package app.spektralab.raw

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DNG reader for CFA (mosaiced) DNGs: uncompressed (8..16 bit, packed or not) or
 * lossless-JPEG compressed (Compression 7), in strips or tiles, with LinearizationTable,
 * repeating black levels, ActiveArea / DefaultCrop, the colour matrices and GainMap
 * opcodes. That covers Android DngCreator output, Adobe DNG Converter output and the
 * DNGs written by most cameras.
 *
 * Returns null (so callers fall back to the system decoder) for LinearRaw (already
 * demosaiced, e.g. Apple ProRAW), lossy JPEG / JPEG XL / deflate (floating point) DNGs,
 * non-rectangular CFA layouts and anything malformed.
 *
 * Tile reassembly of lossless-JPEG data follows LibRaw's lossless_dng_load_raw.
 */
object DngReader {
    fun read(bytes: ByteArray): RawImage? = runCatching { parse(bytes) }.getOrNull()

    private fun parse(bytes: ByteArray): RawImage? {
        val tiff = Tiff.parse(bytes, 0, setOf(42)) ?: return null
        val ifd0 = tiff.ifd0
        if (TiffTags.DNG_VERSION !in ifd0) return null
        // the raw image is IFD0 itself or one of its SubIFDs (when a preview comes first)
        val raw = tiff.allIfds()
            .filter { it.int(TiffTags.PHOTOMETRIC) == 32803 }
            .sortedWith(compareBy({ (it.int(TiffTags.NEW_SUBFILE_TYPE) ?: 0) and 1 }, { -(it.int(TiffTags.IMAGE_WIDTH) ?: 0) }))
            .firstOrNull() ?: return null
        fun anyD(tag: Int) = raw.doubles(tag) ?: ifd0.doubles(tag)

        val width = raw.int(TiffTags.IMAGE_WIDTH) ?: return null
        val height = raw.int(TiffTags.IMAGE_LENGTH) ?: return null
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS) return null
        if (raw.int(TiffTags.SAMPLES_PER_PIXEL, 1) != 1) return null
        if (raw.int(TiffTags.CFA_LAYOUT, 1) != 1) return null
        val bps = raw.int(TiffTags.BITS_PER_SAMPLE, 1)
        val compression = raw.int(TiffTags.COMPRESSION, 1)

        // CFA, relative to the ActiveArea origin
        val dim = raw.ints(TiffTags.CFA_REPEAT_PATTERN_DIM) ?: return null            // rows, cols
        if (dim.size != 2 || dim[0] !in 1..CfaPattern.MAX_DIM || dim[1] !in 1..CfaPattern.MAX_DIM) return null
        val cfaRows = dim[0]; val cfaCols = dim[1]
        val planeColor = raw.ints(TiffTags.CFA_PLANE_COLOR) ?: intArrayOf(0, 1, 2)
        val cfaValues = raw.ints(TiffTags.CFA_PATTERN)?.takeIf { it.size >= cfaRows * cfaCols } ?: return null
        val colors = IntArray(cfaRows * cfaCols) { i -> planeColor.getOrElse(cfaValues[i]) { -1 } }
        if (colors.any { it !in 0..2 }) return null                                          // e.g. CMYG sensors

        val active = raw.ints(TiffTags.ACTIVE_AREA)?.takeIf { it.size == 4 } ?: intArrayOf(0, 0, height, width)  // top, left, bottom, right
        if (active[0] < 0 || active[1] < 0 || active[2] > height || active[3] > width || active[2] <= active[0] || active[3] <= active[1]) return null
        val cfa = CfaPattern(cfaCols, cfaRows, colors).anchoredAt(active[0], active[1])

        // pixels
        val data = readPixels(bytes, raw, width, height, bps, compression) ?: return null
        raw.ints(TiffTags.LINEARIZATION_TABLE, 65536)?.let { table ->
            val last = table.size - 1
            for (i in data.indices) data[i] = table[minOf(data[i].toInt() and 0xffff, last)].coerceIn(0, 65535).toShort()
        }

