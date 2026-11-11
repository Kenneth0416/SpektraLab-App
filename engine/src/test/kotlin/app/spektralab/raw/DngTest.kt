package app.spektralab.raw

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Builds DNG files the way Android's DngCreator lays them out (uncompressed 16-bit Bayer
 * strip, CFA / level / colour tags, GainMap opcodes) from a known linear sRGB scene and
 * checks that the reader + developer recover the scene.
 */
class DngTest {
    private val w = 96
    private val h = 64
    private val black = 64f
    private val white = 1023f

    // XYZ(D50) <-> linear sRGB
    private val srgbToXyz50 = doubleArrayOf(0.4360747, 0.3850649, 0.1430804, 0.2225045, 0.7168786, 0.0606169, 0.0139322, 0.0971045, 0.7141733)
    // a plausible phone sensor: XYZ(D65 white) -> camera
    private val colorMatrix = doubleArrayOf(0.9, -0.3, -0.1, -0.4, 1.3, 0.15, -0.05, 0.2, 0.6)
    private val d65 = doubleArrayOf(0.95047, 1.0, 1.08883)
    private val d50ToD65 = doubleArrayOf(0.9555766, -0.0230393, 0.0631636, -0.0282895, 1.0099416, 0.0210077, 0.0122982, -0.0204830, 1.3299098)

    private fun mv(m: DoubleArray, v: DoubleArray) = DoubleArray(3) { m[it * 3] * v[0] + m[it * 3 + 1] * v[1] + m[it * 3 + 2] * v[2] }

    /** Smooth scene so demosaicing is essentially exact away from the borders. */
    private fun scene(x: Int, y: Int): DoubleArray {
        val u = x.toDouble() / w; val v = y.toDouble() / h
        return doubleArrayOf(0.08 + 0.5 * u, 0.1 + 0.45 * v, 0.55 - 0.4 * u * v)
    }

    private fun gainAt(x: Int, y: Int, on: Boolean): Double {
        if (!on) return 1.0
        val dx = x.toDouble() / w - 0.5; val dy = y.toDouble() / h - 0.5
        return 1.0 + 1.2 * (dx * dx + dy * dy)
    }

    private class Tag(val id: Int, val type: Int, val count: Int, val data: ByteArray)

    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    private fun shorts(id: Int, vararg v: Int) = Tag(id, 3, v.size, le(v.size * 2).apply { v.forEach { putShort(it.toShort()) } }.array())
    private fun longs(id: Int, vararg v: Int) = Tag(id, 4, v.size, le(v.size * 4).apply { v.forEach { putInt(it) } }.array())
    private fun bytes(id: Int, vararg v: Int) = Tag(id, 1, v.size, ByteArray(v.size) { v[it].toByte() })
    private fun srationals(id: Int, v: DoubleArray) = Tag(id, 10, v.size, le(v.size * 8).apply { v.forEach { putInt(Math.round(it * 1_000_000).toInt()); putInt(1_000_000) } }.array())
    private fun rationals(id: Int, v: DoubleArray) = Tag(id, 5, v.size, le(v.size * 8).apply { v.forEach { putInt(Math.round(it * 1_000_000).toInt()); putInt(1_000_000) } }.array())

    private fun gainMapOpcodes(): ByteArray {
        val pv = 9; val ph = 13
        val out = ByteBuffer.allocate(4 + 4 * (16 + 76 + pv * ph * 4)).order(ByteOrder.BIG_ENDIAN)
        out.putInt(4)
        for (pos in 0 until 4) {
            out.putInt(9); out.putInt(0x01030000); out.putInt(0); out.putInt(76 + pv * ph * 4)
            out.putInt(pos / 2); out.putInt(pos % 2); out.putInt(h); out.putInt(w)
            out.putInt(0); out.putInt(1); out.putInt(2); out.putInt(2)
            out.putInt(pv); out.putInt(ph)
            out.putDouble(1.0 / (pv - 1)); out.putDouble(1.0 / (ph - 1)); out.putDouble(0.0); out.putDouble(0.0)
            out.putInt(1)
            for (i in 0 until pv) for (j in 0 until ph) {
                val x = (j.toDouble() / (ph - 1) * w).toInt(); val y = (i.toDouble() / (pv - 1) * h).toInt()
                out.putFloat(gainAt(x, y, true).toFloat())
            }
        }
        return out.array()
    }

