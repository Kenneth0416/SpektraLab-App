package app.spektralab.raw

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** X-Trans development (XTrans.kt through RawDeveloper) on synthetic scenes with known truth. */
class XTransTest {
    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val xtrans = CfaPattern(6, 6, RafBuilder.XT2)
    private val opts = RawDeveloper.Options(reconstructHighlights = false, chromaDenoise = false)

    /** Camera RGB (already white balanced, 0..1) of a test scene: gradients, a hard-edged disc, bars, a zone plate corner. */
    private fun scene(x: Int, y: Int, w: Int, h: Int): DoubleArray {
        val u = x.toDouble() / w; val v = y.toDouble() / h
        var r = 0.15 + 0.5 * u; var g = 0.2 + 0.4 * v; var b = 0.5 - 0.35 * u * v
        val dx = x - w * 0.3; val dy = y - h * 0.5
        if (dx * dx + dy * dy < (h * 0.25) * (h * 0.25)) { r = 0.75; g = 0.25; b = 0.1 }       // red disc
        if (x > w * 0.6 && x < w * 0.9 && (y / 6) % 2 == 0) { r *= 0.3; g *= 0.3; b *= 0.3 }   // horizontal bars, period 12
        if (x > w * 0.6 && y > h * 0.75) {                                                    // gentle zone plate
            val f = 0.5 + 0.5 * sin(PI * ((x - w * 0.6) * (x - w * 0.6) + (y - h * 0.75) * (y - h * 0.75)) / 900.0)
            r = 0.1 + 0.6 * f; g = 0.1 + 0.6 * f; b = 0.1 + 0.6 * f
        }
        return doubleArrayOf(r, g, b)
    }

    private fun mosaic(w: Int, h: Int, cfa: CfaPattern, white: Int, black: Int): ShortArray =
        ShortArray(w * h) { i ->
            val x = i % w; val y = i / w
            (black + scene(x, y, w, h)[cfa.color(y, x)] * (white - black)).toInt().coerceIn(0, 65535).toShort()
        }

    private fun image(w: Int, h: Int, cfa: CfaPattern = xtrans, crop: IntArray = intArrayOf(0, 0, w, h), orientation: Int = 1): RawImage {
        val white = 16383; val black = 1000
        return RawImage(w, h, mosaic(w, h, cfa, white, black), cfa, FloatArray(cfa.size) { black.toFloat() }, white.toFloat(),
            crop[0], crop[1], crop[2], crop[3], floatArrayOf(1f, 1f, 1f), identity, orientation)
    }

    /** PSNR (peak 1) of developed vs truth over the pixels at least [border] from the edge. */
    private fun psnr(out: LinearImage, w: Int, h: Int, border: Int, ox: Int = 0, oy: Int = 0): Double {
        var se = 0.0; var n = 0
        for (y in border until out.height - border) for (x in border until out.width - border) {
            val t = scene(x + ox, y + oy, w, h)
            val d = doubleArrayOf(out.red(x, y) - t[0], out.green(x, y) - t[1], out.blue(x, y) - t[2])
            se += d[0] * d[0] + d[1] * d[1] + d[2] * d[2]; n += 3
        }
        return 10 * log10(1.0 / (se / n))
    }

    @Test
    fun supportsXTransButNotOddPatterns() {
        assertTrue(XTrans.supports(xtrans))
        assertFalse(XTrans.supports(CfaPattern.RGGB))                                   // Bayer goes the Bayer way
        assertFalse(XTrans.supports(CfaPattern(2, 2, intArrayOf(0, 0, 0, 0))))          // monochrome-ish
        assertFalse(XTrans.supports(CfaPattern.parse(6, 6, "RRRRRR" + "GGGGGG".repeat(4) + "BBBBBB")))
        assertTrue(RawDeveloper.supports(image(36, 36)))
    }

    @Test
    fun flatFieldIsExact() {
        val w = 60; val h = 48
        val c = doubleArrayOf(0.3, 0.5, 0.2)
        val data = ShortArray(w * h) { i -> (1000 + c[xtrans.color(i / w, i % w)] * 15383).toInt().toShort() }
        val img = RawImage(w, h, data, xtrans, FloatArray(36) { 1000f }, 16383f, 0, 0, w, h, floatArrayOf(1f, 1f, 1f), identity)
        for (out in listOf(RawDeveloper.develop(img, 4096, options = opts), RawDeveloper.develop(img, 4096, fast = true, options = opts))) {
            for (y in 0 until out.height) for (x in 0 until out.width) {
                assertEquals(0.3f, out.red(x, y), 2e-3f); assertEquals(0.5f, out.green(x, y), 2e-3f); assertEquals(0.2f, out.blue(x, y), 2e-3f)
            }
        }
    }

    @Test
    fun fullResolutionQuality() {
        val w = 768; val h = 528          // LibRaw's 3-pass X-Trans needs 512 x 512 for the comparison dump
        val img = image(w, h)
        val out = RawDeveloper.develop(img, 4096, options = opts)
        assertEquals(w, out.width); assertEquals(h, out.height)
        val p = psnr(out, w, h, 3)
        println("X-Trans full-resolution PSNR vs truth: ${"%.2f".format(p)} dB")
        assertTrue(p > 30.0, "PSNR $p")
        dump(img, out, "xtrans_scene")
    }

