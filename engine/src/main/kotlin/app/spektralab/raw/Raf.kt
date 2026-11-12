// Fujifilm RAF reader.
//
// Container layout, metadata rules (frame size, margins, crop, CFA, black / white levels,
// white balance) and the uncompressed loaders are ported from LibRaw 0.22.2:
// src/metadata/identify.cpp (RAF header, `identify_finetune_dcr` Fujifilm branch),
// src/metadata/fuji.cpp (`parse_fuji`, `guess_RAFDataGeneration`), src/metadata/tiff.cpp
// (0xF00x raw-section tags), src/utils/open.cpp (X-Trans margin / crop alignment, choice of
// loader) and src/decoders/{generic,decoders_libraw}.cpp (`unpacked_load_raw`,
// `fuji_14bit_load_raw`). LibRaw is Copyright 2008-2025 LibRaw LLC (LGPL 2.1 / CDDL 1.0),
// derived from dcraw.c by Dave Coffin; the LGPL permits use in this GPLv3 program.
package app.spektralab.raw

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reader for Fujifilm RAF files of the X / GFX era: a big-endian RAF header and tag
 * directory, an embedded JPEG (whose EXIF holds Make, Model, Orientation and the maker
 * note) and a TIFF-like raw section (`II*`, IFD 0xF000 with tags 0xF001..0xF00E).
 *
 * Pixel layouts: 16-bit words (the usual "uncompressed" RAF, byte order of the file's
 * TIFF blocks), 14-bit packed (LibRaw `fuji_14bit_load_raw`) and the Fujifilm
 * lossless-compressed format ([FujiCompressed], when that decoder accepts the file).
 * Sensors: X-Trans (6x6, tag 0x131) and Bayer.
 *
 * Returns null for anything else, in particular SuperCCD (45-degree "fuji_width" layouts
 * and `fuji_layout` dual-frame files), the S2Pro / DBP / S20Pro / F700 special cases, files
 * without a raw-section TIFF (pre-2010 cameras), 12-bit packed data (tag 0xF006), lossy
 * compression, unknown cameras (no colour matrix) and truncated or malformed files.
 */
object RafReader {
    fun read(bytes: ByteArray): RawImage? = try {
        val info = parse(bytes)
        if (info == null) null else decode(bytes, info)
    } catch (e: RuntimeException) {
        null
    }

    /** How the pixel data is stored. */
    enum class Layout { UNPACKED16, PACKED14, COMPRESSED }

    /** Everything [parse] learns about a file before the pixels are read. */
    internal class Info(
        val make: String, val model: String,
        val rawWidth: Int, val rawHeight: Int, val bps: Int,
        val layout: Layout, val dataOffset: Int, val dataSize: Int, val littleEndian: Boolean,
        /** CFA anchored at raw (0, 0); a 6x6 X-Trans or a Bayer pattern, possibly widened for [black]. */
        val cfa: CfaPattern, val xtrans: Boolean,
        val black: FloatArray, val white: Int,
        /** Visible area after LibRaw's margin alignment (LibRaw `left_margin`, `top_margin`, `width`, `height`). */
        val left: Int, val top: Int, val width: Int, val height: Int,
        /** Output crop in raw coordinates (LibRaw `raw_inset_crops[0]`, or the visible area). */
        val cropX: Int, val cropY: Int, val cropWidth: Int, val cropHeight: Int,
        /** As-shot multipliers R, G, B (LibRaw `cam_mul`), or null. */
        val asShot: DoubleArray?,
        val orientation: Int,
        val xyzToCamera: DoubleArray,
    )

