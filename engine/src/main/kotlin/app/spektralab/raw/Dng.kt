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
 * Also reads 16-bit linear RGB strips (uncompressed or deflate, including half-float).
 * Returns null for lossy JPEG / JPEG XL, unsupported layouts,
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
            .filter { it.int(TiffTags.PHOTOMETRIC) in setOf(32803, 34892) }
            .sortedWith(compareBy({ (it.int(TiffTags.NEW_SUBFILE_TYPE) ?: 0) and 1 }, { -(it.int(TiffTags.IMAGE_WIDTH) ?: 0) }))
            .firstOrNull() ?: return null
        fun anyD(tag: Int) = raw.doubles(tag) ?: ifd0.doubles(tag)

        val linear = raw.int(TiffTags.PHOTOMETRIC) == 34892
        val width = raw.int(TiffTags.IMAGE_WIDTH) ?: return null
        val height = raw.int(TiffTags.IMAGE_LENGTH) ?: return null
        if (width <= 0 || height <= 0 || width.toLong() * height * (if (linear) 3 else 1) > RawDecodeLimits.MAX_SAMPLES) return null
        if (raw.int(TiffTags.SAMPLES_PER_PIXEL, 1) != (if (linear) 3 else 1)) return null
        if (raw.int(TiffTags.CFA_LAYOUT, 1) != 1) return null
        val bps = raw.int(TiffTags.BITS_PER_SAMPLE, 1)
        val compression = raw.int(TiffTags.COMPRESSION, 1)

        // CFA, relative to the ActiveArea origin
        val dim = if (linear) intArrayOf(2, 2) else raw.ints(TiffTags.CFA_REPEAT_PATTERN_DIM) ?: return null            // rows, cols
        if (dim.size != 2 || dim[0] !in 1..CfaPattern.MAX_DIM || dim[1] !in 1..CfaPattern.MAX_DIM) return null
        val cfaRows = dim[0]; val cfaCols = dim[1]
        val planeColor = raw.ints(TiffTags.CFA_PLANE_COLOR) ?: intArrayOf(0, 1, 2)
        val cfaValues = if (linear) intArrayOf(0, 1, 1, 2) else raw.ints(TiffTags.CFA_PATTERN)?.takeIf { it.size >= cfaRows * cfaCols } ?: return null
        val colors = IntArray(cfaRows * cfaCols) { i -> planeColor.getOrElse(cfaValues[i]) { -1 } }
        if (colors.any { it !in 0..2 }) return null                                          // e.g. CMYG sensors

        val active = raw.ints(TiffTags.ACTIVE_AREA)?.takeIf { it.size == 4 } ?: intArrayOf(0, 0, height, width)  // top, left, bottom, right
        if (active[0] < 0 || active[1] < 0 || active[2] > height || active[3] > width || active[2] <= active[0] || active[3] <= active[1]) return null
        val cfa = CfaPattern(cfaCols, cfaRows, colors).anchoredAt(active[0], active[1])

        // pixels
        val data = (if (linear) readLinear(bytes, raw, width, height, bps, compression)
            else readPixels(bytes, raw, width, height, bps, compression)) ?: return null
        raw.ints(TiffTags.LINEARIZATION_TABLE, 65536)?.let { table ->
            val last = table.size - 1
            for (i in data.indices) data[i] = table[minOf(data[i].toInt() and 0xffff, last)].coerceIn(0, 65535).toShort()
        }

        // levels: BlackLevel repeats over BlackLevelRepeatDim (rows, cols) from the ActiveArea origin
        val blackRep = raw.ints(TiffTags.BLACK_LEVEL_REPEAT_DIM)?.takeIf { it.size == 2 && it[0] in 1..CfaPattern.MAX_DIM && it[1] in 1..CfaPattern.MAX_DIM } ?: intArrayOf(1, 1)
        val blackVals = raw.doubles(TiffTags.BLACK_LEVEL) ?: doubleArrayOf(0.0)
        val black = blackPerCfaPosition(cfaRows, cfaCols, blackRep[0], blackRep[1], blackVals)
            .let { CfaPattern.shiftPattern(it, cfaCols, cfaRows, active[0], active[1]) }
        val white = if (linear && raw.int(339, 1) == 3) 65535f else (raw.double(TiffTags.WHITE_LEVEL) ?: ((1L shl bps.coerceIn(1, 16)) - 1).toDouble()).toFloat()
        if (white <= black.max()) return null

        // crop: DefaultCropOrigin/Size are relative to the ActiveArea
        val origin = anyD(TiffTags.DEFAULT_CROP_ORIGIN)?.takeIf { it.size >= 2 } ?: doubleArrayOf(0.0, 0.0)          // h, v
        val size = anyD(TiffTags.DEFAULT_CROP_SIZE)?.takeIf { it.size >= 2 }
            ?: doubleArrayOf((active[3] - active[1]).toDouble(), (active[2] - active[0]).toDouble())
        var cropX = active[1] + origin[0].toInt().coerceAtLeast(0)
        var cropY = active[0] + origin[1].toInt().coerceAtLeast(0)
        // keep the crop on even raw coordinates and sizes (as before RawImage handled any phase)
        if (!linear) { cropX += cropX % 2; cropY += cropY % 2 }
        var cropW = minOf(size[0].toInt(), active[3] - cropX)
        var cropH = minOf(size[1].toInt(), active[2] - cropY)
        if (!linear) { cropW -= cropW % 2; cropH -= cropH % 2 }
        if (cropW < 4 || cropH < 4) return null

        // colour
        val colour = colour(raw, ifd0) ?: return null
        val orientation = (ifd0.int(TiffTags.ORIENTATION) ?: raw.int(TiffTags.ORIENTATION) ?: 1).let { if (it in 1..8) it else 1 }
        val baseline = (ifd0.double(TiffTags.BASELINE_EXPOSURE) ?: raw.double(TiffTags.BASELINE_EXPOSURE))?.toFloat() ?: 0f
        val gainMaps = raw[TiffTags.OPCODE_LIST_2]?.let { parseGainMaps(bytes, it.dataOffset, it.byteCount) } ?: emptyList()

        val noise = anyD(51041)?.takeIf { it.size >= 2 && it.size % 2 == 0 }
        val noiseScale = noise?.let { n -> (n.indices step 2).map { n[it] }.average().toFloat() }?.takeIf { it > 0f }
        val noiseOffset = noise?.let { n -> (1 until n.size step 2).map { n[it] }.average().toFloat() }?.coerceAtLeast(0f) ?: 0f
        return RawImage(width, height, data, cfa, black, white, cropX, cropY, cropW, cropH,
            colour.wb, colour.camToSrgb, orientation, baseline, gainMaps,
            make = ifd0.string(TiffTags.MAKE) ?: "", model = ifd0.string(TiffTags.MODEL) ?: ifd0.string(TiffTags.UNIQUE_CAMERA_MODEL) ?: "",
            noiseScale = noiseScale, noiseOffset = noiseOffset, linear = linear, tags = keepTags(ifd0, raw))
    }

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
        // TIFF permits unsigned 0xffffffff to mean one strip covering the image.
        val th = if (tiled) raw.int(TiffTags.TILE_LENGTH) ?: return null else
            (raw.longs(TiffTags.ROWS_PER_STRIP, 1)?.firstOrNull() ?: height.toLong()).coerceAtMost(height.toLong()).toInt()
        if (tw <= 0 || th <= 0 || tw.toLong() * th > RawDecodeLimits.MAX_SAMPLES) return null
        val across = (width.toLong() + tw - 1) / tw
        val down = (height.toLong() + th - 1) / th
        val tiles = across * down
        if (tiles !in 1..RawDecodeLimits.MAX_SEGMENTS.toLong()) return null
        val offsetsTag = if (tiled) TiffTags.TILE_OFFSETS else TiffTags.STRIP_OFFSETS
        val countsTag = if (tiled) TiffTags.TILE_BYTE_COUNTS else TiffTags.STRIP_BYTE_COUNTS
        fun segments(tag: Int): LongArray? {
            val entry = raw[tag] ?: return null
            if (entry.count !in tiles.toInt()..RawDecodeLimits.MAX_SEGMENTS) return null
            return raw.longs(tag, RawDecodeLimits.MAX_SEGMENTS)
        }
        val offsets = segments(offsetsTag) ?: return null
        val counts = segments(countsTag) ?: return null
        val stride = Unpack.rowBytes(tw, bps)
        if (compression == 1 && stride !in 1..Int.MAX_VALUE.toLong()) return null

        // Preflight every segment before allocating the output. Uncompressed reads
        // must fit their own declared byte range, not merely the surrounding file.
        for (t in 0 until tiles.toInt()) {
            val cols = minOf(tw.toLong(), width - (t % across) * tw).toInt()
            val rows = minOf(th.toLong(), height - (t / across) * th).toInt()
            val off = offsets[t]; val len = counts[t]
            if (off <= 0 || off >= bytes.size || len <= 0 || len > bytes.size - off) return null
            if (compression == 1) {
                val required = (rows - 1).toLong() * stride + Unpack.rowBytes(cols, bps)
                if (required > len) return null
            } else {
                val h = LosslessJpeg.readHeader(bytes, off.toInt(), len.toInt()) ?: return null
                val samples = h.rowSamples.toLong() * h.height
                val required = (rows - 1).toLong() * tw + cols
                if (samples < required || samples > tw.toLong() * th) return null
            }
        }
        val data = ShortArray(width * height)
        for (t in 0 until tiles.toInt()) {
            val tx = ((t % across) * tw).toInt()
            val ty = ((t / across) * th).toInt()
            val cols = minOf(tw, width - tx)
            val rows = minOf(th, height - ty)
            val off = offsets[t].toInt(); val len = counts[t].toInt()
            val ok = if (compression == 1) {
                if (bps == 16) Unpack.u16(bytes, off, stride.toInt(), cols, rows, raw.littleEndian, data, ty * width + tx, width)
                else Unpack.packed(bytes, off, stride.toInt(), cols, rows, bps, Unpack.BitOrder.MSB, data, ty * width + tx, width)
            } else ljpegTile(bytes, off, len, data, width, tx, ty, tw, cols, rows)
            if (!ok) return null
        }
        return data
    }

    /** Linear RGB strips produced by DngWriter, including half-float predictor 3. */
    private fun readLinear(bytes: ByteArray, raw: TiffIfd, width: Int, height: Int, bps: Int, compression: Int): ShortArray? {
        if (bps != 16 || compression !in setOf(1, 8) || raw.int(284, 1) != 1 || TiffTags.TILE_WIDTH in raw) return null
        val predictor = raw.int(317, 1)
        val format = raw.int(339, 1)
        val float = format == 3
        if (format !in setOf(1, 3) || predictor !in 1..3 || float != (predictor == 3) ||
            (compression == 1 && (float || predictor != 1))) return null
        val rowsPerStrip = (raw.longs(278, 1)?.firstOrNull() ?: height.toLong()).coerceAtMost(height.toLong()).toInt()
        if (rowsPerStrip <= 0) return null
        val strips = (height.toLong() + rowsPerStrip - 1) / rowsPerStrip
        if (strips !in 1..RawDecodeLimits.MAX_SEGMENTS.toLong()) return null
        val offsets = raw.longs(273, RawDecodeLimits.MAX_SEGMENTS) ?: return null
        val counts = raw.longs(279, RawDecodeLimits.MAX_SEGMENTS) ?: return null
        if (offsets.size != strips.toInt() || counts.size != offsets.size) return null
        val n = width * 3
        val rowBytes = n * 2
        for (i in offsets.indices) {
            val off = offsets[i]; val len = counts[i]
            if (off <= 0 || off >= bytes.size || len <= 0 || len > bytes.size - off) return null
            val rows = minOf(rowsPerStrip, height - i * rowsPerStrip)
            if (compression == 1 && len < rows.toLong() * rowBytes) return null
        }
        val data = ShortArray(width * height * 3)
        for (i in offsets.indices) {
            val rows = minOf(rowsPerStrip, height - i * rowsPerStrip)
            val at = i * rowsPerStrip * n
            if (compression == 1) {
                ByteBuffer.wrap(bytes, offsets[i].toInt(), rows * rowBytes)
                    .order(if (raw.littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
                    .asShortBuffer().get(data, at, rows * n)
                continue
            }
            val buf = ByteArray(rows * rowBytes)
            val inf = java.util.zip.Inflater()
            try {
                inf.setInput(bytes, offsets[i].toInt(), counts[i].toInt())
                var got = 0
                while (got < buf.size && !inf.finished()) {
                    val count = inf.inflate(buf, got, buf.size - got)
                    if (count == 0) return null
                    got += count
                }
                if (got != buf.size) return null
                // Consume the trailer and reject excess decompressed samples.
                if (!inf.finished() && inf.inflate(ByteArray(1)) != 0) return null
                if (!inf.finished()) return null
            } finally { inf.end() }
            if (float) {
                for (r in 0 until rows) {
                    val o = r * rowBytes
                    for (k in 3 until 2 * n) buf[o + k] = (buf[o + k] + buf[o + k - 3]).toByte()
                    for (k in 0 until n) {
                        val half = ((buf[o + k].toInt() and 255) shl 8) or (buf[o + n + k].toInt() and 255)
                        val v = app.spektralab.engine.Half.toFloat(half.toShort())
                        if (!v.isFinite()) return null
                        data[at + r * n + k] = (v.coerceIn(0f, 1f) * 65535f + 0.5f).toInt().toShort()
                    }
                }
            } else {
                ByteBuffer.wrap(buf).order(if (raw.littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
                    .asShortBuffer().get(data, at, rows * n)
                if (predictor == 2) for (r in 0 until rows) {
                    val start = at + r * n
                    for (k in 3 until n) data[start + k] = (data[start + k] + data[start + k - 3]).toShort()
                }
            }
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
        val cm1 = mat(TiffTags.COLOR_MATRIX_1); val cm2 = mat(TiffTags.COLOR_MATRIX_2)
        val t1 = illuminantKelvin(illum(TiffTags.CALIBRATION_ILLUMINANT_1))
        val t2 = illuminantKelvin(illum(TiffTags.CALIBRATION_ILLUMINANT_2))
        val shot = (ifd0.doubles(TiffTags.AS_SHOT_NEUTRAL) ?: raw.doubles(TiffTags.AS_SHOT_NEUTRAL))
            ?.takeIf { it.size == 3 && it.all { v -> v > 0 && v.isFinite() } }
        var w1 = if (cm1 != null && cm2 == null) 1.0 else 0.0
        if (cm1 != null && cm2 != null && shot != null && t1 != null && t2 != null && t1 != t2) {
            w1 = 0.5
            repeat(8) {
                val white = Mat3.invert(lerp(cm1, cm2, w1))?.let { Mat3.mulVec(it, shot) } ?: return@repeat
                val sum = white.sum()
                if (sum > 0) {
                    val kelvin = mccamy(white[0] / sum, white[1] / sum)
                    w1 = ((1.0 / kelvin - 1.0 / t2) / (1.0 / t1 - 1.0 / t2)).coerceIn(0.0, 1.0)
                }
            }
        }
        fun blend(a: DoubleArray?, b: DoubleArray?): DoubleArray? =
            if (a != null && b != null) lerp(a, b, w1) else a ?: b
        val fm = blend(mat(TiffTags.FORWARD_MATRIX_1), mat(TiffTags.FORWARD_MATRIX_2))
        val cm = blend(cm1, cm2) ?: if (fm != null) Mat3.IDENTITY else return null
        val cc = blend(mat(TiffTags.CAMERA_CALIBRATION_1), mat(TiffTags.CAMERA_CALIBRATION_2))
        val ab = (ifd0.doubles(TiffTags.ANALOG_BALANCE) ?: raw.doubles(TiffTags.ANALOG_BALANCE))?.takeIf { it.size == 3 }
        val neutral = (ifd0.doubles(TiffTags.AS_SHOT_NEUTRAL) ?: raw.doubles(TiffTags.AS_SHOT_NEUTRAL))?.takeIf { it.size == 3 && it.all { v -> v > 0 } }
            ?: (ifd0.doubles(TiffTags.AS_SHOT_WHITE_XY) ?: raw.doubles(TiffTags.AS_SHOT_WHITE_XY))?.takeIf { it.size == 2 && it[1] > 0 }?.let { xy ->
                val abcc = Mat3.mul(ab?.let { doubleArrayOf(it[0], 0.0, 0.0, 0.0, it[1], 0.0, 0.0, 0.0, it[2]) } ?: Mat3.IDENTITY, cc ?: Mat3.IDENTITY)
                Mat3.mulVec(Mat3.mul(abcc, cm), RawColor.xyToXyz(xy[0], xy[1])).takeIf { n -> n.all { it > 0 } }
            }
            ?: doubleArrayOf(1.0, 1.0, 1.0)
        return RawColor.fromDng(cm, fm, neutral, cc, ab)
    }

    private fun lerp(a: DoubleArray, b: DoubleArray, wa: Double) = DoubleArray(9) { a[it] * wa + b[it] * (1 - wa) }

    /** Correlated colour temperature from chromaticity (McCamy). */
    private fun mccamy(x: Double, y: Double): Double {
        val n = (x - 0.3320) / (0.1858 - y)
        return (449.0 * n * n * n + 3525.0 * n * n + 6823.3 * n + 5520.33).coerceIn(1500.0, 25000.0)
    }

    /** Temperature of an EXIF LightSource / DNG CalibrationIlluminant code. */
    private fun illuminantKelvin(code: Int): Double? = when (code) {
        17, 3 -> 2856.0; 24 -> 3200.0; 2, 14 -> 4150.0; 13 -> 3450.0; 12 -> 6430.0; 15 -> 6500.0
        23 -> 5003.0; 20 -> 5503.0; 1, 4, 9 -> 5500.0; 18 -> 4874.0; 19 -> 6774.0; 21 -> 6504.0; 22 -> 7504.0; 10 -> 6500.0; 11 -> 7500.0
        else -> null
    }

    private val KEEP = intArrayOf(271, 272, 306, 50708, 50721, 50722, 50723, 50724, 50727,
        50728, 50730, 50778, 50779, 50964, 50965)

    private fun keepTags(ifd0: TiffIfd, raw: TiffIfd): Map<Int, DngTag> = buildMap {
        for (tag in KEEP) {
            val source = if (tag in ifd0) ifd0 else raw
            val e = source[tag] ?: continue
            if (e.type == 2) put(tag, DngTag(2, DoubleArray(0), source.bytes.copyOfRange(e.dataOffset, e.dataOffset + minOf(e.count, 256))))
            else source.doubles(tag)?.let { put(tag, DngTag(e.type, it)) }
        }
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
                    val plane = o.int; o.int                                       // plane, planes
                    val rowPitch = o.int; val colPitch = o.int
                    val pv = o.int; val ph = o.int
                    val sv = o.double; val sh = o.double; val ov = o.double; val oh = o.double
                    val mapPlanes = o.int
                    if (pv >= 1 && ph >= 1 && mapPlanes >= 1 && pv.toLong() * ph * mapPlanes * 4 <= o.remaining()) {
                        val gains = FloatArray(pv * ph)
                        for (i in 0 until pv * ph) { gains[i] = o.float; repeat(mapPlanes - 1) { o.float } }
                        if (rowPitch > 0 && colPitch > 0 && sv > 0 && sh > 0)
                            out += GainMap(top, left, bottom, right, rowPitch, colPitch, pv, ph, sv, sh, ov, oh, gains, plane)
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
