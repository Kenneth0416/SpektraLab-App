package app.spektralab.raw

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Phone-like raw files for load-path tests and benchmarks: a 12 MP Bayer DNG laid out
 * like Android DngCreator output (16-bit uncompressed, one strip, 10-bit sensor levels,
 * per-CFA-position lens shading maps, noise profile) and the merged Linear DNG the
 * camera writes from a burst of such frames ([DngWriter.writeLinear]).
 */
object RawFixtures {
    const val PHONE_W = 4064
    const val PHONE_H = 3056
    private const val BLACK = 64
    private const val WHITE = 1023

    private val fm = doubleArrayOf(0.60, 0.25, 0.11, 0.25, 0.80, -0.05, 0.02, -0.15, 0.95)
    private val cm = doubleArrayOf(1.10, -0.35, -0.10, -0.40, 1.30, 0.10, -0.05, 0.20, 0.60)
    private val neutral = doubleArrayOf(0.52, 1.0, 0.66)

    /** Smooth colour fields with fine detail, 0..1 camera RGB. */
    fun scene(x: Int, y: Int, w: Int, h: Int, c: Int): Double {
        val u = x.toDouble() / w; val v = y.toDouble() / h
        val stripes = 0.5 + 0.5 * kotlin.math.sin((x * 0.9 + y * 0.6) * 0.07)
        val block = if (((x / 211) + (y / 157)) % 2 == 0) 0.10 else 0.0
        return when (c) {
            0 -> 0.08 + 0.30 * u + 0.15 * stripes + block
            1 -> 0.10 + 0.25 * v + 0.12 * stripes + block * 0.5
            else -> 0.25 - 0.12 * u * v + 0.08 * stripes
        }.coerceIn(0.0, 1.0)
    }

    /** OpcodeList2 with [maps] (big endian). */
    fun gainMapOpcodes(maps: List<GainMap>): ByteArray {
        val o = ByteBuffer.allocate(4 + maps.sumOf { 16 + 76 + it.gains.size * 4 }).order(ByteOrder.BIG_ENDIAN)
        o.putInt(maps.size)
        for (m in maps) {
            o.putInt(9); o.putInt(0x01030000); o.putInt(0); o.putInt(76 + m.gains.size * 4)
            o.putInt(m.top); o.putInt(m.left); o.putInt(m.bottom); o.putInt(m.right)
            o.putInt(m.plane); o.putInt(1); o.putInt(m.rowPitch); o.putInt(m.colPitch)
            o.putInt(m.pointsV); o.putInt(m.pointsH)
            o.putDouble(m.spacingV); o.putDouble(m.spacingH); o.putDouble(m.originV); o.putDouble(m.originH)
            o.putInt(1)
            for (g in m.gains) o.putFloat(g)
        }
        return o.array()
    }

    /** Lens shading: brighter towards the corners, as phone maps are. */
    private fun shading(pv: Int, ph: Int, c: Int) = FloatArray(pv * ph) { i ->
        val dy = (i / ph).toFloat() / (pv - 1) - 0.5f; val dx = (i % ph).toFloat() / (ph - 1) - 0.5f
        1f + (1.6f + 0.1f * c) * (dx * dx + dy * dy)
    }

