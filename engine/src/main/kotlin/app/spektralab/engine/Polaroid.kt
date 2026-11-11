package app.spektralab.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Instant-film development animation from spektrafilm_gui `polaroid_animation.py`:
 * time coefficients of the nine layers, and the image-wide maxima the shader needs.
 */
object Polaroid {
    const val DURATION_MS = 1600L

    private fun expit(x: Double) = 1.0 / (1.0 + exp(-x.coerceIn(-60.0, 60.0)))

    private fun normalizedSigmoid(t: Double, center: Double, slope: Double): Double {
        val start = expit(-center * slope)
        val end = expit((1.0 - center) * slope)
        return ((expit((t - center) * slope) - start) / (end - start + 1e-4)).coerceIn(0.0, 1.0)
    }

    private fun delayedRise(t: Double, center: Double, slope: Double, tail: Double): Double {
        val rise = normalizedSigmoid(t, center, slope)
        return rise + (1.0 - rise) * t.pow(tail)
    }

    private fun windowedPulse(t: Double, onC: Double, onS: Double, offC: Double, offS: Double, decay: Double): Double =
        normalizedSigmoid(t, onC, onS) * (1.0 - normalizedSigmoid(t, offC, offS)) * (1.0 - t).pow(decay)

    /** neutral, yellow, magenta, cyan, receiver, silver, anti, warm, stain at time t in [0, 1]. */
    fun coefficients(tIn: Double): FloatArray {
        val t = tIn.coerceIn(0.0, 1.0)
        val body = delayedRise(t, 0.19, 7.4, 3.8)
        return doubleArrayOf(
            body * delayedRise(t, 0.12, 8.0, 2.8),
            body * delayedRise(t, 0.22, 11.0, 3.0),
            body * delayedRise(t, 0.31, 10.2, 3.4),
            body * delayedRise(t, 0.52, 9.4, 5.4),
            1.08 * (1.0 - t).pow(0.46),
            1.00 * windowedPulse(t, 0.05, 32.0, 0.44, 8.0, 0.24),
            1.22 * windowedPulse(t, 0.10, 54.0, 0.28, 28.0, 0.18),
            0.96 * windowedPulse(t, 0.12, 20.0, 0.80, 6.5, 0.34),
            0.68 * windowedPulse(t, 0.03, 24.0, 0.98, 4.0, 0.08),
        ).map { it.toFloat() }.toFloatArray()
    }

    /** Max density per channel, max chroma per channel, max neutral, from display RGBA8 pixels. */
    class Maxima(val density: FloatArray, val chroma: FloatArray, val neutral: Float)

    fun maxima(rgba: ByteArray, step: Int = 3): Maxima {
        val lut = DoubleArray(256) { i -> (-ln(max(i / 255.0, 1e-4).pow(2.2))).coerceIn(0.0, 6.0) }
        val dm = DoubleArray(3); val cm = DoubleArray(3); var nm = 0.0
        var i = 0
        while (i + 3 < rgba.size) {
            val r = lut[rgba[i].toInt() and 0xff]; val g = lut[rgba[i + 1].toInt() and 0xff]; val b = lut[rgba[i + 2].toInt() and 0xff]
            val n = min(r, min(g, b))
            dm[0] = max(dm[0], r); dm[1] = max(dm[1], g); dm[2] = max(dm[2], b)
            cm[0] = max(cm[0], r - n); cm[1] = max(cm[1], g - n); cm[2] = max(cm[2], b - n)
            nm = max(nm, n)
            i += 4 * step
        }
        return Maxima(FloatArray(3) { dm[it].toFloat() }, FloatArray(3) { cm[it].toFloat() }, nm.toFloat())
    }
}
