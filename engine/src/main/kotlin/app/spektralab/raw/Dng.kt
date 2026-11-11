package app.spektralab.raw

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Lens shading gain map (DNG opcode 9), one per CFA position on phone cameras. */
class GainMap(
    val top: Int, val left: Int, val bottom: Int, val right: Int,
    val rowPitch: Int, val colPitch: Int,
    val pointsV: Int, val pointsH: Int,
    val spacingV: Double, val spacingH: Double, val originV: Double, val originH: Double,
    val gains: FloatArray,
) {
    /** Gain at raw pixel (x, y) of an image [width] x [height] (bilinear, clamped). */
    fun gain(x: Int, y: Int, width: Int, height: Int): Float {
        val v = ((y.toDouble() / height - originV) / spacingV).coerceIn(0.0, (pointsV - 1).toDouble())
        val h = ((x.toDouble() / width - originH) / spacingH).coerceIn(0.0, (pointsH - 1).toDouble())
        val v0 = v.toInt().coerceAtMost(pointsV - 2).coerceAtLeast(0)
        val h0 = h.toInt().coerceAtMost(pointsH - 2).coerceAtLeast(0)
        val v1 = minOf(v0 + 1, pointsV - 1)
        val h1 = minOf(h0 + 1, pointsH - 1)
        val fv = (v - v0).toFloat()
        val fh = (h - h0).toFloat()
        val a = gains[v0 * pointsH + h0] * (1 - fh) + gains[v0 * pointsH + h1] * fh
        val b = gains[v1 * pointsH + h0] * (1 - fh) + gains[v1 * pointsH + h1] * fh
        return a * (1 - fv) + b * fv
    }

    fun covers(x: Int, y: Int) = y >= top && y < bottom && x >= left && x < right &&
        (y - top) % rowPitch == 0 && (x - left) % colPitch == 0
}

/** A Bayer raw frame with the metadata needed to develop it to linear sRGB. */
class DngImage(
    val rawWidth: Int,
    val rawHeight: Int,
    /** Sensor values, row-major, unsigned 16 bit. */
    val data: ShortArray,
    /** Colour (0 R, 1 G, 2 B) at CFA position [row % 2][col % 2]. */
    val cfa: IntArray,
    /** Black level per CFA position. */
    val black: FloatArray,
    val white: Float,
    /** Region to output (DefaultCrop inside ActiveArea), raw pixel coordinates. */
    val cropX: Int, val cropY: Int, val cropWidth: Int, val cropHeight: Int,
    /** White balance gains applied to black-subtracted, normalised camera RGB. */
    val wb: FloatArray,
    /** White-balanced camera RGB -> linear sRGB (D65), row-major 3x3. */
    val camToSrgb: FloatArray,
    /** TIFF orientation (1, 3, 6, 8 supported). */
    val orientation: Int,
    val baselineExposure: Float,
    val gainMaps: List<GainMap>,
    /**
     * Sensor noise model (DNG NoiseProfile): variance = scale * x + offset for a
     * black-subtracted sample x normalised to [0, 1]. Null when the file has none.
     */
    val noiseScale: Float? = null,
    val noiseOffset: Float = 0f,
) {
    /** Size after applying [orientation]. */
    val uprightWidth get() = if (orientation == 6 || orientation == 8) cropHeight else cropWidth
    val uprightHeight get() = if (orientation == 6 || orientation == 8) cropWidth else cropHeight
}

/**
 * Minimal DNG reader for uncompressed Bayer DNGs, which is what Android's DngCreator
 * (Camera2 RAW_SENSOR, CameraX RAW capture) writes. Returns null for anything else
 * (compressed, tiled, non-Bayer, linear raw) so callers can fall back to the system decoder.
 */
object DngReader {
    private class Entry(val type: Int, val count: Int, val valueOffset: Int)
    private class Ifd(val entries: Map<Int, Entry>)

    fun read(bytes: ByteArray): DngImage? = runCatching { parse(ByteBuffer.wrap(bytes)) }.getOrNull()

