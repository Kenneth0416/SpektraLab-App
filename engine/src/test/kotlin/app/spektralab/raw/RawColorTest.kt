package app.spektralab.raw

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RawColorTest {
    /** A dcraw adobe_coeff entry as printed in the table (Canon EOS 5D Mark II), / 10000. */
    private val adobe = intArrayOf(4716, 603, -830, -7798, 15474, 2480, -1496, 1937, 6651).map { it / 10000.0 }.toDoubleArray()

    private fun apply(m: FloatArray, v: DoubleArray) = DoubleArray(3) { m[it * 3] * v[0] + m[it * 3 + 1] * v[1] + m[it * 3 + 2] * v[2] }

    @Test
    fun neutralStaysNeutral() {
        for (mul in listOf(doubleArrayOf(2.1, 1.0, 1.6), doubleArrayOf(1500.0, 1024.0, 2300.0), doubleArrayOf(1.3, 1.0, 3.2), null)) {
            val r = assertNotNull(RawColor.fromXyzToCamera(adobe, mul))
            assertEquals(1f, r.wb[1])
            // raw response to a grey under the as-shot light is proportional to 1 / mul
            val neutral = mul?.let { m -> DoubleArray(3) { m[1] / m[it] } } ?: RawColor.daylightNeutral(adobe)!!
            for (k in doubleArrayOf(1.0, 0.18)) {
                val out = apply(r.camToSrgb, DoubleArray(3) { k * neutral[it] * r.wb[it] })
                for (c in 0..2) assertEquals(k, out[c], 1e-5, "mul ${mul?.toList()} channel $c")
            }
        }
        // the matrix scale does not matter
        val a = RawColor.fromXyzToCamera(adobe, doubleArrayOf(2.0, 1.0, 1.5))!!
        val b = RawColor.fromXyzToCamera(DoubleArray(9) { adobe[it] * 10000 }, doubleArrayOf(4.0, 2.0, 3.0))!!
        for (i in 0 until 9) assertEquals(a.camToSrgb[i], b.camToSrgb[i], 1e-5f)
    }

    @Test
    fun daylightMatchesDcraw() {
        // dcraw cam_xyz_coeff: cam_rgb = cam_xyz * xyz_rgb, rows normalised to 1, pre_mul = 1 / row sum, rgb_cam = inv(cam_rgb)
        val camRgb = Mat3.mul(adobe, RawColor.SRGB_TO_XYZ_D65)
        val sums = DoubleArray(3) { camRgb[it * 3] + camRgb[it * 3 + 1] + camRgb[it * 3 + 2] }
        val rgbCam = Mat3.invert(DoubleArray(9) { camRgb[it] / sums[it / 3] })!!
        val preMul = DoubleArray(3) { 1 / sums[it] }
        val r = RawColor.fromXyzToCamera(adobe, preMul)!!
        var worst = 0.0
        for (raw in listOf(doubleArrayOf(0.2, 0.5, 0.3), doubleArrayOf(0.7, 0.1, 0.05), doubleArrayOf(0.1, 0.3, 0.6))) {
            val dcraw = Mat3.mulVec(rgbCam, DoubleArray(3) { raw[it] * preMul[it] / preMul[1] })
            val ours = apply(r.camToSrgb, DoubleArray(3) { raw[it] * r.wb[it] })
            for (c in 0..2) worst = max(worst, abs(dcraw[c] - ours[c]))
        }
        println("adobe_coeff daylight vs dcraw: max diff %.2e".format(worst))
        assertTrue(worst < 2e-3)
    }

    @Test
    fun dngColourModelWithCalibrationAndAnalogBalance() {
        val cm = doubleArrayOf(0.9, -0.3, -0.1, -0.4, 1.3, 0.15, -0.05, 0.2, 0.6)
        val cc = doubleArrayOf(1.05, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.97)
        val ab = doubleArrayOf(1.1, 1.0, 0.9)
        val neutral = doubleArrayOf(0.45, 1.0, 0.7)
        // a ForwardMatrix maps white-balanced (1, 1, 1) to D50: rows sum to the D50 white
        val fm0 = doubleArrayOf(0.60, 0.25, 0.11, 0.25, 0.80, -0.05, 0.02, -0.15, 0.95)
        val fmD50 = DoubleArray(9) { fm0[it] * RawColor.D50[it / 3] / (fm0[it / 3 * 3] + fm0[it / 3 * 3 + 1] + fm0[it / 3 * 3 + 2]) }
        for (fm in listOf(null, fmD50)) {
            val r = RawColor.fromDng(cm, fm, neutral, cc, ab)!!
            val out = apply(r.camToSrgb, DoubleArray(3) { neutral[it] * r.wb[it] })
            for (c in 0..2) assertEquals(1.0, out[c], 1e-4, "fm=${fm != null}")
        }
    }

    @Test
    fun cfaPatterns() {
        for (p in listOf(CfaPattern.RGGB, CfaPattern.BGGR, CfaPattern.GRBG, CfaPattern.GBRG)) assertTrue(p.isBayer, "$p")
        assertContentEquals(CfaPattern.RGGB.colors, CfaPattern.fromDcrawFilters(0x94949494.toInt()).colors)
        assertContentEquals(CfaPattern.BGGR.colors, CfaPattern.fromDcrawFilters(0x16161616).colors)
        assertContentEquals(CfaPattern.GRBG.colors, CfaPattern.fromDcrawFilters(0x61616161).colors)
        assertContentEquals(CfaPattern.GBRG.colors, CfaPattern.fromDcrawFilters(0x49494949).colors)
        // a pattern given relative to (1, 1) is the opposite phase at (0, 0)
        assertContentEquals(CfaPattern.BGGR.colors, CfaPattern.RGGB.anchoredAt(1, 1).colors)
        assertContentEquals(CfaPattern.GRBG.colors, CfaPattern.RGGB.anchoredAt(0, 1).colors)
        assertEquals(0, CfaPattern.RGGB.color(-2, 4))
        val xtrans = CfaPattern.parse(6, 6, XTRANS)
        assertFalse(xtrans.isBayer)
        assertEquals(36, xtrans.size)
        assertTrue(CfaPattern.parse(4, 2, "RGRGGBGB").isBayer)
        assertFalse(CfaPattern(2, 2, intArrayOf(0, 1, 2, 1)).isBayer)
        assertContentEquals(floatArrayOf(10f, 20f, 20f, 30f), CfaPattern.RGGB.perColor(floatArrayOf(10f, 20f, 30f)))
    }

    /** Builds a RawImage directly, as a maker-format reader would, from a known scene. */
    private fun synthetic(cfa: CfaPattern, cropX: Int, cropY: Int, mul: DoubleArray): Pair<RawImage, (Double, Double) -> DoubleArray> {
        val w = 80; val h = 60; val black = 512f; val white = 16383f
        fun scene(x: Double, y: Double) = doubleArrayOf(0.08 + 0.5 * x / w, 0.1 + 0.45 * y / h, 0.55 - 0.4 * x * y / (w * h))
        val toCam = Mat3.mul(adobe, RawColor.SRGB_TO_XYZ_D65)
        // raw green of a D65 white reaches the white level
        val d65 = Mat3.mulVec(adobe, RawColor.D65)
        val data = ShortArray(w * h) { i ->
            val x = i % w; val y = i / w
            val cam = Mat3.mulVec(toCam, scene(x.toDouble(), y.toDouble()))
            val c = cfa.color(y, x)
            (cam[c] / d65[1] * (white - black) + black).toInt().coerceIn(0, 65535).toShort()
        }
        val col = RawColor.fromXyzToCamera(adobe, mul)!!
        val img = RawImage(w, h, data, cfa, cfa.perColor(floatArrayOf(black, black, black)), white,
            cropX, cropY, w - cropX - 3, h - cropY - 2, col.wb, col.camToSrgb, make = "Test", model = "Synthetic")
        return img to { x, y -> scene(x + cropX, y + cropY) }
    }

    @Test
    fun makerStyleImageWithOddCropDevelops() {
        val d65 = RawColor.daylightNeutral(adobe)!!
        val mul = DoubleArray(3) { 1 / d65[it] }
        for (cfa in listOf(CfaPattern.RGGB, CfaPattern.GBRG)) for ((cx, cy) in listOf(0 to 0, 1 to 0, 0 to 1, 3 to 5)) {
            val (img, truth) = synthetic(cfa, cx, cy, mul)
            assertTrue(RawDeveloper.supports(img))
            val out = RawDeveloper.develop(img, 4096)
            var worst = 0.0
            for (y in 3 until out.height - 3) for (x in 3 until out.width - 3) {
                val t = truth(x.toDouble(), y.toDouble())
                worst = max(worst, max(abs(out.red(x, y) - t[0]), max(abs(out.green(x, y) - t[1]), abs(out.blue(x, y) - t[2]))))
            }
            assertTrue(worst < 0.01, "$cfa crop $cx,$cy error $worst")
            val binned = RawDeveloper.develop(img, 4096, fast = true)
            assertEquals(img.cropWidth / 2, binned.width)
        }
    }

    @Test
    fun developerRefusesXTrans() {
        val cfa = CfaPattern.parse(6, 6, XTRANS)
        val img = RawImage(24, 24, ShortArray(24 * 24), cfa, FloatArray(36) { 256f }, 4095f, 0, 0, 24, 24,
            floatArrayOf(2f, 1f, 1.5f), FloatArray(9) { if (it % 4 == 0) 1f else 0f })
        assertFalse(RawDeveloper.supports(img))
        assertFailsWith<IllegalArgumentException> { RawDeveloper.develop(img, 1024) }
        assertEquals(256f, img.blackAt(7, 13))
    }

    @Test
    fun mirroredOrientations() {
        val d65 = RawColor.daylightNeutral(adobe)!!
        val (img, _) = synthetic(CfaPattern.RGGB, 0, 0, DoubleArray(3) { 1 / d65[it] })
        val base = RawDeveloper.develop(img, 4096)
        for (o in 2..8) {
            val r = RawImage(img.rawWidth, img.rawHeight, img.data, img.cfa, img.black, img.white, img.cropX, img.cropY,
                img.cropWidth, img.cropHeight, img.wb, img.camToSrgb, orientation = o)
            val out = RawDeveloper.develop(r, 4096)
            assertEquals(r.uprightWidth, out.width)
            val w = base.width; val h = base.height
            for ((u, v) in listOf(0 to 0, w - 1 to 0, 5 to h - 2, 17 to 11)) {
                val (x, y) = when (o) {
                    2 -> (w - 1 - u) to v; 3 -> (w - 1 - u) to (h - 1 - v); 4 -> u to (h - 1 - v)
                    5 -> v to u; 6 -> (h - 1 - v) to u; 7 -> (h - 1 - v) to (w - 1 - u); else -> v to (w - 1 - u)
                }
                assertEquals(base.red(u, v), out.red(x, y), "orientation $o")
            }
        }
    }

    companion object {
        const val XTRANS = "GGRGGBGGBGGRBRGRBGGGBGGRGGRGGBRBGBRG"
    }
}