    /** Scene override, exposure gain and sensor noise for the newer tests. */
    private var sceneFn: ((Int, Int) -> DoubleArray)? = null
    private var exposure = 1.0
    private var noise: Pair<Double, Double>? = null          // NoiseProfile (scale, offset)
    private var tungsten = false                              // dual-illuminant profile shot under illuminant A
    private val rnd = java.util.Random(11)

    private fun buildDng(forward: Boolean, orientation: Int, shading: Boolean, cfa: IntArray = intArrayOf(0, 1, 1, 2)): ByteArray {
        val neutral: DoubleArray
        val toCam: (DoubleArray) -> DoubleArray
        val colourTags = ArrayList<Tag>()
        if (tungsten) {
            // calibration 1 = illuminant A, calibration 2 = D65, each with its own matrices
            val fmA = doubleArrayOf(0.70, 0.18, 0.08, 0.30, 0.78, -0.08, 0.03, -0.25, 1.05)
            val fmD = doubleArrayOf(0.60, 0.25, 0.11, 0.25, 0.80, -0.05, 0.02, -0.15, 0.95)
            val cmA = doubleArrayOf(1.1, -0.45, -0.05, -0.45, 1.35, 0.1, -0.02, 0.15, 0.5)
            val a = doubleArrayOf(1.09850, 1.0, 0.35585)
            val inv = DngReader.invert(fmA)!!
            neutral = mv(cmA, a)
            toCam = { rgb -> mv(inv, mv(srgbToXyz50, rgb)).let { c -> DoubleArray(3) { c[it] * neutral[it] / neutral[1] } } }
            colourTags += srationals(50721, cmA); colourTags += srationals(50722, colorMatrix)
            colourTags += srationals(50964, fmA); colourTags += srationals(50965, fmD)
            colourTags += shorts(50778, 17); colourTags += shorts(50779, 21)
        } else if (forward) {
            // ForwardMatrix: white-balanced camera -> XYZ D50
            val fm = doubleArrayOf(0.60, 0.25, 0.11, 0.25, 0.80, -0.05, 0.02, -0.15, 0.95)
            val inv = DngReader.invert(fm)!!
            neutral = doubleArrayOf(0.52, 1.0, 0.66)
            toCam = { rgb -> mv(inv, mv(srgbToXyz50, rgb)).let { c -> DoubleArray(3) { c[it] * neutral[it] / neutral[1] } } }
            colourTags += srationals(50964, fm)
            colourTags += srationals(50721, colorMatrix)
            colourTags += shorts(50778, 21)
        } else {
            neutral = mv(colorMatrix, d65)
            // Green-normalised exposure, independent of the metadata matrix scale.
            toCam = { rgb -> mv(colorMatrix, mv(d50ToD65, mv(srgbToXyz50, rgb))).let { c -> DoubleArray(3) { c[it] / neutral[1] } } }
            colourTags += srationals(50721, colorMatrix)
            colourTags += shorts(50778, 21)
        }
        colourTags += rationals(50728, neutral)

        val px = le(w * h * 2)
        for (y in 0 until h) for (x in 0 until w) {
            val cam = toCam((sceneFn ?: ::scene)(x, y))
            val c = cfa[(y % 2) * 2 + x % 2]
            var n = (cam[c] * exposure / gainAt(x, y, shading)).coerceIn(0.0, 1.0)
            noise?.let { (s, o) -> n += rnd.nextGaussian() * Math.sqrt(s * n + o) }
            val v = n.coerceIn(0.0, 1.0) * (white - black) + black
            px.putShort(Math.round(v).coerceIn(0, 65535).toShort())
        }
        val tags = arrayListOf(
            longs(254, 0), longs(256, w), longs(257, h), shorts(258, 16), shorts(259, 1), shorts(262, 32803),
            longs(273, 0), shorts(274, orientation), shorts(277, 1), longs(278, h), longs(279, w * h * 2),
            shorts(284, 1), shorts(33421, 2, 2), bytes(33422, *cfa),
            bytes(50706, 1, 4, 0, 0), bytes(50707, 1, 1, 0, 0),
            Tag(50708, 2, 5, "Test\u0000".toByteArray()),
            shorts(50713, 2, 2), longs(50714, black.toInt(), black.toInt(), black.toInt(), black.toInt()), longs(50717, white.toInt()),
            longs(50719, 0, 0), longs(50720, w, h),
        )
        tags += colourTags
        if (shading) tags += Tag(51009, 7, gainMapOpcodes().size, gainMapOpcodes())
        noise?.let { (s, o) -> tags += Tag(51041, 12, 2, le(16).apply { putDouble(s); putDouble(o) }.array()) }
        tags.sortBy { it.id }

        // layout: header, IFD, out-of-line tag data, pixel strip
        val ifdSize = 2 + tags.size * 12 + 4
        var dataPos = 8 + ifdSize
        val offsets = HashMap<Tag, Int>()
        for (t in tags) if (t.data.size > 4) { offsets[t] = dataPos; dataPos += t.data.size + (t.data.size and 1) }
        val stripOffset = dataPos
        val out = ByteArrayOutputStream()
        out.write(le(8).apply { put('I'.code.toByte()); put('I'.code.toByte()); putShort(42); putInt(8) }.array())
        val ifd = le(ifdSize)
        ifd.putShort(tags.size.toShort())
        for (t in tags) {
            ifd.putShort(t.id.toShort()); ifd.putShort(t.type.toShort()); ifd.putInt(t.count)
            when {
                t.id == 273 -> ifd.putInt(stripOffset)
                t.data.size > 4 -> ifd.putInt(offsets[t]!!)
                else -> ifd.put(t.data.copyOf(4))
            }
        }
        ifd.putInt(0)
        out.write(ifd.array())
        for (t in tags) if (t.data.size > 4) { out.write(t.data); if (t.data.size and 1 == 1) out.write(0) }
        out.write(px.array())
        return out.toByteArray()
    }

