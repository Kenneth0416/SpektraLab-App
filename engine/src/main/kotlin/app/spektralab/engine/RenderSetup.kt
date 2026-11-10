package app.spektralab.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * Everything the GPU renderer needs for one look, independent of the output
 * resolution. Building it runs the spektrafilm print stage over a 3D grid of
 * film densities (tens of milliseconds), so callers cache it per look.
 */
class LookSetup(val look: Look, val params: RuntimeParams, global: GlobalData, lutSize: Int = 40, reuseLut: Lut3? = null) {
    val pipeline = PixelPipeline(params, global)
    private val filming = pipeline.filming
    val film get() = params.film

    /** Linear sRGB -> XYZ (row-major 3x3). */
    val inputMatrix = FloatArray(9) { film.mSrgbToXyz[it].toFloat() }
    val tcLutSize = film.tcLut.shape[0]
    val tcLut: FloatArray = film.tcLut.data
    /** Film exposure multiplier 2^ev (auto exposure is applied separately). */
    val exposureScale = 2.0.pow(params.exposureCompensationEv).toFloat()

    val curveX0 = filming.curveX0.toFloat()
    val curveX1 = (filming.curveX0 + filming.curveDx * (film.logExposure.size - 1)).toFloat()
    val curveSize = film.logExposure.size
    /** Normalised density curves [K][3] and the pre-coupler curves, flattened. */
    val curves = flatten(film.normalizedDensityCurves)
    val curves0 = if (params.couplers.active) flatten(filming.densityCurves0) else curves
    val couplersActive = params.couplers.active
    /** Coupler matrix, row = donor layer, column = receiving layer. */
    val couplerMatrix = FloatArray(9) { filming.couplerMatrix[it / 3][it % 3].toFloat() }
    val positive = film.isPositive
    val densityMax = FloatArray(3) { film.densityMaxNormalized[it].toFloat() }

    val outputLut: Lut3 = reuseLut?.takeIf { it.n == lutSize } ?: pipeline.buildOutputLut(lutSize)
    /** Print glare in linear output units (fraction of the viewing white). */
    val glare = if (params.scanFilm) 0f else (params.glarePercent / 100.0).toFloat()

    val grain = GrainSetup(params)

    fun exposureBlur(pixelSizeUm: Double, maxSigmaPx: Double) =
        SpatialKernels.exposureBlur(params, pixelSizeUm, maxSigmaPx)

    fun couplerBlur(pixelSizeUm: Double, maxSigmaPx: Double) =
        SpatialKernels.couplerBlur(params, pixelSizeUm, maxSigmaPx)

    /** Physical size of one output pixel on the film, um. */
    fun pixelSizeUm(longEdgePx: Int): Double = params.filmFormatMm * 1000.0 / max(longEdgePx, 1)

    companion object {
        fun create(look: Look, data: EngineData, lutSize: Int = 40, reuseLut: Lut3? = null): LookSetup =
            LookSetup(look, LookMapper.toRuntime(look, data), data.global, lutSize, reuseLut)

        /** Only these settings change the film-density -> output LUT. */
        fun lutKey(look: Look, lutSize: Int): String =
            listOf(look.film, look.paper, look.printEv, look.glare, look.filmEv, look.push, look.yFilter, look.mFilter, lutSize)
                .joinToString("|")

        private fun flatten(a: Array<DoubleArray>) = FloatArray(a.size * 3) { a[it / 3][it % 3].toFloat() }
    }
}

/** Port of spektrafilm `measure_autoexposure_ev` (center weighted), on linear sRGB. */
object AutoExposure {
    /** [rgb] is interleaved linear sRGB (3 floats per pixel), row-major w x h. */
    fun centerWeightedEv(rgb: FloatArray, w: Int, h: Int): Double {
        val longEdge = max(w, h).toDouble()
        val nw = w / longEdge
        val nh = h / longEdge
        val sigma = 0.2
        var sum = 0.0
        var wsum = 0.0
        for (y in 0 until h) {
            val yy = (y.toDouble() / h - 0.5) * nh
            for (x in 0 until w) {
                val xx = (x.toDouble() / w - 0.5) * nw
                val m = exp(-(xx * xx + yy * yy) / (2 * sigma * sigma))
                val i = (y * w + x) * 3
                val lum = 0.2126 * rgb[i] + 0.7152 * rgb[i + 1] + 0.0722 * rgb[i + 2]
                sum += m * lum
                wsum += m
            }
        }
        val exposure = sum / wsum / 0.184
        if (exposure <= 0 || exposure.isNaN()) return 0.0
        return (-ln(exposure) / ln(2.0)).coerceIn(-8.0, 8.0)
    }
}
