package app.spektralab.engine

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

class SpatialTest {
    private val assets = File(System.getProperty("spektra.assets"))
    private val data = EngineData { File(assets, it).inputStream() }
    private val ref = SpkFile.read(javaClass.getResourceAsStream("/spatial/spatial.spk")!!)

    /** Encircled energy of a Gaussian mixture within the pixel disc r <= R. */
    private fun encircled(m: GaussianMixture, radius: Double, c: Int): Double {
        var count = 0
        val ri = radius.toInt()
        for (y in -ri..ri) for (x in -ri..ri) if (x * x + y * y <= radius * radius) count++
        val rEff = sqrt(count / PI)
        var e = m.identity[c]
        for (k in m.sigmasPx.indices) e += m.weights[k][c] * (1 - exp(-rEff * rEff / (2 * m.sigmasPx[k] * m.sigmasPx[k])))
        return e
    }

    private fun compare(name: String, m: GaussianMixture, tol: Double): Boolean {
        val radii = ref.array("radii")
        val r = ref.array(name)
        var maxErr = 0.0
        val sb = StringBuilder()
        for (i in 0 until radii.size) for (c in 0..2) {
            val mine = encircled(m, radii[i].toDouble(), c)
            val err = abs(mine - r[i, c])
            maxErr = max(maxErr, err)
            if (c == 0) sb.append("R=%g ref=%.4f mine=%.4f; ".format(radii[i], r[i, c], mine))
        }
        println("$name: max encircled-energy error %.4f (sigmas=${m.sigmasPx.joinToString { "%.1f".format(it) }})\n   $sb".format(maxErr))
        return maxErr < tol
    }

    @Test
    fun halationMatchesSpektrafilm() {
        val look = Look(film = "kodak_portra_400", halation = 4.0)
        val p = LookMapper.toRuntime(look, data)
        p.couplers.active = false
        assertTrue(compare("halation_px5", SpatialKernels.exposureBlur(p, 5.0, 300.0), 0.02))
        assertTrue(compare("halation_px20", SpatialKernels.exposureBlur(p, 20.0, 300.0), 0.02))
    }

    @Test
    fun diffusionFiltersMatchSpektrafilm() {
        for (fam in listOf("black_pro_mist", "cinebloom", "glimmerglass", "pro_mist")) {
            val look = Look(film = "kodak_portra_400", diffusion = fam, diffusionAmount = 0.5)
            val p = LookMapper.toRuntime(look, data)
            p.halation.active = false
            assertTrue(compare("diffusion_${fam}_px20", SpatialKernels.exposureBlur(p, 20.0, 400.0), 0.03), fam)
        }
    }

    @Test
    fun grainStatisticsMatchSpektrafilm() {
        val levels = ref.array("grain_levels")
        for (px in listOf(6.0, 20.0)) {
            val p = LookMapper.toRuntime(Look(film = "kodak_portra_400"), data)
            p.couplers.active = false
            val fm = FilmingModel(p)
            val g = GrainSetup(p)
            val stats = ref.array("grain_px${px.toInt()}")
            val rnd = java.util.Random(1)
            for (li in 0 until levels.size) {
                val logRaw = DoubleArray(3) { levels[li].toDouble() }
                val d = DoubleArray(3)
                fm.develop(logRaw, d)
                val n = 20000
                val sum = DoubleArray(3); val sq = DoubleArray(3)
                val out = DoubleArray(3)
                repeat(n) {
                    g.apply(d, px, rnd, out)
                    for (c in 0..2) { sum[c] += out[c]; sq[c] += out[c] * out[c] }
                }
                for (c in 0..2) {
                    val mean = sum[c] / n
                    val std = sqrt(max(sq[c] / n - mean * mean, 0.0))
                    val refMean = stats[li, 3 + c]; val refStd = stats[li, 6 + c]
                    println("px=$px level=${levels[li]} ch=$c density=%.3f ref(mean=%.4f std=%.4f) mine(mean=%.4f std=%.4f)"
                        .format(d[c], refMean, refStd, mean, std))
                    assertTrue(abs(mean - refMean) < 0.01 + 0.01 * refMean)
                    assertTrue(abs(std - refStd) < 0.003 + 0.1 * refStd)
                }
            }
        }
    }
}