    private fun maxError(img: LinearImage, cell: Int, orientation: Int): Double {
        var worst = 0.0
        val margin = 3
        for (y in margin until img.height - margin) for (x in margin until img.width - margin) {
            // centre of this output pixel in upright sensor coordinates
            val ux = (x + 0.5) * cell - 0.5; val uy = (y + 0.5) * cell - 0.5
            val (sx, sy) = when (orientation) {
                3 -> (w - 1 - ux) to (h - 1 - uy)
                6 -> uy to (h - 1 - ux)
                8 -> (w - 1 - uy) to ux
                else -> ux to uy
            }
            val u = sx / w; val v = sy / h
            val truth = doubleArrayOf(0.08 + 0.5 * u, 0.1 + 0.45 * v, 0.55 - 0.4 * u * v)
            worst = max(worst, abs(img.red(x, y) - truth[0]))
            worst = max(worst, abs(img.green(x, y) - truth[1]))
            worst = max(worst, abs(img.blue(x, y) - truth[2]))
        }
        return worst
    }

    @Test
    fun recoversSceneFromForwardMatrixDng() {
        val dng = assertNotNull(DngReader.read(buildDng(forward = true, orientation = 1, shading = false)))
        assertEquals(w, dng.cropWidth); assertEquals(h, dng.cropHeight)
        val full = RawDeveloper.develop(dng, 4096)
        assertEquals(w, full.width)
        val e = maxError(full, 1, 1)
        println("forward matrix, full demosaic: max error %.4f".format(e))
        assertTrue(e < 0.01)
        val half = RawDeveloper.develop(dng, 4096, fast = true)
        assertEquals(w / 2, half.width)
        val e2 = maxError(half, 2, 1)
        println("forward matrix, binned: max error %.4f".format(e2))
        assertTrue(e2 < 0.01)
    }