    private fun bayerIfd(w: Int, h: Int, strips: List<ByteArray>, rowsPerStrip: Int, privateData: ByteArray? = null,
                         make: String = "TestMaker", model: String = "Phone 1"): TiffBuilder.Ifd {
        val maps = (0 until 4).map { pos ->
            GainMap(pos / 2, pos % 2, h, w, 2, 2, 13, 17, 1.0 / 12, 1.0 / 16, 0.0, 0.0, shading(13, 17, pos), 0)
        }
        return TiffBuilder.Ifd().long(254, 0).long(256, w).long(257, h).short(258, 16).short(259, 1)
            .short(262, 32803).short(274, 6).short(277, 1).long(278, rowsPerStrip).short(284, 1)
            .short(33421, 2, 2).byte(33422, 0, 1, 1, 2).byte(50706, 1, 4, 0, 0).byte(50707, 1, 3, 0, 0)
            .ascii(271, make).ascii(272, model).ascii(50708, "$make $model")
            .short(50713, 2, 2).long(50714, BLACK, BLACK, BLACK, BLACK).long(50717, WHITE)
            .long(50719, 0, 0).long(50720, w, h)
            .srational(50721, *cm).srational(50722, *cm).short(50778, 17).short(50779, 21)
            .srational(50964, *fm).srational(50965, *fm).rational(50728, *neutral)
            .double(51041, 1.5e-4, 2e-6, 1.5e-4, 2e-6, 1.5e-4, 2e-6, 1.5e-4, 2e-6)
            .undefined(51009, gainMapOpcodes(maps))
            .blobs(273, 279, strips)
            .also { ifd -> privateData?.let { ifd.undefined(50740, it) } }
    }

    /** A DngCreator-style 12 MP Bayer DNG (24.8 MB); [privateData] adds a DNGPrivateData tag. */
    fun bayerDng(w: Int = PHONE_W, h: Int = PHONE_H, seed: Long = 1, privateData: ByteArray? = null,
                 make: String = "TestMaker", model: String = "Phone 1"): ByteArray {
        val rnd = java.util.Random(seed)
        val px = ByteBuffer.allocate(w * h * 2).order(ByteOrder.LITTLE_ENDIAN)
        val cfa = intArrayOf(0, 1, 1, 2)
        for (y in 0 until h) for (x in 0 until w) {
            val c = cfa[(y % 2) * 2 + x % 2]
            val n = scene(x, y, w, h, c) * neutral[c]
            val v = n + rnd.nextGaussian() * kotlin.math.sqrt(1.5e-4 * n + 2e-6)
            px.putShort(Math.round(v.coerceIn(0.0, 1.0) * (WHITE - BLACK) + BLACK).toInt().toShort())
        }
        return TiffBuilder().build(bayerIfd(w, h, listOf(px.array()), h, privateData, make, model))
    }

    /** The frame a merged DNG takes its metadata from (tiny pixel data, real tags). */
    fun sourceFrame(): RawImage {
        val w = 8; val h = 8
        return requireNotNull(DngReader.read(TiffBuilder().build(bayerIfd(w, h, listOf(ByteArray(w * h * 2) { 70 }), h))))
    }

    /** Merged camera RGB, as BurstMerge produces it: 0..65535, with merged-burst noise. */
    fun mergedRgb(w: Int = PHONE_W, h: Int = PHONE_H, seed: Long = 2, noise: Double = 0.012): ShortArray {
        val rnd = java.util.Random(seed)
        val rgb = ShortArray(w * h * 3)
        // noise per row is expensive with nextGaussian: reuse a table of normals
        val normals = DoubleArray(1 shl 16) { rnd.nextGaussian() }
        var k = 0
        for (y in 0 until h) for (x in 0 until w) for (c in 0..2) {
            val n = scene(x, y, w, h, c) * neutral[c]
            val v = n + normals[(k++ * 40503 + y) and 0xffff] * noise * kotlin.math.sqrt(n + 0.01)
            rgb[(y * w + x) * 3 + c] = Math.round(v.coerceIn(0.0, 1.0) * 65535).toInt().toShort()
        }
        return rgb
    }

    fun mergedGainMaps(w: Int = PHONE_W, h: Int = PHONE_H) = (0..2).map { c ->
        GainMap(0, 0, h, w, 1, 1, 13, 17, 1.0 / 12, 1.0 / 16, 0.0, 0.0, shading(13, 17, c), c)
    }

    /** A merged-burst Linear DNG exactly as the camera writes it. */
    fun writeMerged(out: OutputStream, w: Int = PHONE_W, h: Int = PHONE_H, rgb: ShortArray = mergedRgb(w, h)) =
        DngWriter.writeLinear(out, w, h, rgb, sourceFrame(), mergedGainMaps(w, h), 1.5e-4 / 4 to 2e-6 / 4)
}
