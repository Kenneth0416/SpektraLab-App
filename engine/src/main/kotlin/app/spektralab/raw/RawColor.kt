package app.spektralab.raw

import kotlin.math.abs

/**
 * Builds [RawImage.wb] and [RawImage.camToSrgb] from camera colour metadata.
 *
 * Semantics (what [RawDeveloper] expects): camera values are black-subtracted and
 * normalised to white, multiplied by `wb` (green = 1), then `camToSrgb` maps them to
 * linear sRGB (D65). A neutral surface lit by the as-shot illuminant comes out neutral,
 * and one whose green raw value reaches the white level comes out as (1, 1, 1).
 *
 * The as-shot white is adapted to the output white with Bradford in XYZ, the way the
 * Adobe DNG SDK does it (dcraw / LibRaw instead scale in camera space; the two agree for
 * daylight white balance).
 */
object RawColor {
    class Result(val wb: FloatArray, val camToSrgb: FloatArray)

    /**
     * From a dcraw / LibRaw `adobe_coeff` matrix (XYZ (D65) -> camera, row-major 3x3; any
     * overall scale, so the table's integers / 10000 can be passed as they are) and the
     * camera's as-shot white balance multipliers.
     *
     * @param asShotMultipliers raw-channel gains that make a grey neutral (R, G, B), any
     *   scale, e.g. Canon WB_RGGBLevelsAsShot as (R, (G1 + G2) / 2, B). Null or invalid
     *   (non-positive) uses the matrix's daylight (D65) white.
     */
    fun fromXyzToCamera(xyzToCamera: DoubleArray, asShotMultipliers: DoubleArray? = null): Result? {
        require(xyzToCamera.size == 9)
        val neutral = asShotMultipliers?.takeIf { it.size >= 3 && it.take(3).all { m -> m > 0 && m.isFinite() } }
            ?.let { m -> DoubleArray(3) { m[1] / m[it] } }
            ?: daylightNeutral(xyzToCamera) ?: return null
        return fromDng(xyzToCamera, null, neutral)
    }

    /** Camera RGB of a D65 white under [xyzToCamera], normalised to green = 1. */
    fun daylightNeutral(xyzToCamera: DoubleArray): DoubleArray? {
        val n = Mat3.mulVec(xyzToCamera, D65)
        return if (n.all { it > 0 }) DoubleArray(3) { n[it] / n[1] } else null
    }

    /**
     * The DNG colour model for one calibration illuminant.
     * @param colorMatrix XYZ -> reference camera (DNG ColorMatrix).
     * @param forwardMatrix white-balanced camera -> XYZ (D50) (DNG ForwardMatrix), optional.
     * @param neutral as-shot camera neutral (DNG AsShotNeutral), any scale.
     * @param calibration CameraCalibration, [analogBalance] AnalogBalance (diagonal, 3 values).
     */
    fun fromDng(
        colorMatrix: DoubleArray, forwardMatrix: DoubleArray?, neutral: DoubleArray,
        calibration: DoubleArray? = null, analogBalance: DoubleArray? = null,
    ): Result? {
        if (neutral.size < 3 || neutral.take(3).any { it <= 0 || !it.isFinite() }) return null
        val ab = analogBalance?.takeIf { it.size >= 3 && it.take(3).all { v -> v > 0 } }
            ?.let { doubleArrayOf(it[0], 0.0, 0.0, 0.0, it[1], 0.0, 0.0, 0.0, it[2]) } ?: Mat3.IDENTITY
        val cc = calibration?.takeIf { it.size == 9 && Mat3.invert(it) != null } ?: Mat3.IDENTITY
        val abcc = Mat3.mul(ab, cc)
        // M with XYZ(D50) = M * (camera / neutral)
        val m: DoubleArray = if (forwardMatrix != null && forwardMatrix.size == 9) {
            val inv = Mat3.invert(abcc) ?: return null
            val refNeutral = Mat3.mulVec(inv, neutral)
            if (refNeutral.any { it <= 0 }) return null
            // CameraToXYZ = FM * diag(1 / refNeutral) * inv(AB CC), then times diag(neutral)
            val d = doubleArrayOf(1 / refNeutral[0], 0.0, 0.0, 0.0, 1 / refNeutral[1], 0.0, 0.0, 0.0, 1 / refNeutral[2])
            val camToXyz = Mat3.mul(forwardMatrix, Mat3.mul(d, inv))
            DoubleArray(9) { camToXyz[it] * neutral[it % 3] }
        } else {
            val cm = Mat3.mul(abcc, colorMatrix)
            val inv = Mat3.invert(cm) ?: return null
            val whiteXyz = Mat3.mulVec(inv, neutral)
            if (whiteXyz[1] <= 0) return null
            val adapt = bradford(whiteXyz, D50)
            // XYZ50 = adapt * inv(CM) * camera = adapt * inv(CM) * diag(neutral) * (camera / neutral)
            val a = Mat3.mul(adapt, inv)
            DoubleArray(9) { a[it] * neutral[it % 3] }
        }
        val wb = FloatArray(3) { (neutral[1] / neutral[it]).toFloat() }
        // m is already invariant to the arbitrary scale of AsShotNeutral: its
        // reference-neutral division (FM) or white adaptation (CM) cancels that
        // scale. WB is green-normalised, so dividing by neutral_G again would
        // spuriously change exposure when equivalent metadata is rescaled.
        val camToSrgb = Mat3.mul(XYZ_D50_TO_SRGB, m)
        return Result(wb, FloatArray(9) { camToSrgb[it].toFloat() })
    }

