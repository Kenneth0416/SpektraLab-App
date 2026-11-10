package app.spektralab.engine

import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue

/** The GPU samples the tc LUT bilinearly; check that it stays close to the Mitchell lookup. */
class TcLutSamplingTest {
    private val assets = File(System.getProperty("spektra.assets"))

    private fun bilinear(lut: NdArray, x: Double, y: Double, out: DoubleArray) {
        val n = lut.shape[0]
        val xi = x.coerceIn(0.0, n - 1.0); val yi = y.coerceIn(0.0, n - 1.0)
        val x0 = xi.toInt().coerceAtMost(n - 2); val y0 = yi.toInt().coerceAtMost(n - 2)
        val fx = xi - x0; val fy = yi - y0
        for (c in 0..2) {
            val a = lut[x0, y0, c] * (1 - fx) + lut[x0 + 1, y0, c] * fx
            val b = lut[x0, y0 + 1, c] * (1 - fx) + lut[x0 + 1, y0 + 1, c] * fx
            out[c] = a * (1 - fy) + b * fy
        }
    }

    @Test
    fun bilinearCloseToCubic() {
        val film = FilmProfile.read(File(assets, "film/kodak_portra_400.spk").inputStream())
        val lut = film.tcLut
        val n = lut.shape[0]
        val a = DoubleArray(3); val b = DoubleArray(3)
        var maxErr = 0.0; var sum = 0.0; var cnt = 0
        val rnd = java.util.Random(3)
        repeat(20000) {
            val x = rnd.nextDouble() * (n - 1); val y = rnd.nextDouble() * (n - 1)
            Interp.cubic2d(lut, x, y, a); bilinear(lut, x, y, b)
            for (c in 0..2) if (a[c] > 1e-3) {
                val e = abs(log10(max(a[c], 1e-6)) - log10(max(b[c], 1e-6)))
                maxErr = max(maxErr, e); sum += e; cnt++
            }
        }
        println("tc lut bilinear vs cubic: max log10 err %.5f mean %.6f".format(maxErr, sum / cnt))
        assertTrue(sum / cnt < 0.002)
    }
}
