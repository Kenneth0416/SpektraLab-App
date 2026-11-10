package app.spektralab.engine

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Constants for the spektrafilm sublayer grain model (`model/grain.py`,
 * `apply_grain_to_density_layers`), precomputed for the GPU.
 *
 * Per channel c and sublayer s the developed density is a Poisson-binomial
 * particle count: seeds ~ Poisson(n / sat), grains ~ Binomial(seeds, p),
 * value = grains * od * sat, which is Poisson(n p / sat) * od * sat.
 */
class GrainSetup(params: RuntimeParams) {
    val active = params.grain.active
    val amount = params.grain.amount
    val blurPx = params.grain.blur
    val densityMin = params.grain.densityMin.copyOf()
    val uniformity = params.grain.uniformity.copyOf()

    /** Layer-density lookup: [3 sublayers][TABLE][3 channels] (one texture row per sublayer), channel density on [0, tableHi[c]]. */
    val tableSize = 128
    val tableHi = DoubleArray(3)
    val layerTable: FloatArray

    /** Per [sublayer][channel]. */
    val layerDensityMin = Array(3) { DoubleArray(3) }
    val layerDensityMax = Array(3) { DoubleArray(3) }
    /** Particles per um^2 of film for each sublayer/channel. */
    val particlesPerUm2 = Array(3) { DoubleArray(3) }

    init {
        val film = params.film
        val curves = film.normalizedDensityCurves
        val layers = film.densityCurvesLayers // [K][3 sublayers][3 ch]
        val k = curves.size
        val g = params.grain
        for (c in 0..2) {
            val dmlRaw = DoubleArray(3) { s -> (0 until k).maxOf { layers[it, s, c].toDouble() } }
            val total = dmlRaw.sum()
            for (s in 0..2) {
                val frac = dmlRaw[s] / total
                layerDensityMin[s][c] = frac * g.densityMin[c]
                layerDensityMax[s][c] = dmlRaw[s] + layerDensityMin[s][c]
                val particleArea = g.particleAreaUm2 * g.particleScale[c] * g.particleScaleLayers[s]
                particlesPerUm2[s][c] = frac / particleArea
            }
            tableHi[c] = curves.maxOf { it[c] }
        }
        layerTable = FloatArray(tableSize * 9)
        val positive = film.isPositive
        for (c in 0..2) {
            // x axis: channel density (increasing for negatives, decreasing for positives)
            val xs = DoubleArray(k) { if (positive) -curves[it][c] else curves[it][c] }
            for (s in 0..2) {
                val fp = DoubleArray(k) { layers[it, s, c].toDouble() }
                for (i in 0 until tableSize) {
                    val d = tableHi[c] * i / (tableSize - 1)
                    val v = Interp.interp(if (positive) -d else d, xs, fp)
                    layerTable[(s * tableSize + i) * 3 + c] = v.toFloat()
                }
            }
        }
    }

    /**
     * CPU reference of the grain for one pixel (used by tests and as a
     * specification for the shader). [normal] and [uniform] are noise sources.
     */
    fun apply(density: DoubleArray, pixelSizeUm: Double, rnd: java.util.Random, out: DoubleArray) {
        val area = pixelSizeUm * pixelSizeUm
        for (c in 0..2) {
            var acc = 0.0
            var mean = 0.0
            val t = (density[c] / tableHi[c]).coerceIn(0.0, 1.0) * (tableSize - 1)
            val i0 = t.toInt().coerceAtMost(tableSize - 2)
            val f = t - i0
            for (s in 0..2) {
                val v0 = layerTable[(s * tableSize + i0) * 3 + c]
                val v1 = layerTable[(s * tableSize + i0 + 1) * 3 + c]
                val dl = v0 + f * (v1 - v0) + layerDensityMin[s][c]
                val dmax = layerDensityMax[s][c]
                val n = area * particlesPerUm2[s][c]
                val p = (dl / dmax).coerceIn(1e-6, 1 - 1e-6)
                val sat = 1 - p * uniformity[c] * (1 - 1e-6)
                val od = dmax / n
                val lambda = n * p / sat
                val count = poisson(lambda, rnd)
                acc += count * od * sat
                mean += lambda * od * sat
            }
            out[c] = mean + amount * (acc - mean) - densityMin[c]
        }
    }

    private fun poisson(lambda: Double, rnd: java.util.Random): Double {
        if (lambda > 30) return max(0.0, lambda + sqrt(lambda) * rnd.nextGaussian())
        val l = exp(-lambda)
        var k = 0
        var p = 1.0
        do { k++; p *= rnd.nextDouble() } while (p > l)
        return (k - 1).toDouble()
    }
}