    @Test
    fun recoversSceneFromColorMatrixDngWithShadingAndRotation() {
        for (o in intArrayOf(1, 3, 6, 8)) {
            val dng = assertNotNull(DngReader.read(buildDng(forward = false, orientation = o, shading = true, cfa = intArrayOf(1, 0, 2, 1))))
            assertEquals(4, dng.gainMaps.size)
            val img = RawDeveloper.develop(dng, 4096)
            if (o == 6 || o == 8) { assertEquals(h, img.width); assertEquals(w, img.height) }
            val e = maxError(img, 1, o)
            println("colour matrix + gain map, orientation $o: max error %.4f".format(e))
            assertTrue(e < 0.015, "orientation $o error $e")
        }
    }

    @Test
    fun dualIlluminantProfileUsesTheTungstenCalibrationUnderTungstenLight() {
        tungsten = true
        val dng = assertNotNull(DngReader.read(buildDng(forward = true, orientation = 1, shading = false)))
        val e = maxError(RawDeveloper.develop(dng, 4096), 1, 1)
        println("dual illuminant, shot under illuminant A: max error %.4f".format(e))
        // picking the daylight matrices instead gives an error of about 0.1 here
        assertTrue(e < 0.012, "error $e")
    }

    @Test
    fun rebuildsClippedHighlights() {
        // neutral ramp, 2.2 stops over: green clips first, red and blue (lower sensitivity) keep going
        sceneFn = { x, _ -> DoubleArray(3) { 0.3 + 1.5 * x / w } }
        val bytes = buildDng(forward = true, orientation = 1, shading = false)
        val dng = assertNotNull(DngReader.read(bytes))
        val on = RawDeveloper.develop(dng, 4096)
        val off = RawDeveloper.develop(dng, 4096, options = RawDeveloper.Options(reconstructHighlights = false))
        var maxOn = 0f; var maxOff = 0f; var tint = 0f; var worst = 0.0
        val y = h / 2
        for (x in 4 until w - 4) {
            val truth = 0.3 + 1.5 * x / w
            maxOn = maxOf(maxOn, on.green(x, y)); maxOff = maxOf(maxOff, off.green(x, y))
            val r = on.red(x, y); val g = on.green(x, y); val b = on.blue(x, y)
            tint = maxOf(tint, (maxOf(r, g, b) - minOf(r, g, b)) / maxOf(g, 1e-3f))
            // exact until the last channel clips (blue here, neutral 0.66 -> scene 1 / 0.66)
            if (truth < 1.0 / 0.66 * 0.8) worst = max(worst, abs(g - truth))
        }
        println("highlights: brightest value %.2f with reconstruction vs %.2f clipped; error below the last clip %.3f; tint %.3f"
            .format(maxOn, maxOff, worst, tint))
        assertTrue(maxOff <= 1.02f)
        assertTrue(maxOn > 1.4f, "recovers more than half a stop: $maxOn")
        assertTrue(worst < 0.06, "follows the true ramp: $worst")
        assertTrue(tint < 0.08f, "highlights stay neutral: $tint")
    }

