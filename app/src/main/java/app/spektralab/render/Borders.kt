package app.spektralab.render

import app.spektralab.engine.Look
import app.spektralab.engine.LookSetup
import kotlin.math.pow

/** Border colours (display sRGB, 0..1) derived from the simulated materials. */
object Borders {
    private fun encode(v: Double): Float {
        val c = v.coerceIn(0.0, 1.0)
        return (if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055).toFloat()
    }

    fun color(look: Look, setup: LookSetup): FloatArray = when {
        // print: unexposed paper; direct scans: pure white like the spektrafilm GUI padding
        look.whiteBorder -> setup.paperWhite?.let { w -> FloatArray(3) { encode(w[it]) } } ?: floatArrayOf(1f, 1f, 1f)
        look.filmEdge -> FloatArray(3) { encode(setup.rebateColor[it]) }
        else -> floatArrayOf(0f, 0f, 0f)
    }
}
