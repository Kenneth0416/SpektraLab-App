package app.spektralab.raw

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Synthetic handheld bursts: a continuous scene sampled by a Bayer sensor at different
 * sub-pixel offsets with sensor noise, like frames of a shaky hand.
 */
class BurstTest {
    private val w = 256
    private val h = 192
    private val black = 64.0
    private val white = 1023.0
    private val srgbToXyz50 = doubleArrayOf(0.4360747, 0.3850649, 0.1430804, 0.2225045, 0.7168786, 0.0606169, 0.0139322, 0.0971045, 0.7141733)
    private val fm = doubleArrayOf(0.60, 0.25, 0.11, 0.25, 0.80, -0.05, 0.02, -0.15, 0.95)
    private val neutral = doubleArrayOf(0.52, 1.0, 0.66)
    private val noiseProfile = 1.5e-3 to 1e-5

    private fun mv(m: DoubleArray, v: DoubleArray) = DoubleArray(3) { m[it * 3] * v[0] + m[it * 3 + 1] * v[1] + m[it * 3 + 2] * v[2] }

    /** Textured scene: soft colour fields, fine diagonal stripes, a few hard-edged blocks. */
    private fun scene(x: Double, y: Double): DoubleArray {
        val u = x / w; val v = y / h
        val stripes = 0.5 + 0.5 * sin((x * 0.9 + y * 0.6) * 0.9)
        val block = if (((x / 37).toInt() + (y / 29).toInt()) % 2 == 0) 0.12 else 0.0
        return doubleArrayOf(
            0.10 + 0.25 * u + 0.18 * stripes * (if (v > 0.5) 1.0 else 0.2) + block,
            0.12 + 0.20 * v + 0.15 * stripes * (if (u > 0.5) 1.0 else 0.2) + block * 0.5,
            0.30 - 0.15 * u * v + 0.10 * (0.5 + 0.5 * cos(x * 0.21)) )
    }

    private class Tag(val id: Int, val type: Int, val count: Int, val data: ByteArray)
    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    private fun shorts(id: Int, vararg v: Int) = Tag(id, 3, v.size, le(v.size * 2).apply { v.forEach { putShort(it.toShort()) } }.array())
    private fun longs(id: Int, vararg v: Int) = Tag(id, 4, v.size, le(v.size * 4).apply { v.forEach { putInt(it) } }.array())
    private fun bytes(id: Int, vararg v: Int) = Tag(id, 1, v.size, ByteArray(v.size) { v[it].toByte() })
    private fun rat(id: Int, type: Int, v: DoubleArray) = Tag(id, type, v.size, le(v.size * 8).apply { v.forEach { putInt(Math.round(it * 1_000_000).toInt()); putInt(1_000_000) } }.array())

    /** One frame: the scene shifted by (dx, dy) raw pixels, optionally with noise and an object pasted in. */
    /** Aperiodic texture, so block matching has one right answer everywhere. */
    private val waves = java.util.Random(5).let { r -> List(14) { doubleArrayOf(0.04 + 0.3 * r.nextDouble(), 0.04 + 0.3 * r.nextDouble(), 6.28 * r.nextDouble()) } }
    private fun texture(x: Double, y: Double): DoubleArray {
        var s = 0.0
        for (k in waves) s += sin(k[0] * x + k[1] * y + k[2])
        val v = 0.3 + 0.05 * s
        return doubleArrayOf(v, v * 0.9, v * 0.7)
    }