    // RAF directory tags
    private const val RAW_IMAGE_FULL_SIZE = 0x100
    private const val RAW_IMAGE_CROP_TOP_LEFT = 0x110
    private const val RAW_IMAGE_CROPPED_SIZE = 0x111
    private const val RAW_IMAGE_SIZE = 0x121
    private const val FUJI_LAYOUT = 0x130
    private const val XTRANS_LAYOUT = 0x131
    private const val WB_GRGB_LEVELS = 0x2ff0
    private const val BLACK_LEVEL = 0x4000
    private const val RAF_DATA = 0xc000
    // raw-section tags
    private const val RAW_IFD = 0xf000
    private const val RAW_WIDTH = 0xf001
    private const val RAW_HEIGHT = 0xf002
    private const val RAW_BITS = 0xf003
    private const val RAW_PACKED = 0xf006
    private const val STRIP_OFFSET = 0xf007
    private const val STRIP_BYTES = 0xf008
    private const val RAW_BLACK = 0xf00a
    private const val WB_AS_SHOT = 0xf00e
    // maker note
    private const val CROP_MODE = 0x104d

    private const val FILTERS_XTRANS = 9

    internal fun parse(bytes: ByteArray): Info? {
        if (bytes.size < 160 || !Bytes.matches(bytes, 0, "FUJIFILM")) return null
        fun u32(p: Int): Long = if (p >= 0 && p + 4 <= bytes.size) Bytes.u32be(bytes, p) else -1L
        val headerModel = ascii(bytes, 0x1c, 32)
        val thumbOffset = u32(84)
        val dirOffset = u32(92)
        val rawOffset = u32(100)
        if (rawOffset <= 0 || rawOffset >= bytes.size) return null

        // ---------------------------------------------------------------- RAF directory
        var rawH0 = 0; var rawW0 = 0; var insetPresent = false
        var cTop = -1; var cLeft = -1; var cHeight = 0; var cWidth = 0
        var height = 0; var width = 0
        var fujiLayout = 0; var fujiWidth = false
        var xtransAbs: IntArray? = null
        var dirWb: DoubleArray? = null
        var dirBlack: DoubleArray? = null
        var gen = 0
        var tableW = 0; var tableH = 0
        if (dirOffset in 0 until bytes.size - 4L) {
            var p = dirOffset.toInt()
            val entries = Bytes.u32be(bytes, p)
            p += 4
            if (entries <= 255) for (e in 0 until entries.toInt()) {
                if (p + 4 > bytes.size) break
                val tag = Bytes.u16be(bytes, p)
                val len = Bytes.u16be(bytes, p + 2)
                val d = p + 4
                if (d + len > bytes.size) break
                fun s(i: Int) = Bytes.u16be(bytes, d + 2 * i)
                when {
                    tag == RAW_IMAGE_FULL_SIZE && len >= 4 -> { rawH0 = s(0); rawW0 = s(1); insetPresent = true }
                    tag == RAW_IMAGE_CROP_TOP_LEFT && insetPresent && len >= 4 -> { cTop = s(0); cLeft = s(1) }
                    tag == RAW_IMAGE_CROPPED_SIZE && insetPresent && len >= 4 -> { cHeight = s(0); cWidth = s(1) }
                    tag == RAW_IMAGE_SIZE && len >= 4 -> { height = s(0); width = s(1); if (width == 4284) width += 3 }
                    tag == FUJI_LAYOUT && len >= 2 -> {
                        fujiLayout = (bytes[d].toInt() and 0xff) shr 7
                        fujiWidth = (bytes[d + 1].toInt() and 8) == 0
                    }
                    tag == XTRANS_LAYOUT && len >= 36 ->
                        xtransAbs = IntArray(36).also { a -> for (c in 0 until 36) a[35 - c] = (bytes[d + c].toInt() and 0xff).coerceIn(0, 2) }
                    tag == WB_GRGB_LEVELS && len >= 8 -> {
                        // G R G B, averaged with a second set when there are two
                        val v = DoubleArray(4) { if (len == 16) ((s(it) + s(it + 4)) / 2).toDouble() else s(it).toDouble() }
                        dirWb = doubleArrayOf(v[1], v[0], v[3])
                    }
                    tag == BLACK_LEVEL && (len == 8 || len == 16) -> {
                        val v = IntArray(4) { if (len == 16) (s(it) + s(it + 4)) / 2 else s(it) }
                        dirBlack = doubleArrayOf(v[1].toDouble(), v[0].toDouble(), v[3].toDouble())
                    }
                    tag == RAF_DATA && len > 20000 -> {
                        val b01 = Bytes.u16le(bytes, d); val b23 = Bytes.u16le(bytes, d + 2)
                        val wrts = Bytes.u32le(bytes, d + 4) == 0x53545257L
                        var off = 0
                        when {
                            b01 != 0 && b23 == 0 && b01 < 10000 -> { gen = 1; off = 0 }
                            b01 > 10000 && b23 == 0 -> { gen = 2; off = 4 }
                            b01 == 0 -> if (!wrts) { gen = 3; off = 4 } else { gen = 4; off = 8 }
                        }
                        tableW = Bytes.u32le(bytes, d + off).toInt(); tableH = Bytes.u32le(bytes, d + off + 4).toInt()
                    }
                    tag == RAF_DATA && len == 4096 -> gen = 4096
                }
                p = d + len
            }
        }
        if (fujiWidth || fujiLayout != 0) return null          // SuperCCD layouts

        // ---------------------------------------------------------------- raw section (TIFF-like)
        val rawTiff = Tiff.parse(bytes, rawOffset.toInt(), setOf(42)) ?: return null
        val rawIfd = rawTiff.ifd0.int(RAW_IFD)?.let { rawTiff.ifdAt(rawTiff.base + it) } ?: rawTiff.ifd0
        val rawWidth = rawIfd.int(RAW_WIDTH) ?: return null
        val rawHeight = rawIfd.int(RAW_HEIGHT) ?: return null
        val bps = rawIfd.int(RAW_BITS) ?: return null
        if (bps !in 8..16 || rawWidth <= 0 || rawHeight <= 0) return null
        if (rawWidth.toLong() * rawHeight > RawDecodeLimits.MAX_SAMPLES) return null
        if (RAW_PACKED in rawIfd && bps <= 12) return null      // LibRaw packed_load_raw (12-bit): not supported
        val dataOffset = rawTiff.base.toLong() + (rawIfd.int(STRIP_OFFSET)?.toLong()?.and(0xffffffffL) ?: return null)
        val dataSize = rawIfd.int(STRIP_BYTES)?.toLong()?.and(0xffffffffL) ?: return null
        if (dataOffset <= 0 || dataOffset >= bytes.size || dataSize <= 0) return null

        // ---------------------------------------------------------------- embedded JPEG EXIF
        val exifTiff = if (thumbOffset in 1 until bytes.size - 20L) Tiff.parse(bytes, thumbOffset.toInt() + 12) else null
        val exifMake = exifTiff?.ifd0?.string(TiffTags.MAKE)?.takeIf { it.isNotEmpty() }
        val exifModel = exifTiff?.ifd0?.string(TiffTags.MODEL)?.takeIf { it.isNotEmpty() }
        val make = exifMake ?: "FUJIFILM"
        val model = exifModel ?: headerModel
        if (model.isEmpty()) return null
        val orientation = (exifTiff?.ifd0?.int(TiffTags.ORIENTATION) ?: 1).let { if (it in 1..8) it else 1 }
        val cropMode = exifTiff?.let { t -> runCatching { t.makerNote() }.getOrNull()?.takeIf { it.kind == "fuji" }?.ifd?.int(CROP_MODE) } ?: 0
        // LibRaw reads the 16-bit words in the byte order of the last TIFF block it parsed (the JPEG EXIF)
        val littleEndian = exifTiff?.littleEndian ?: rawTiff.littleEndian
        if (model.startsWith("S2Pro") || model.startsWith("S20Pro") || model.startsWith("F700") || model.startsWith("DBP")) return null

        // ---------------------------------------------------------------- layout
        val layout = when {
            dataSize == rawWidth.toLong() * rawHeight * 2 -> Layout.UNPACKED16
            dataSize == rawWidth.toLong() * rawHeight * 7 / 4 -> Layout.PACKED14
            else -> Layout.COMPRESSED
        }
        if (layout != Layout.COMPRESSED && dataOffset + dataSize > bytes.size) return null   // truncated

        // ---------------------------------------------------------------- frame geometry (identify_finetune_dcr)
        var filters = if (xtransAbs != null) FILTERS_XTRANS else 0x94949494.toInt()
        var white = 0
        if (filters != FILTERS_XTRANS && !model.startsWith("X-")) white = 0x3e00
        if (cropMode == 0 && gen != 0 && gen != 4096) { width = tableW; height = tableH }
        else if (cropMode == 1) { width = rawWidth; height = rawHeight }
        else if (cropMode == 4) height = rawHeight
        if (width <= 0) width = rawWidth
        if (height <= 0) height = rawHeight
        if (width > rawWidth || height > rawHeight) return null
        var top = (rawHeight - height) shr 2 shl 1
        var left = (rawWidth - width) shr 2 shl 1
        val insetValid = cWidth > 0 && cHeight > 0 && cLeft >= 0 && cTop >= 0 && cLeft + cWidth <= rawWidth && cTop + cHeight <= rawHeight
        fun useInset() { if (insetValid) { top = cTop; left = cLeft; width = cWidth; height = cHeight } }
        if (gen != 0 && gen != 4096) {
            when (rawWidth) {
                2944 -> filters = 0x16161616
                4096, 5120, 6048, 6160 -> left = 0
                4992 -> left = 4
                7872 -> if (cropMode == 0) { top = 6; left = 0; width = 7752; height = 5178 } else useInset()
                6336 -> when (cropMode) {
                    0 -> { top = 6; left = 0; width = if (model.equals("X-S20", ignoreCase = true)) 6252 else 6264; height = 4176 }
                    2, 4 -> { left = 630; top = 0; height = 3348; width = 5004 }
                    else -> useInset()
                }
                6384 -> {
                    top = 0
                    when (cropMode) {
                        0 -> { left = 0; top = 6; width = 6246; height = 4170 }
                        2 -> { left = 624; width = 5004; height = rawHeight }
                        4 -> { left = 624; width = 5004 }
                    }
                }
                6912, 9216 -> { left = 0; top = 0 }
                8472 -> { left = 0; top = 0; width = rawWidth - 192 }
                9696, 11808 -> {
                    left = 0; width = rawWidth - 146; height = rawHeight - 8; top = 2
                    if (bps == 16) white = 0xffff
                }
            }
        } else if (gen == 0) {
            when (rawWidth) {
                2304 -> { top = 6; height -= 6 }
                3328 -> {
                    width = rawWidth - 66; left = 34
                    if (insetPresent && cLeft == 8) { left = 0; width += 2; filters = 0x16161616 }
                }
                3664 -> filters = 0x16161616
                2944, 5504 -> return null                     // S2Pro / DBP special cases
                else -> if (!model.startsWith("S6000") && !model.startsWith("S6500")) useInset()
            }
        }
        if (left < 0 || top < 0 || width <= 0 || height <= 0 || left + width > rawWidth || top + height > rawHeight) return null
        val xtrans = filters == FILTERS_XTRANS
        // CFA in raw coordinates: xtrans_abs is already anchored at the raw origin; a dcraw
        // `filters` word is relative to the visible origin as identify() leaves it.
        val baseCfa = if (xtrans) CfaPattern(6, 6, xtransAbs!!) else CfaPattern.fromDcrawFilters(filters).anchoredAt(top, left)

        // open.cpp: align the margins to the CFA period, then the inset crop likewise
        val align = if (xtrans) 6 else 2
        run {
            val newTop = (top + align - 1) / align * align
            val newLeft = (left + align - 1) / align * align
            height -= newTop - top; width -= newLeft - left
            top = newTop; left = newLeft
        }
        if (width <= 0 || height <= 0) return null
        var cx = left; var cy = top; var cw = width; var ch = height
        if (insetValid) {
            var l = cLeft; var t = cTop; var w = cWidth; var h = cHeight
            if (l != 0 && l % align != 0 && w > align) { val nl = (l / align + 1) * align; w -= nl - l; l = nl }
            if (t != 0 && t % align != 0 && h > align) { val nt = (t / align + 1) * align; h -= nt - t; t = nt }
            // keep it inside the visible area
            val x0 = maxOf(l, left); val y0 = maxOf(t, top)
            val x1 = minOf(l + w, left + width); val y1 = minOf(t + h, top + height)
            if (x1 > x0 && y1 > y0) { cx = x0; cy = y0; cw = x1 - x0; ch = y1 - y0 }
        }

        // ---------------------------------------------------------------- colour data
        val entry = CameraMatrices.find(make, model) ?: return null
        val matrix = entry.xyzToCamera?.takeIf { entry.colors == 3 }?.let { m -> DoubleArray(9) { m[it / 3][it % 3] } } ?: return null

        // black: 0xF00A (an n x n pattern relative to the visible origin) or the directory's 0x4000
        var cfa = baseCfa
        var black: FloatArray
        val blackVals = rawIfd.ints(RAW_BLACK, 4096)
        val n = blackVals?.let { minOf(kotlin.math.sqrt(it.size.toDouble()).toInt(), 64) } ?: 0
        if (blackVals != null && n >= 2 && n * n <= blackVals.size) {
            val pw = lcm(baseCfa.width, n); val ph = lcm(baseCfa.height, n)
            if (pw <= CfaPattern.MAX_DIM && ph <= CfaPattern.MAX_DIM) {
                cfa = CfaPattern(pw, ph, IntArray(pw * ph) { baseCfa.color(it / pw, it % pw) })
                black = FloatArray(pw * ph) { i ->
                    blackVals[Math.floorMod(i / pw - top, n) * n + Math.floorMod(i % pw - left, n)].toFloat()
                }
            } else {
                // a pattern we cannot represent: per-colour means
                val sum = DoubleArray(3); val cnt = IntArray(3)
                for (r in 0 until n) for (c in 0 until n) {
                    val col = baseCfa.color(r + top, c + left); sum[col] += blackVals[r * n + c]; cnt[col]++
                }
                black = baseCfa.perColor(FloatArray(3) { if (cnt[it] > 0) (sum[it] / cnt[it]).toFloat() else 0f })
            }
        } else if (blackVals != null && blackVals.size == 1) {
            black = FloatArray(baseCfa.size) { blackVals[0].toFloat() }
        } else if (dirBlack != null) {
            black = baseCfa.perColor(FloatArray(3) { dirBlack!![it].toFloat() })
        } else {
            black = FloatArray(baseCfa.size)
        }
        // adobe_coeff: the table's black is forced (> 0) or a fallback (< 0); its maximum wins
        if (entry.black > 0 || (entry.black < 0 && black.all { it == 0f })) {
            cfa = baseCfa
            black = FloatArray(baseCfa.size) { entry.blackLevel.toFloat() }
        }
        if (entry.white > 0) white = entry.white
        if (white == 0) white = (1 shl bps) - 1
        if (white > 0xffff) white = 0xffff
        if (white <= black.max()) return null

        val asShot = rawIfd.ints(WB_AS_SHOT)?.takeIf { it.size >= 3 }?.let { doubleArrayOf(it[1].toDouble(), it[0].toDouble(), it[2].toDouble()) }
            ?: dirWb
        return Info(make, model, rawWidth, rawHeight, bps, layout, dataOffset.toInt(), dataSize.toInt(), littleEndian,
            cfa, xtrans, black, white, left, top, width, height, cx, cy, cw, ch,
            asShot?.takeIf { m -> m.all { it > 0 } }, orientation, matrix)
    }