    @Test
    fun removesChromaNoiseButKeepsColourEdges() {
        // two flat colours with a hard edge, at a noise level typical of a phone at high ISO
        sceneFn = { x, _ -> if (x < w / 2) doubleArrayOf(0.20, 0.18, 0.15) else doubleArrayOf(0.10, 0.22, 0.30) }
        noise = 2.5e-3 to 2e-5
        val bytes = buildDng(forward = true, orientation = 1, shading = false)
        val dng = assertNotNull(DngReader.read(bytes))
        assertNotNull(dng.noiseScale)
        fun stats(img: LinearImage): Triple<Double, Double, Double> {
            // chroma noise on each flat side, luminance noise, and the mean colour next to the edge
            var c2 = 0.0; var l2 = 0.0; var n = 0; var edgeR = 0.0; var en = 0
            for (side in 0..1) {
                val x0 = if (side == 0) 6 else w / 2 + 8; val x1 = if (side == 0) w / 2 - 8 else w - 6
                var mr = 0.0; var mb = 0.0; var ml = 0.0; var k = 0
                for (y in 6 until h - 6) for (x in x0 until x1) {
                    val l = 0.2126 * img.red(x, y) + 0.7152 * img.green(x, y) + 0.0722 * img.blue(x, y)
                    mr += img.red(x, y) - l; mb += img.blue(x, y) - l; ml += l; k++
                }
                mr /= k; mb /= k; ml /= k
                for (y in 6 until h - 6) for (x in x0 until x1) {
                    val l = 0.2126 * img.red(x, y) + 0.7152 * img.green(x, y) + 0.0722 * img.blue(x, y)
                    c2 += (img.red(x, y) - l - mr).let { it * it } + (img.blue(x, y) - l - mb).let { it * it }
                    l2 += (l - ml) * (l - ml); n++
                }
            }
            for (y in 6 until h - 6) for (x in w / 2 - 7 until w / 2 - 3) { edgeR += img.red(x, y); en++ }
            return Triple(Math.sqrt(c2 / n / 2), Math.sqrt(l2 / n), edgeR / en)
        }
        val off = stats(RawDeveloper.develop(dng, 4096, options = RawDeveloper.Options(chromaDenoise = false)))
        val on = stats(RawDeveloper.develop(dng, 4096))
        println("chroma noise %.4f -> %.4f, luminance noise %.4f -> %.4f, red just left of the edge %.3f -> %.3f (true 0.200)"
            .format(off.first, on.first, off.second, on.second, off.third, on.third))
        assertTrue(on.first < off.first * 0.6, "chroma noise should drop by at least 40%")
        assertTrue(abs(on.second - off.second) < off.second * 0.1, "luminance is left alone")
        assertTrue(abs(on.third - 0.20) < 0.02, "no colour bleeding across the edge")
    }

    @Test
    fun rejectsWhatItCannotDevelop() {
        assertNull(DngReader.read(ByteArray(64)))
        val compressed = buildDng(true, 1, false)
        // patch Compression (tag 259) to 7 (lossless JPEG)
        val b = ByteBuffer.wrap(compressed).order(ByteOrder.LITTLE_ENDIAN)
        val n = b.getShort(8).toInt()
        for (i in 0 until n) if (b.getShort(10 + i * 12).toInt() == 259) b.putShort(10 + i * 12 + 8, 7)
        assertNull(DngReader.read(compressed))
    }

    /** Writes the synthetic files for an external cross-check with LibRaw (tools/rawcheck). */
    @Test
    fun dumpForLibRaw() {
        val dir = System.getProperty("spektra.rawdump")?.takeIf { it.isNotEmpty() } ?: return
        File(dir).mkdirs()
        val bytes = buildDng(forward = false, orientation = 1, shading = false)
        File(dir, "synthetic.dng").writeBytes(bytes)
        val img = RawDeveloper.develop(DngReader.read(bytes)!!, 4096)
        File(dir, "developed.txt").printWriter().use { p ->
            p.println("${img.width} ${img.height}")
            for (y in 0 until img.height) for (x in 0 until img.width) p.println("${img.red(x, y)} ${img.green(x, y)} ${img.blue(x, y)}")
        }
    }
}