    private fun frame(dx: Double, dy: Double, noisy: Boolean, seed: Long, obj: Pair<Int, Int>? = null,
                      sceneFn: (Double, Double) -> DoubleArray = ::scene): DngImage {
        val inv = DngReader.invert(fm)!!
        val rnd = java.util.Random(seed)
        val px = le(w * h * 2)
        val cfa = intArrayOf(0, 1, 1, 2)
        for (y in 0 until h) for (x in 0 until w) {
            var rgb = sceneFn(x + dx, y + dy)
            if (obj != null && x in obj.first until obj.first + 24 && y in obj.second until obj.second + 24) rgb = doubleArrayOf(0.7, 0.1, 0.1)
            val cam = mv(inv, mv(srgbToXyz50, rgb))
            val c = cfa[(y % 2) * 2 + x % 2]
            var n = (cam[c] * neutral[c]).coerceIn(0.0, 1.0)
            if (noisy) n += rnd.nextGaussian() * sqrt(noiseProfile.first * n + noiseProfile.second)
            px.putShort(Math.round(n.coerceIn(0.0, 1.0) * (white - black) + black).toInt().toShort())
        }
        val tags = arrayListOf(
            longs(254, 0), longs(256, w), longs(257, h), shorts(258, 16), shorts(259, 1), shorts(262, 32803),
            longs(273, 0), shorts(274, 1), shorts(277, 1), longs(278, h), longs(279, w * h * 2), shorts(284, 1),
            shorts(33421, 2, 2), bytes(33422, *cfa), bytes(50706, 1, 4, 0, 0), bytes(50707, 1, 1, 0, 0),
            Tag(50708, 2, 5, "Test\u0000".toByteArray()), shorts(50713, 2, 2),
            longs(50714, black.toInt(), black.toInt(), black.toInt(), black.toInt()), longs(50717, white.toInt()),
            longs(50719, 0, 0), longs(50720, w, h), rat(50964, 10, fm), rat(50728, 5, neutral), shorts(50778, 21),
            Tag(51041, 12, 2, le(16).apply { putDouble(noiseProfile.first); putDouble(noiseProfile.second) }.array()),
        )
        tags.sortBy { it.id }
        val ifdSize = 2 + tags.size * 12 + 4
        var dataPos = 8 + ifdSize
        val offsets = HashMap<Tag, Int>()
        for (t in tags) if (t.data.size > 4) { offsets[t] = dataPos; dataPos += t.data.size + (t.data.size and 1) }
        val out = ByteArrayOutputStream()
        out.write(le(8).apply { put('I'.code.toByte()); put('I'.code.toByte()); putShort(42); putInt(8) }.array())
        val ifd = le(ifdSize)
        ifd.putShort(tags.size.toShort())
        for (t in tags) {
            ifd.putShort(t.id.toShort()); ifd.putShort(t.type.toShort()); ifd.putInt(t.count)
            when { t.id == 273 -> ifd.putInt(dataPos); t.data.size > 4 -> ifd.putInt(offsets[t]!!); else -> ifd.put(t.data.copyOf(4)) }
        }
        ifd.putInt(0)
        out.write(ifd.array())
        for (t in tags) if (t.data.size > 4) { out.write(t.data); if (t.data.size and 1 == 1) out.write(0) }
        out.write(px.array())
        return assertNotNull(DngReader.read(out.toByteArray()))
    }

    private val shifts = listOf(0.0 to 0.0, 1.3 to -0.6, -2.4 to 1.7, 0.5 to 2.5, -1.6 to -3.3, 3.7 to 0.4)
    private val quiet = RawDeveloper.Options(chromaDenoise = false)

