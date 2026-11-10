package app.spektralab.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Filming stage: Kotlin port of spektrafilm `runtime/stages/filming.py`,
 * `utils/spectral_upsampling.py` (hanatos2025 path), `model/develop.py`
 * and `model/couplers.py`.
 */
class FilmingModel(val params: RuntimeParams) {
    private val film = params.film
    private val m = film.mSrgbToXyz
    private val lutScale = (film.tcLut.shape[0] - 1).toDouble()

    /** Linear sRGB -> film raw exposure (green-normalised so mid gray is ~1), no exposure comp. */
    fun rgbToRaw(r: Double, g: Double, b: Double, out: DoubleArray) {
        val x = m[0] * r + m[1] * g + m[2] * b
        val y = m[3] * r + m[4] * g + m[5] * b
        val z = m[6] * r + m[7] * g + m[8] * b
        var sum = x + y + z
        val denom = max(sum, 1e-10)
        val cx = x / denom
        val cy = y / denom
        // _tri2quad
        var ty = cy / max(1.0 - cx, 1e-10)
        var tx = (1.0 - cx) * (1.0 - cx)
        tx = tx.coerceIn(0.0, 1.0)
        ty = ty.coerceIn(0.0, 1.0)
        if (sum.isNaN()) sum = 0.0
        Interp.cubic2d(film.tcLut, tx * lutScale, ty * lutScale, out)
        out[0] *= sum; out[1] *= sum; out[2] *= sum
    }

    /** Per-pixel expose (spatial effects handled elsewhere): log10 raw exposure. */
    fun exposeLog(r: Double, g: Double, b: Double, out: DoubleArray) {
        rgbToRaw(r, g, b, out)
        val k = 2.0.pow(params.exposureCompensationEv)
        for (c in 0..2) out[c] = log10(max(out[c] * k, 0.0) + 1e-10)
    }

    // ------------------------------------------------------------------ develop

    val gamma get() = params.densityCurveGamma
    /** Uniform log-exposure axis of the density curves, already divided by gamma. */
    val curveX0 get() = film.logExposure[0] / gamma
    val curveDx get() = (film.logExposure[1] - film.logExposure[0]) / gamma

    val couplerMatrix: Array<DoubleArray> by lazy { computeDirCouplersMatrix() }

    /** Density curves before the effect of DIR couplers (`compute_density_curves_before_dir_couplers`). */
    val densityCurves0: Array<DoubleArray> by lazy {
        val curves = film.normalizedDensityCurves
        val le = film.logExposure
        val mat = couplerMatrix
        val positive = film.isPositive
        val dmax = film.densityMaxNormalized
        val n = curves.size
        val out = Array(n) { DoubleArray(3) }
        for (i in 0..2) {
            val le0 = DoubleArray(n)
            val fp = DoubleArray(n) { curves[it][i] }
            for (j in 0 until n) {
                var amount = 0.0
                for (k in 0..2) {
                    val silver = if (positive) dmax[k] - curves[j][k] else curves[j][k]
                    amount += silver * mat[k][i]
                }
                le0[j] = le[j] - amount
            }
            for (j in 0 until n) out[j][i] = Interp.interp(le[j], le0, fp)
        }
        out
    }

    private fun computeDirCouplersMatrix(): Array<DoubleArray> {
        val c = params.couplers
        val mat = Array(3) { DoubleArray(3) }
        for (i in 0..2) mat[i][i] = c.gammaSamelayerRgb[i] * c.inhibitionSamelayer
        mat[0][1] = c.gammaInterlayerRToGb[0] * c.inhibitionInterlayer
        mat[0][2] = c.gammaInterlayerRToGb[1] * c.inhibitionInterlayer
        mat[1][0] = c.gammaInterlayerGToRb[0] * c.inhibitionInterlayer
        mat[1][2] = c.gammaInterlayerGToRb[1] * c.inhibitionInterlayer
        mat[2][0] = c.gammaInterlayerBToRg[0] * c.inhibitionInterlayer
        mat[2][1] = c.gammaInterlayerBToRg[1] * c.inhibitionInterlayer
        for (i in 0..2) for (j in 0..2) mat[i][j] *= c.amount
        return mat
    }

    /** Density before couplers, from log raw (first half of `develop`). */
    fun density0(logRaw: DoubleArray, out: DoubleArray) {
        val curves = film.normalizedDensityCurves
        for (c in 0..2) out[c] = Interp.interpUniform(logRaw[c], curveX0, curveDx, curves, c)
    }