    /** CIE xy chromaticity -> XYZ with Y = 1 (for DNG AsShotWhiteXY). */
    fun xyToXyz(x: Double, y: Double) = doubleArrayOf(x / y, 1.0, (1 - x - y) / y)

    // ---------------------------------------------------------------- constants
    val D65 = doubleArrayOf(0.95047, 1.0, 1.08883)
    /** XYZ (D50) -> linear sRGB (D65), Bradford adapted. */
    val XYZ_D50_TO_SRGB = doubleArrayOf(
        3.1338561, -1.6168667, -0.4906146,
        -0.9787684, 1.9161415, 0.0334540,
        0.0719453, -0.2289914, 1.4052427)
    /** D50 white (0.9642, 1, 0.8251) exactly as [XYZ_D50_TO_SRGB] sees it, so whites map to (1, 1, 1). */
    val D50: DoubleArray = Mat3.mulVec(Mat3.invert(XYZ_D50_TO_SRGB)!!, doubleArrayOf(1.0, 1.0, 1.0))
    /** linear sRGB -> XYZ (D65). */
    val SRGB_TO_XYZ_D65 = doubleArrayOf(
        0.4124564, 0.3575761, 0.1804375,
        0.2126729, 0.7151522, 0.0721750,
        0.0193339, 0.1191920, 0.9503041)
    private val BRADFORD = doubleArrayOf(0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296)

    /** Bradford chromatic adaptation taking XYZ white [from] to [to]. */
    fun bradford(from: DoubleArray, to: DoubleArray): DoubleArray {
        val s = Mat3.mulVec(BRADFORD, from)
        val t = Mat3.mulVec(BRADFORD, to)
        val scale = doubleArrayOf(t[0] / s[0], 0.0, 0.0, 0.0, t[1] / s[1], 0.0, 0.0, 0.0, t[2] / s[2])
        return Mat3.mul(Mat3.invert(BRADFORD)!!, Mat3.mul(scale, BRADFORD))
    }
}

/** Row-major 3x3 matrix helpers. */
object Mat3 {
    val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
        val r = i / 3; val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    fun mulVec(m: DoubleArray, v: DoubleArray) = DoubleArray(3) { m[it * 3] * v[0] + m[it * 3 + 1] * v[1] + m[it * 3 + 2] * v[2] }

    fun invert(m: DoubleArray): DoubleArray? {
        val a = m[0]; val b = m[1]; val c = m[2]; val d = m[3]; val e = m[4]; val f = m[5]; val g = m[6]; val h = m[7]; val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (abs(det) < 1e-12 || !det.isFinite()) return null
        return doubleArrayOf(
            (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det)
    }
}