        // levels: BlackLevel repeats over BlackLevelRepeatDim (rows, cols) from the ActiveArea origin
        val blackRep = raw.ints(TiffTags.BLACK_LEVEL_REPEAT_DIM)?.takeIf { it.size == 2 && it[0] in 1..CfaPattern.MAX_DIM && it[1] in 1..CfaPattern.MAX_DIM } ?: intArrayOf(1, 1)
        val blackVals = raw.doubles(TiffTags.BLACK_LEVEL) ?: doubleArrayOf(0.0)
        val black = blackPerCfaPosition(cfaRows, cfaCols, blackRep[0], blackRep[1], blackVals)
            .let { CfaPattern.shiftPattern(it, cfaCols, cfaRows, active[0], active[1]) }
        val white = (raw.double(TiffTags.WHITE_LEVEL) ?: ((1L shl bps.coerceIn(1, 16)) - 1).toDouble()).toFloat()
        if (white <= black.max()) return null

        // crop: DefaultCropOrigin/Size are relative to the ActiveArea
        val origin = anyD(TiffTags.DEFAULT_CROP_ORIGIN)?.takeIf { it.size >= 2 } ?: doubleArrayOf(0.0, 0.0)          // h, v
        val size = anyD(TiffTags.DEFAULT_CROP_SIZE)?.takeIf { it.size >= 2 }
            ?: doubleArrayOf((active[3] - active[1]).toDouble(), (active[2] - active[0]).toDouble())
        var cropX = active[1] + origin[0].toInt().coerceAtLeast(0)
        var cropY = active[0] + origin[1].toInt().coerceAtLeast(0)
        // keep the crop on even raw coordinates and sizes (as before RawImage handled any phase)
        cropX += cropX % 2; cropY += cropY % 2
        var cropW = minOf(size[0].toInt(), active[3] - cropX)
        var cropH = minOf(size[1].toInt(), active[2] - cropY)
        cropW -= cropW % 2; cropH -= cropH % 2
        if (cropW < 4 || cropH < 4) return null

        // colour
        val colour = colour(raw, ifd0) ?: return null
        val orientation = (ifd0.int(TiffTags.ORIENTATION) ?: raw.int(TiffTags.ORIENTATION) ?: 1).let { if (it in 1..8) it else 1 }
        val baseline = (ifd0.double(TiffTags.BASELINE_EXPOSURE) ?: raw.double(TiffTags.BASELINE_EXPOSURE))?.toFloat() ?: 0f
        val gainMaps = raw[TiffTags.OPCODE_LIST_2]?.let { parseGainMaps(bytes, it.dataOffset, it.byteCount) } ?: emptyList()