    /** DIR coupler log-exposure correction from the uncorrected density. */
    fun couplerCorrection(density0: DoubleArray, out: DoubleArray) {
        val mat = couplerMatrix
        val dmax = film.densityMaxNormalized
        for (mm in 0..2) {
            var s = 0.0
            for (k in 0..2) {
                val silver = if (film.isPositive) dmax[k] - density0[k] else density0[k]
                s += silver * mat[k][mm]
            }
            out[mm] = s
        }
    }

    /** Full per-pixel develop (no coupler diffusion, no grain): log raw -> CMY density. */
    fun develop(logRaw: DoubleArray, out: DoubleArray) {
        val curves = film.normalizedDensityCurves
        if (!params.couplers.active) {
            for (c in 0..2) out[c] = Interp.interpUniform(logRaw[c], curveX0, curveDx, curves, c)
            return
        }
        val d0 = DoubleArray(3)
        density0(logRaw, d0)
        val corr = DoubleArray(3)
        couplerCorrection(d0, corr)
        val c0 = densityCurves0
        for (c in 0..2) out[c] = Interp.interpUniform(logRaw[c] - corr[c], curveX0, curveDx, c0, c)
    }

    /** `_simple_rgb_to_density_spectral`: spectral density of a uniform patch. */
    fun simpleRgbToDensitySpectral(rgb: Double): DoubleArray {
        val raw = DoubleArray(3)
        rgbToRaw(rgb, rgb, rgb, raw)
        val le = film.logExposure
        val x0 = le[0] / gamma
        val dx = (le[1] - le[0]) / gamma
        val cmy = DoubleArray(3) { c ->
            Interp.interpUniform(log10(raw[c] + 1e-10), x0, dx, film.densityCurves, c)
        }
        return densitySpectral(film.channelDensity, film.baseDensity, cmy)
    }
}

/** `compute_density_spectral`: sum_c cmy_c * dye_c(lambda) + base(lambda). NaN propagates. */
fun densitySpectral(channelDensity: Array<DoubleArray>, base: DoubleArray, cmy: DoubleArray): DoubleArray =
    DoubleArray(N_WL) { l ->
        channelDensity[l][0] * cmy[0] + channelDensity[l][1] * cmy[1] + channelDensity[l][2] * cmy[2] + base[l]
    }

/**
 * Printing stage: port of spektrafilm `runtime/stages/printing.py` and the
 * colour enlarger (`model/color_filters.py`, custom dichroics).
 */
class PrintingModel(val params: RuntimeParams, val global: GlobalData, filming: FilmingModel) {
    private val film = params.film
    private val paper = params.paper ?: error("printing needs a paper")
    private val sens = paper.sensitivity

    /** Enlarger light after the C/M/Y dichroic filtration (Kodak CC units). */
    val printIlluminant: DoubleArray = run {
        val e = params.enlarger
        val cc = doubleArrayOf(e.cFilterNeutral, e.mFilterNeutral + e.mFilterShift, e.yFilterNeutral + e.yFilterShift)
        filteredIlluminant(global.enlargerIlluminant, global.dichroicFilters, cc)
    }

    /** Mid-gray exposure normalisation (`_compute_exposure_factor_midgray`). */
    val exposureFactor: Double = run {
        val e = params.enlarger
        val midgray = filming.simpleRgbToDensitySpectral(0.184)
        val factorMid = exposureFactor(midgray)
        val factorComp = if (e.printExposureCompensation)
            exposureFactor(filming.simpleRgbToDensitySpectral(0.184 * 2.0.pow(params.exposureCompensationEv)))
        else 1.0
        when {
            e.printExposureCompensation && !e.normalizePrintExposure -> factorComp / factorMid
            e.normalizePrintExposure && e.printExposureCompensation -> factorComp
            e.normalizePrintExposure && !e.printExposureCompensation -> factorMid
            else -> 1.0
        }
    }

    private fun exposureFactor(densSpectral: DoubleArray): Double {
        val raw = rawFromDensity(densSpectral)
        var logSum = 0.0
        for (c in 0..2) logSum += ln(max(raw[c], 1e-10))
        return 1.0 / exp(logSum / 3.0)
    }