    private fun decode(bytes: ByteArray, info: Info): RawImage? {
        val data: ShortArray
        var rawWidth = info.rawWidth
        var rawHeight = info.rawHeight
        when (info.layout) {
            Layout.UNPACKED16 -> {
                data = ShortArray(rawWidth * rawHeight)
                ByteBuffer.wrap(bytes, info.dataOffset, info.dataSize)
                    .order(if (info.littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
                    .asShortBuffer().get(data)
            }
            Layout.PACKED14 -> data = unpack14(bytes, info.dataOffset, rawWidth, rawHeight)
            Layout.COMPRESSED -> {
                val c = FujiCompressed.decode(bytes, info.dataOffset, info.dataSize, info.cfa) ?: return null
                // the compressed header carries the stored frame size; it must hold the visible area
                if (c.width > rawWidth || c.height > rawHeight || info.left + info.width > c.width || info.top + info.height > c.height) return null
                if (info.cropX + info.cropWidth > c.width || info.cropY + info.cropHeight > c.height) return null
                data = c.data; rawWidth = c.width; rawHeight = c.height
            }
        }
        val colour = RawColor.fromXyzToCamera(info.xyzToCamera, info.asShot) ?: return null
        return RawImage(
            rawWidth, rawHeight, data, info.cfa, info.black, info.white.toFloat(),
            info.cropX, info.cropY, info.cropWidth, info.cropHeight,
            colour.wb, colour.camToSrgb, info.orientation,
            make = info.make, model = info.model,
        )
    }

    /**
     * LibRaw `fuji_14bit_load_raw`: rows of `width * 7 / 4` bytes; each 32-bit word is
     * byte-swapped, then every 7 bytes hold four MSB-first 14-bit samples.
     */
    internal fun unpack14(bytes: ByteArray, offset: Int, width: Int, height: Int): ShortArray {
        val lineLen = width * 7 / 4
        val out = ShortArray(width * height)
        val swapped = lineLen / 4 * 4
        val line = ByteArray(lineLen + 8)
        for (row in 0 until height) {
            val s = offset + row * lineLen
            for (i in 0 until swapped) line[i] = bytes[s + (i and 3.inv()) + 3 - (i and 3)]
            for (i in swapped until lineLen) line[i] = bytes[s + i]
            var sp = 0; var dp = 0
            val o = row * width
            while (dp < width - 3 && sp < lineLen - 6) {
                val b0 = line[sp].toInt() and 0xff; val b1 = line[sp + 1].toInt() and 0xff
                val b2 = line[sp + 2].toInt() and 0xff; val b3 = line[sp + 3].toInt() and 0xff
                val b4 = line[sp + 4].toInt() and 0xff; val b5 = line[sp + 5].toInt() and 0xff
                val b6 = line[sp + 6].toInt() and 0xff
                out[o + dp] = ((b0 shl 6) or (b1 shr 2)).toShort()
                out[o + dp + 1] = (((b1 and 3) shl 12) or (b2 shl 4) or (b3 shr 4)).toShort()
                out[o + dp + 2] = (((b3 and 0xf) shl 10) or (b4 shl 2) or (b5 shr 6)).toShort()
                out[o + dp + 3] = (((b5 and 0x3f) shl 8) or b6).toShort()
                sp += 7; dp += 4
            }
        }
        return out
    }

    private fun ascii(b: ByteArray, p: Int, max: Int): String {
        var n = 0
        while (n < max && p + n < b.size && b[p + n] != 0.toByte()) n++
        return String(b, p, n, Charsets.ISO_8859_1).trim()
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    private fun lcm(a: Int, b: Int) = a / gcd(a, b) * b
}