    @Test
    fun binnedPreviewMatchesBoxFilteredScene() {
        val w = 360; val h = 240
        val out = RawDeveloper.develop(image(w, h), 120, options = opts)
        assertEquals(120, out.width); assertEquals(80, out.height)
        // compare with the scene averaged over the same 3x3 cells, away from the hard edges
        var se = 0.0; var n = 0
        for (v in 0 until out.height) for (u in 0 until out.width) {
            val t = DoubleArray(3)
            for (dy in 0 until 3) for (dx in 0 until 3) { val s = scene(u * 3 + dx, v * 3 + dy, w, h); for (c in 0..2) t[c] += s[c] / 9 }
            val d = doubleArrayOf(out.red(u, v) - t[0], out.green(u, v) - t[1], out.blue(u, v) - t[2])
            se += d.sumOf { it * it }; n += 3
        }
        val p = 10 * log10(1.0 / (se / n))
        println("X-Trans binned PSNR vs box-filtered truth: ${"%.2f".format(p)} dB")
        assertTrue(p > 25.0, "PSNR $p")
        // fast = true bins even when full resolution was asked for
        assertEquals(120, RawDeveloper.develop(image(w, h), 4096, fast = true, options = opts).width)
    }

    @Test
    fun cropPhaseAndOrientation() {
        // a crop at an odd phase of the 6x6 tile, rotated: the result must still match the scene
        val w = 200; val h = 150
        val crop = intArrayOf(7, 5, 181, 133)
        val up = RawDeveloper.develop(image(w, h, crop = crop), 4096, options = opts)
        val rot = RawDeveloper.develop(image(w, h, crop = crop, orientation = 6), 4096, options = opts)
        assertEquals(181, up.width); assertEquals(133, up.height)
        assertEquals(133, rot.width); assertEquals(181, rot.height)
        assertTrue(psnr(up, w, h, 3, crop[0], crop[1]) > 30.0)
        // orientation 6 (90 CW): upright (x, y) comes from crop (y, h - 1 - x)
        for (y in 0 until rot.height step 7) for (x in 0 until rot.width step 7)
            assertEquals(up.green(y, up.height - 1 - x), rot.green(x, y), 0f)
    }

    @Test
    fun highlightsStayNeutralAndBounded() {
        val w = 72; val h = 72
        val data = ShortArray(w * h) { 16383 }
        val img = RawImage(w, h, data, xtrans, FloatArray(36) { 1000f }, 16383f, 0, 0, w, h, floatArrayOf(2f, 1f, 1.5f), identity)
        val out = RawDeveloper.develop(img, 4096)
        for (y in 0 until h) for (x in 0 until w) {
            assertEquals(out.red(x, y), out.green(x, y), 1e-2f); assertEquals(out.blue(x, y), out.green(x, y), 1e-2f)
            assertTrue(out.green(x, y) <= 2.01f)
        }
    }

    @Test
    fun noiseDoesNotBlowUp() {
        // pure noise around mid-grey: the edge weighting must not amplify it beyond the input noise
        val w = 120; val h = 120
        val rnd = kotlin.random.Random(1)
        val data = ShortArray(w * h) { (1000 + 7000 + rnd.nextInt(-300, 301)).toShort() }
        val img = RawImage(w, h, data, xtrans, FloatArray(36) { 1000f }, 16383f, 0, 0, w, h, floatArrayOf(1f, 1f, 1f), identity)
        val out = RawDeveloper.develop(img, 4096, options = opts)
        var s = 0.0; var s2 = 0.0; var mx = 0.0; var n = 0
        for (y in 2 until h - 2) for (x in 2 until w - 2) {
            val v = out.green(x, y).toDouble(); s += v; s2 += v * v; n++
            mx = max(mx, abs(out.red(x, y) - 7000.0 / 15383))
        }
        val sd = sqrt(s2 / n - (s / n) * (s / n))
        val inputSd = 300 / sqrt(3.0) / 15383
        assertTrue(sd < inputSd * 1.05, "green sd $sd vs input $inputSd")
        assertTrue(mx < 600.0 / 15383 * 1.5, "red max deviation $mx")
    }

    /** With SPEKTRA_RAF_DUMP set: the scene as a RAF (for LibRaw), the truth and our result as float32 RGB. */
    private fun dump(img: RawImage, out: LinearImage, name: String) {
        val dir = System.getenv("SPEKTRA_RAF_DUMP")?.takeIf { it.isNotEmpty() } ?: return
        val w = img.rawWidth; val h = img.rawHeight
        val mosaic = IntArray(w * h) { img.data[it].toInt() and 0xffff }
        File(dir, "$name.RAF").writeBytes(RafBuilder(w, h, mosaic, black = IntArray(36) { 1000 }, wbGrb = intArrayOf(500, 500, 500),
            visible = w to h).build())
        fun write(file: String, f: (Int, Int, Int) -> Float) {
            val buf = ByteBuffer.allocate(8 + w * h * 12).order(ByteOrder.LITTLE_ENDIAN).putInt(w).putInt(h)
            for (y in 0 until h) for (x in 0 until w) for (c in 0..2) buf.putFloat(f(x, y, c))
            File(dir, file).writeBytes(buf.array())
        }
        write("${name}_truth.f32") { x, y, c -> scene(x, y, w, h)[c].toFloat() }
        write("${name}_ours.f32") { x, y, c -> when (c) { 0 -> out.red(x, y); 1 -> out.green(x, y); else -> out.blue(x, y) } }
    }
}