    private fun rawFromDensity(densSpectral: DoubleArray): DoubleArray {
        val raw = DoubleArray(3)
        for (l in 0 until N_WL) {
            var light = 10.0.pow(-densSpectral[l]) * printIlluminant[l]
            if (light.isNaN()) light = 0.0
            raw[0] += light * sens[l][0]
            raw[1] += light * sens[l][1]
            raw[2] += light * sens[l][2]
        }
        return raw
    }

    /** Film CMY density -> print log raw exposure (`expose`, no diffusion). */
    fun expose(cmyFilm: DoubleArray, out: DoubleArray) {
        val raw = rawFromDensity(densitySpectral(film.channelDensity, film.baseDensity, cmyFilm))
        for (c in 0..2) {
            // _film_cmy_to_print_log_raw followed by expose()
            val lr = log10(max(raw[c] * exposureFactor, 0.0) + 1e-10)
            out[c] = log10(max(10.0.pow(lr) * params.enlarger.printExposure, 0.0) + 1e-10)
        }
    }

    /** Print log raw -> print CMY density (fitted curves, no morph). */
    fun develop(logRaw: DoubleArray, out: DoubleArray) {
        val le = paper.logExposure
        val dx = le[1] - le[0]
        for (c in 0..2) out[c] = Interp.interpUniform(logRaw[c], le[0], dx, paper.densityCurves, c)
    }

    /**
     * Fast path for building the film-density LUT: spectral transmittance of
     * the film is separable per dye, so 10^-(sum) = product of per-axis terms.
     */
    fun buildFilmToPrintDensityLut(n: Int, lo: DoubleArray, hi: DoubleArray): FloatArray {
        val cd = film.channelDensity
        val axes = Array(3) { c -> DoubleArray(n) { i -> lo[c] + (hi[c] - lo[c]) * i / (n - 1) } }
        // per-axis transmittance tables [c][i][l]
        val t = Array(3) { c -> Array(n) { i -> DoubleArray(N_WL) { l ->
            val d = cd[l][c]
            if (d.isNaN()) 0.0 else 10.0.pow(-d * axes[c][i])
        } } }
        val baseLight = DoubleArray(N_WL) { l ->
            var bad = film.baseDensity[l].isNaN()
            for (c in 0..2) if (cd[l][c].isNaN()) bad = true
            if (bad) 0.0 else 10.0.pow(-film.baseDensity[l]) * printIlluminant[l]
        }
        val s0 = DoubleArray(N_WL) { sens[it][0] }
        val s1 = DoubleArray(N_WL) { sens[it][1] }
        val s2 = DoubleArray(N_WL) { sens[it][2] }
        val out = FloatArray(n * n * n * 3)
        val k = exposureFactor * params.enlarger.printExposure
        val le = paper.logExposure
        val dx = le[1] - le[0]
        val curves = paper.densityCurves
        val tmp = DoubleArray(N_WL)
        for (i in 0 until n) {
            val ti = t[0][i]
            for (j in 0 until n) {
                val tj = t[1][j]
                for (l in 0 until N_WL) tmp[l] = baseLight[l] * ti[l] * tj[l]
                for (kk in 0 until n) {
                    val tk = t[2][kk]
                    var r0 = 0.0; var r1 = 0.0; var r2 = 0.0
                    for (l in 0 until N_WL) {
                        val light = tmp[l] * tk[l]
                        r0 += light * s0[l]; r1 += light * s1[l]; r2 += light * s2[l]
                    }
                    val idx = ((i * n + j) * n + kk) * 3
                    out[idx] = Interp.interpUniform(log10(max(r0 * k, 0.0) + 1e-10), le[0], dx, curves, 0).toFloat()
                    out[idx + 1] = Interp.interpUniform(log10(max(r1 * k, 0.0) + 1e-10), le[0], dx, curves, 1).toFloat()
                    out[idx + 2] = Interp.interpUniform(log10(max(r2 * k, 0.0) + 1e-10), le[0], dx, curves, 2).toFloat()
                }
            }
        }
        return out
    }

    companion object {
        fun filteredIlluminant(illuminant: DoubleArray, filters: Array<DoubleArray>, cc: DoubleArray): DoubleArray {
            val trans = DoubleArray(3) { 10.0.pow(-cc[it] / 100.0) }
            return DoubleArray(N_WL) { l ->
                var total = 1.0
                for (i in 0..2) total *= 1.0 - (1.0 - filters[l][i]) * (1.0 - trans[i])
                illuminant[l] * total
            }
        }
    }
}