    private fun parse(b: ByteBuffer): DngImage? {
        if (b.capacity() < 16) return null
        val order = when (b.getShort(0).toInt()) { 0x4949 -> ByteOrder.LITTLE_ENDIAN; 0x4D4D -> ByteOrder.BIG_ENDIAN; else -> return null }
        b.order(order)
        if (b.getShort(2).toInt() != 42) return null
        val ifd0 = readIfd(b, b.getInt(4)) ?: return null
        // the raw image is IFD0 itself or one of its SubIFDs (when a preview comes first)
        val candidates = ArrayList<Ifd>().apply { add(ifd0) }
        ifd0.entries[330]?.let { e -> values(b, e).forEach { off -> readIfd(b, off.toInt())?.let { candidates += it } } }
        val raw = candidates.firstOrNull { ints(b, it, 262)?.firstOrNull() == 32803 } ?: return null
        fun rawInts(tag: Int) = ints(b, raw, tag)
        fun anyD(tag: Int) = doubles(b, raw, tag) ?: doubles(b, ifd0, tag)

        val width = rawInts(256)?.first() ?: return null
        val height = rawInts(257)?.first() ?: return null
        if ((rawInts(259)?.first() ?: 1) != 1) return null                    // uncompressed only
        if ((rawInts(258)?.first() ?: 0) != 16) return null
        if ((rawInts(277)?.first() ?: 1) != 1) return null
        val dim = rawInts(33421) ?: return null
        if (dim.size != 2 || dim[0] != 2 || dim[1] != 2) return null
        val cfa = rawInts(33422)?.takeIf { it.size == 4 && it.all { c -> c in 0..2 } } ?: return null
        val offsets = rawInts(273) ?: return null                              // strips (no tiles)
        val counts = rawInts(279) ?: return null

        val data = ShortArray(width * height)
        var pos = 0
        for (i in offsets.indices) {
            val n = minOf(counts[i] / 2, data.size - pos)
            val sb = b.duplicate().order(order)
            sb.position(offsets[i]); sb.limit(offsets[i] + n * 2)
            sb.asShortBuffer().get(data, pos, n)
            pos += n
        }
        if (pos < data.size) return null

        // levels
        val blackRep = rawInts(50713) ?: intArrayOf(1, 1)
        val blackVals = anyD(50714) ?: doubleArrayOf(0.0)
        val black = FloatArray(4) { i ->
            val r = i / 2; val c = i % 2
            blackVals[((r % blackRep[0]) * blackRep[1] + (c % blackRep[1])).coerceAtMost(blackVals.size - 1)].toFloat()
        }
        val white = (anyD(50717)?.first() ?: 65535.0).toFloat()

        // crop: DefaultCropOrigin/Size are relative to the ActiveArea
        val active = rawInts(50829) ?: intArrayOf(0, 0, height, width)         // top, left, bottom, right
        val origin = anyD(50719) ?: doubleArrayOf(0.0, 0.0)                    // h, v
        val size = anyD(50720) ?: doubleArrayOf((active[3] - active[1]).toDouble(), (active[2] - active[0]).toDouble())
        var cropX = active[1] + origin[0].toInt()
        var cropY = active[0] + origin[1].toInt()
        // keep the CFA phase: start on an even raw coordinate
        cropX += cropX % 2; cropY += cropY % 2
        var cropW = minOf(size[0].toInt(), width - cropX)
        var cropH = minOf(size[1].toInt(), height - cropY)
        cropW -= cropW % 2; cropH -= cropH % 2
        if (cropW < 4 || cropH < 4) return null

        // colour
        val neutral = anyD(50728)?.takeIf { it.size == 3 && it.all { v -> v > 0 } } ?: doubleArrayOf(1.0, 1.0, 1.0)
        val wb = FloatArray(3) { (neutral[1] / neutral[it]).toFloat() }
        val camToXyz50 = cameraToXyzD50(b, raw, ifd0, neutral) ?: return null
        // wb is normalised to green (camera * wb = neutral_G * camera / neutral), so fold 1 / neutral_G in
        val camToSrgb = mul(XYZ_D50_TO_SRGB, camToXyz50).let { m -> DoubleArray(9) { m[it] / neutral[1] } }
        val orientation = ints(b, ifd0, 274)?.first() ?: rawInts(274)?.first() ?: 1
        val baseline = (doubles(b, ifd0, 50730) ?: anyD(50730))?.first()?.toFloat() ?: 0f
        val gainMaps = raw.entries[51009]?.let { parseGainMaps(b, it) } ?: emptyList()

        // NoiseProfile: (scale, offset) per colour plane; one pair may stand for all
        val noise = (doubles(b, ifd0, 51041) ?: anyD(51041))?.takeIf { it.size >= 2 && it.size % 2 == 0 }
        val noiseScale = noise?.let { n -> (n.indices step 2).map { n[it] }.average().toFloat() }?.takeIf { it > 0f }
        val noiseOffset = noise?.let { n -> (1 until n.size step 2).map { n[it] }.average().toFloat() }?.coerceAtLeast(0f) ?: 0f

        return DngImage(width, height, data, cfa, black, white, cropX, cropY, cropW, cropH,
            wb, FloatArray(9) { camToSrgb[it].toFloat() }, orientation, baseline, gainMaps, noiseScale, noiseOffset)
    }