    /** PSNR against the true scene inside [x0, x1) x [y0, y1). */
    private fun psnr(img: LinearImage, x0: Int = 10, y0: Int = 10, x1: Int = w - 10, y1: Int = h - 10,
                     truth: (Int, Int) -> DoubleArray = { x, y -> scene(x.toDouble(), y.toDouble()) }): Double {
        var se = 0.0; var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val t = truth(x, y)
            se += (img.red(x, y) - t[0]).let { it * it } + (img.green(x, y) - t[1]).let { it * it } + (img.blue(x, y) - t[2]).let { it * it }
            n += 3
        }
        return 10 * log10(1.0 / (se / n))
    }

    private fun develop(m: MergedRaw, src: DngImage): LinearImage {
        val bos = ByteArrayOutputStream()
        DngWriter.writeLinear(bos, m.width, m.height, m.rgb, src, m.gainMaps, m.noise)
        val dng = assertNotNull(DngReader.read(bos.toByteArray()), "merged DNG must be readable")
        assertTrue(dng.linear)
        return RawDeveloper.develop(dng, 4096, options = quiet)
    }

    @Test
    fun alignmentFindsSubPixelMotion() {
        val ref = BurstAlign.pyramid(frame(0.0, 0.0, true, 1, sceneFn = ::texture))
        for ((i, s) in shifts.drop(1).withIndex()) {
            val flow = BurstAlign.align(ref, BurstAlign.pyramid(frame(s.first, s.second, true, 10L + i, sceneFn = ::texture)))
            // interior tiles only; alt(x + flow) = ref(x) means flow = -shift
            var eu = 0.0; var ev = 0.0; var n = 0
            for (ty in 2 until flow.tilesY - 2) for (tx in 2 until flow.tilesX - 2) {
                eu += abs(flow.u[ty * flow.tilesX + tx] + s.first); ev += abs(flow.v[ty * flow.tilesX + tx] + s.second); n++
            }
            println("shift (%.1f, %.1f): mean flow error %.3f, %.3f raw px over $n tiles".format(s.first, s.second, eu / n, ev / n))
            assertTrue(eu / n < 0.15 && ev / n < 0.15, "flow error too large")
        }
    }

    @Test
    fun mergingABurstBeatsASingleFrame() {
        val frames = shifts.mapIndexed { i, s -> frame(s.first, s.second, true, 100L + i) }
        val single = psnr(RawDeveloper.develop(frames[0], 4096, options = quiet))
        val t0 = System.nanoTime()
        val merged = BurstMerge.merge(frames)
        println("merge of %d frames %dx%d: %d ms".format(frames.size, w, h, (System.nanoTime() - t0) / 1_000_000))
        val burst = psnr(develop(merged, frames[0]))
        println("noisy scene: single frame %.2f dB, %d-frame merge %.2f dB (frames used %.2f)".format(single, frames.size, burst, merged.framesUsed))
        assertTrue(burst > single + 3.0, "expected at least 3 dB from $single, got $burst")
        assertTrue(merged.framesUsed > 4f, "static scene: nearly all frames should be trusted")
    }

    @Test
    fun cleanBurstResolvesDetail() {
        val frames = shifts.mapIndexed { i, s -> frame(s.first, s.second, false, 200L + i) }
        val single = psnr(RawDeveloper.develop(frames[0], 4096, options = quiet))
        val burst = psnr(develop(BurstMerge.merge(frames), frames[0]))
        println("noise-free scene: single frame demosaic %.2f dB, merge %.2f dB".format(single, burst))
        assertTrue(burst > single - 1.0, "the merge must not lose detail against demosaicing one frame")
    }

    @Test
    fun movingObjectLeavesNoGhost() {
        // a red square at (60, 50) in the reference; it sits somewhere else in every other frame
        val spots = listOf(60 to 50, 150 to 50, 150 to 120, 60 to 120, 110 to 85, 190 to 30)
        val frames = shifts.mapIndexed { i, s -> frame(s.first, s.second, true, 300L + i, spots[i]) }
        val merged = develop(BurstMerge.merge(frames), frames[0])
        val single = RawDeveloper.develop(frames[0], 4096, options = quiet)
        val truthRef: (Int, Int) -> DoubleArray = { x, y ->
            if (x in 60 until 84 && y in 50 until 74) doubleArrayOf(0.7, 0.1, 0.1) else scene(x.toDouble(), y.toDouble())
        }
        // where the object is in the reference, and where it is in another frame
        val atObject = psnr(merged, 56, 46, 88, 78, truthRef) to psnr(single, 56, 46, 88, 78, truthRef)
        val atGhost = psnr(merged, 146, 46, 178, 78, truthRef) to psnr(single, 146, 46, 178, 78, truthRef)
        println("moving object: at the object merge %.2f dB vs single %.2f dB; where it was in another frame merge %.2f dB vs single %.2f dB"
            .format(atObject.first, atObject.second, atGhost.first, atGhost.second))
        assertTrue(atObject.first > atObject.second - 1.0, "object must stay as in the reference")
        assertTrue(atGhost.first > atGhost.second - 1.0, "no ghost of the object from other frames")
    }

    /** Writes a merged DNG for an external validity check with LibRaw. */
    @Test
    fun dumpForLibRaw() {
        val dir = System.getProperty("spektra.rawdump")?.takeIf { it.isNotEmpty() } ?: return
        val frames = shifts.take(3).mapIndexed { i, s -> frame(s.first, s.second, true, 400L + i) }
        val m = BurstMerge.merge(frames)
        File(dir, "merged.dng").outputStream().use { DngWriter.writeLinear(it, m.width, m.height, m.rgb, frames[0], m.gainMaps, m.noise) }
        assertEquals(w, m.width)
        // the pixel data as written (camera RGB, 0..1): the container is what LibRaw has to agree on
        File(dir, "merged_camera.txt").printWriter().use { p ->
            p.println("${m.width} ${m.height}")
            for (i in 0 until m.width * m.height) p.println("${(m.rgb[i * 3].toInt() and 0xffff) / 65535f} ${(m.rgb[i * 3 + 1].toInt() and 0xffff) / 65535f} ${(m.rgb[i * 3 + 2].toInt() and 0xffff) / 65535f}")
        }
    }
}