        return RawImage(width, height, data, cfa, black, white, cropX, cropY, cropW, cropH,
            colour.wb, colour.camToSrgb, orientation, baseline, gainMaps,
            make = ifd0.string(TiffTags.MAKE) ?: "", model = ifd0.string(TiffTags.MODEL) ?: ifd0.string(TiffTags.UNIQUE_CAMERA_MODEL) ?: "")
    }

    private const val MAX_PIXELS = 400_000_000L

    /** Average black per CFA position over the common period of the CFA and black patterns. */
    private fun blackPerCfaPosition(cfaRows: Int, cfaCols: Int, br: Int, bc: Int, vals: DoubleArray): FloatArray {
        val pr = lcm(cfaRows, br); val pc = lcm(cfaCols, bc)
        val sum = DoubleArray(cfaRows * cfaCols)
        val n = IntArray(cfaRows * cfaCols)
        for (r in 0 until pr) for (c in 0 until pc) {
            val i = (r % cfaRows) * cfaCols + c % cfaCols
            sum[i] += vals[minOf(((r % br) * bc + c % bc), vals.size - 1)]
            n[i]++
        }
        return FloatArray(sum.size) { (sum[it] / n[it]).toFloat() }
    }

    private fun lcm(a: Int, b: Int): Int { var x = a; var y = b; while (y != 0) { val t = x % y; x = y; y = t }; return a / x * b }

    // ---------------------------------------------------------------- pixel data
    private fun readPixels(bytes: ByteArray, raw: TiffIfd, width: Int, height: Int, bps: Int, compression: Int): ShortArray? {
        if (compression != 1 && compression != 7) return null
        if (compression == 1 && bps !in 8..16) return null
        val tiled = TiffTags.TILE_WIDTH in raw
        val tw = if (tiled) raw.int(TiffTags.TILE_WIDTH) ?: return null else width
        val th = if (tiled) raw.int(TiffTags.TILE_LENGTH) ?: return null else raw.int(TiffTags.ROWS_PER_STRIP, height).coerceIn(1, height)
        if (tw <= 0 || th <= 0) return null
        val offsets = raw.longs(if (tiled) TiffTags.TILE_OFFSETS else TiffTags.STRIP_OFFSETS) ?: return null
        val counts = raw.longs(if (tiled) TiffTags.TILE_BYTE_COUNTS else TiffTags.STRIP_BYTE_COUNTS)
        val across = (width + tw - 1) / tw
        val down = (height + th - 1) / th
        if (offsets.size < across.toLong() * down) return null
        val data = ShortArray(width * height)
        for (t in 0 until across * down) {
            val tx = (t % across) * tw
            val ty = (t / across) * th
            val cols = minOf(tw, width - tx)
            val rows = minOf(th, height - ty)
            val off = offsets[t]
            if (off <= 0 || off >= bytes.size) return null
            val len = (counts?.getOrNull(t) ?: (bytes.size - off)).coerceAtMost(bytes.size - off).toInt()
            val ok = if (compression == 1) {
                val rowBytes = if (bps == 16) tw * 2L else Unpack.rowBytes(tw, bps)
                if (bps == 16) Unpack.u16(bytes, off.toInt(), rowBytes.toInt(), cols, rows, raw.littleEndian, data, ty * width + tx, width)
                else Unpack.packed(bytes, off.toInt(), rowBytes.toInt(), cols, rows, bps, Unpack.BitOrder.MSB, data, ty * width + tx, width)
            } else ljpegTile(bytes, off.toInt(), len, data, width, tx, ty, tw, cols, rows)
            if (!ok) return null
        }
        return data
    }

    /** Decodes one lossless-JPEG tile: the sample stream fills the tile row by row, [tw] samples per row. */
    private fun ljpegTile(bytes: ByteArray, off: Int, len: Int, data: ShortArray, width: Int, tx: Int, ty: Int, tw: Int, cols: Int, rows: Int): Boolean {
        var r = 0
        var c = 0
        val h = LosslessJpeg.decode(bytes, off, len) { _, s ->
            for (v in s.indices) {
                if (r >= rows) break
                if (c < cols) data[(ty + r) * width + tx + c] = s[v].toShort()
                if (++c >= tw) { c = 0; r++ }
            }
        } ?: return false
        return h.rowSamples.toLong() * h.height >= (rows - 1).toLong() * tw + cols
    }

    // ---------------------------------------------------------------- colour
    private fun colour(raw: TiffIfd, ifd0: TiffIfd): RawColor.Result? {
        fun mat(tag: Int) = (ifd0.doubles(tag) ?: raw.doubles(tag))?.takeIf { it.size == 9 }
        fun illum(tag: Int) = (ifd0.int(tag) ?: raw.int(tag)) ?: 0
        // prefer the daylight calibration (usually illuminant 2 = D65)
        val second = illum(TiffTags.CALIBRATION_ILLUMINANT_2).let { it == 21 || it == 20 || it == 23 || it == 1 } || mat(TiffTags.COLOR_MATRIX_1) == null
        val cm = (if (second) mat(TiffTags.COLOR_MATRIX_2) else null) ?: mat(TiffTags.COLOR_MATRIX_1) ?: mat(TiffTags.COLOR_MATRIX_2) ?: return null
        val usedSecond = second && mat(TiffTags.COLOR_MATRIX_2) != null
        val fm = (if (second) mat(TiffTags.FORWARD_MATRIX_2) else null) ?: mat(TiffTags.FORWARD_MATRIX_1) ?: mat(TiffTags.FORWARD_MATRIX_2)
        val cc = if (usedSecond) mat(TiffTags.CAMERA_CALIBRATION_2) else mat(TiffTags.CAMERA_CALIBRATION_1)
        val ab = (ifd0.doubles(TiffTags.ANALOG_BALANCE) ?: raw.doubles(TiffTags.ANALOG_BALANCE))?.takeIf { it.size == 3 }
        val neutral = (ifd0.doubles(TiffTags.AS_SHOT_NEUTRAL) ?: raw.doubles(TiffTags.AS_SHOT_NEUTRAL))?.takeIf { it.size == 3 && it.all { v -> v > 0 } }
            ?: (ifd0.doubles(TiffTags.AS_SHOT_WHITE_XY) ?: raw.doubles(TiffTags.AS_SHOT_WHITE_XY))?.takeIf { it.size == 2 && it[1] > 0 }?.let { xy ->
                val abcc = Mat3.mul(ab?.let { doubleArrayOf(it[0], 0.0, 0.0, 0.0, it[1], 0.0, 0.0, 0.0, it[2]) } ?: Mat3.IDENTITY, cc ?: Mat3.IDENTITY)
                Mat3.mulVec(Mat3.mul(abcc, cm), RawColor.xyToXyz(xy[0], xy[1])).takeIf { n -> n.all { it > 0 } }
            }
            ?: doubleArrayOf(1.0, 1.0, 1.0)
        return RawColor.fromDng(cm, fm, neutral, cc, ab)
    }

    // ---------------------------------------------------------------- opcodes
    private fun parseGainMaps(bytes: ByteArray, start: Int, length: Int): List<GainMap> {
        val out = ArrayList<GainMap>()
        runCatching {
            // opcode lists are always big endian
            val o = ByteBuffer.wrap(bytes, start, length).order(ByteOrder.BIG_ENDIAN)
            val n = o.int
            repeat(n.coerceIn(0, 1024)) {
                val id = o.int; o.int; o.int
                val size = o.int
                if (size < 0 || size > o.remaining()) return out
                val next = o.position() + size
                if (id == 9) {
                    val top = o.int; val left = o.int; val bottom = o.int; val right = o.int
                    o.int; o.int                                                   // plane, planes
                    val rowPitch = o.int; val colPitch = o.int
                    val pv = o.int; val ph = o.int
                    val sv = o.double; val sh = o.double; val ov = o.double; val oh = o.double
                    val mapPlanes = o.int
                    if (pv >= 1 && ph >= 1 && mapPlanes >= 1 && pv.toLong() * ph * mapPlanes * 4 <= o.remaining()) {
                        val gains = FloatArray(pv * ph)
                        for (i in 0 until pv * ph) { gains[i] = o.float; repeat(mapPlanes - 1) { o.float } }
                        if (rowPitch > 0 && colPitch > 0 && sv > 0 && sh > 0)
                            out += GainMap(top, left, bottom, right, rowPitch, colPitch, pv, ph, sv, sh, ov, oh, gains)
                    }
                }
                o.position(next)
            }
        }
        return out
    }

    // ---------------------------------------------------------------- kept for existing callers
    internal fun mul(a: DoubleArray, b: DoubleArray) = Mat3.mul(a, b)
    internal fun invert(m: DoubleArray): DoubleArray? = Mat3.invert(m)
}