    /**
     * Matrix M with XYZ(D50) = M * (camera / neutral): the DNG ForwardMatrix when present,
     * otherwise derived from the ColorMatrix with a Bradford adaptation of the as-shot white.
     */
    private fun cameraToXyzD50(b: ByteBuffer, raw: Ifd, ifd0: Ifd, neutral: DoubleArray): DoubleArray? {
        fun mat(tag: Int) = (doubles(b, ifd0, tag) ?: doubles(b, raw, tag))?.takeIf { it.size == 9 }
        fun illum(tag: Int) = (ints(b, ifd0, tag) ?: ints(b, raw, tag))?.first() ?: 0
        val cm1 = mat(50721); val cm2 = mat(50722)
        val fm1 = mat(50964); val fm2 = mat(50965)
        val t1 = illuminantKelvin(illum(50778)); val t2 = illuminantKelvin(illum(50779))
        // Dual-illuminant profiles: weight the two calibrations by the colour temperature
        // of the as-shot white (DNG spec, "Camera to XYZ (D50) transform"), found by
        // iterating white -> temperature -> interpolated ColorMatrix -> white.
        var w1 = if (cm1 != null && cm2 == null) 1.0 else 0.0
        if (cm1 != null && cm2 != null && t1 != null && t2 != null && t1 != t2) {
            w1 = 0.5
            repeat(8) {
                val cm = lerp(cm1, cm2, w1)
                val white = invert(cm)?.let { mulVec(it, neutral) } ?: return@repeat
                val sum = white[0] + white[1] + white[2]
                if (sum <= 0) return@repeat
                val kelvin = mccamy(white[0] / sum, white[1] / sum)
                w1 = ((1.0 / kelvin - 1.0 / t2) / (1.0 / t1 - 1.0 / t2)).coerceIn(0.0, 1.0)
            }
        }
        val fm = when {
            fm1 != null && fm2 != null -> lerp(fm1, fm2, w1)
            else -> fm2 ?: fm1
        }
        if (fm != null) return fm
        val cm = when {
            cm1 != null && cm2 != null -> lerp(cm1, cm2, w1)
            else -> cm2 ?: cm1 ?: return null
        }
        val inv = invert(cm) ?: return null
        val whiteXyz = mulVec(inv, neutral)
        val adapt = bradford(whiteXyz, D50)
        // XYZ50 = adapt * inv(CM) * camera = adapt * inv(CM) * diag(neutral) * (camera / neutral)
        val m = mul(adapt, inv)
        return DoubleArray(9) { m[it] * neutral[it % 3] }
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

    private fun parseGainMaps(b: ByteBuffer, e: Entry): List<GainMap> {
        // opcode lists are always big endian
        val o = b.duplicate().order(ByteOrder.BIG_ENDIAN)
        o.position(e.valueOffset)
        val out = ArrayList<GainMap>()
        val n = o.int
        repeat(n) {
            val id = o.int; o.int; o.int
            val size = o.int
            val next = o.position() + size
            if (id == 9) {
                val top = o.int; val left = o.int; val bottom = o.int; val right = o.int
                o.int; o.int                                                   // plane, planes
                val rowPitch = o.int; val colPitch = o.int
                val pv = o.int; val ph = o.int
                val sv = o.double; val sh = o.double; val ov = o.double; val oh = o.double
                val mapPlanes = o.int
                val gains = FloatArray(pv * ph)
                for (i in 0 until pv * ph) { gains[i] = o.float; repeat(mapPlanes - 1) { o.float } }
                if (pv >= 1 && ph >= 1 && rowPitch > 0 && colPitch > 0)
                    out += GainMap(top, left, bottom, right, rowPitch, colPitch, pv, ph, sv, sh, ov, oh, gains)
            }
            o.position(next)
        }
        return out
    }

    // ---------------------------------------------------------------- TIFF plumbing
    private fun readIfd(b: ByteBuffer, offset: Int): Ifd? {
        if (offset <= 0 || offset + 2 > b.capacity()) return null
        val n = b.getShort(offset).toInt() and 0xffff
        val map = HashMap<Int, Entry>()
        for (i in 0 until n) {
            val p = offset + 2 + i * 12
            if (p + 12 > b.capacity()) return null
            val tag = b.getShort(p).toInt() and 0xffff
            val type = b.getShort(p + 2).toInt() and 0xffff
            val count = b.getInt(p + 4)
            val bytes = count.toLong() * typeSize(type)
            map[tag] = Entry(type, count, if (bytes <= 4) p + 8 else b.getInt(p + 8))
        }
        return Ifd(map)
    }

    private fun typeSize(type: Int) = when (type) { 1, 2, 6, 7 -> 1; 3, 8 -> 2; 4, 9, 11 -> 4; 5, 10, 12 -> 8; else -> 1 }

    private fun values(b: ByteBuffer, e: Entry): DoubleArray {
        val n = e.count.coerceAtMost(4096)
        return DoubleArray(n) { i ->
            val p = e.valueOffset + i * typeSize(e.type)
            when (e.type) {
                1, 7 -> (b.get(p).toInt() and 0xff).toDouble()
                6 -> b.get(p).toDouble()
                3 -> (b.getShort(p).toInt() and 0xffff).toDouble()
                8 -> b.getShort(p).toDouble()
                4 -> (b.getInt(p).toLong() and 0xffffffffL).toDouble()
                9 -> b.getInt(p).toDouble()
                5 -> (b.getInt(p).toLong() and 0xffffffffL).toDouble() / (b.getInt(p + 4).toLong() and 0xffffffffL).coerceAtLeast(1)
                10 -> b.getInt(p).toDouble() / b.getInt(p + 4).let { if (it == 0) 1 else it }
                11 -> b.getFloat(p).toDouble()
                12 -> b.getDouble(p)
                else -> 0.0
            }
        }
    }

    private fun doubles(b: ByteBuffer, ifd: Ifd, tag: Int): DoubleArray? = ifd.entries[tag]?.let { values(b, it) }?.takeIf { it.isNotEmpty() }
    private fun ints(b: ByteBuffer, ifd: Ifd, tag: Int): IntArray? = doubles(b, ifd, tag)?.let { d -> IntArray(d.size) { d[it].toInt() } }

    // ---------------------------------------------------------------- colour math
    private val D50 = doubleArrayOf(0.9642, 1.0, 0.8251)
    /** XYZ (D50) -> linear sRGB (D65), Bradford adapted. */
    private val XYZ_D50_TO_SRGB = doubleArrayOf(
        3.1338561, -1.6168667, -0.4906146,
        -0.9787684, 1.9161415, 0.0334540,
        0.0719453, -0.2289914, 1.4052427)
    private val BRADFORD = doubleArrayOf(0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296)

    internal fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
        val r = i / 3; val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    private fun mulVec(m: DoubleArray, v: DoubleArray) = DoubleArray(3) { m[it * 3] * v[0] + m[it * 3 + 1] * v[1] + m[it * 3 + 2] * v[2] }

    internal fun invert(m: DoubleArray): DoubleArray? {
        val a = m[0]; val b = m[1]; val c = m[2]; val d = m[3]; val e = m[4]; val f = m[5]; val g = m[6]; val h = m[7]; val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (kotlin.math.abs(det) < 1e-12) return null
        return doubleArrayOf(
            (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det)
    }

    private fun bradford(from: DoubleArray, to: DoubleArray): DoubleArray {
        val s = mulVec(BRADFORD, from)
        val t = mulVec(BRADFORD, to)
        val scale = doubleArrayOf(t[0] / s[0], 0.0, 0.0, 0.0, t[1] / s[1], 0.0, 0.0, 0.0, t[2] / s[2])
        return mul(invert(BRADFORD)!!, mul(scale, BRADFORD))
    }
}
