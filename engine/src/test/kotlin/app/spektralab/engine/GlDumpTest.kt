package app.spektralab.engine

import app.spektralab.gl.Shaders
import java.io.File
import kotlin.test.Test

/**
 * Writes the GLSL programs and look tables for the WebGL2 shader check in
 * `tools/glcheck` (runs only when -Dspektra.gldump=<dir> is set).
 */
class GlDumpTest {
    private val outDir = System.getProperty("spektra.gldump")?.takeIf { it.isNotEmpty() }

    @Test
    fun dump() {
        val out = File(outDir ?: return).apply { mkdirs() }
        mapOf("vertex" to Shaders.VERTEX, "expose" to Shaders.EXPOSE, "down" to Shaders.DOWN, "blur" to Shaders.BLUR,
            "combine" to Shaders.COMBINE, "corr" to Shaders.CORR, "develop" to Shaders.DEVELOP, "final" to Shaders.FINAL,
            "polaroid" to Shaders.POLAROID)
            .forEach { (k, v) -> File(out, "$k.glsl").writeText(v) }
        val assets = File(System.getProperty("spektra.assets"))
        val data = EngineData { File(assets, it).inputStream() }
        fun arr(a: FloatArray) = a.joinToString(",", "[", "]")
        fun arrd(a: DoubleArray) = a.joinToString(",", "[", "]")
        val looks = mapOf("a" to Look(film = "kodak_portra_400"), "b" to Look(film = "fujifilm_velvia_100"),
            "c" to Look(film = "kodak_ektar_100", paper = "kodak_2383", yFilter = 20.0, printEv = 0.5, filmEv = 1.0))
        for ((name, look) in looks) {
            val s = LookSetup.create(look, data, 36)
            val rnd = java.util.Random(5)
            val colors = DoubleArray(48 * 3) { Math.pow(rnd.nextDouble(), 2.2) * 1.2 }
            val expected = DoubleArray(48 * 3)
            val taps = PixelPipeline.Taps()
            val tmp = DoubleArray(3)
            for (i in 0 until 48) {
                s.pipeline.process(colors[i * 3], colors[i * 3 + 1], colors[i * 3 + 2], taps)
                s.outputLut.sample(taps.cmyFilm[0], taps.cmyFilm[1], taps.cmyFilm[2], tmp)
                for (c in 0..2) expected[i * 3 + c] = tmp[c] + s.glare
            }
            File(out, "look_$name.json").writeText("""{"inputMatrix":${arr(s.inputMatrix)},"tcSize":${s.tcLutSize},"tcLut":${arr(s.tcLut)},
"exposure":${s.exposureScale},"curveX0":${s.curveX0},"curveX1":${s.curveX1},"curveSize":${s.curveSize},
"curves":${arr(s.curves)},"curves0":${arr(s.curves0)},"couplers":${s.couplersActive},"coupler":${arr(s.couplerMatrix)},
"positive":${s.positive},"dmax":${arr(s.densityMax)},"lutN":${s.outputLut.n},"lutLo":${arrd(s.outputLut.lo)},"lutHi":${arrd(s.outputLut.hi)},
"lut":${arr(s.outputLut.values)},"glare":${s.glare},"colors":${arrd(colors)},"expected":${arrd(expected)}}""")
        }
        // polaroid reveal coefficients from the Kotlin port
        File(out, "polaroid_coef.json").writeText((0..10).joinToString(",", "[", "]") { i -> arr(Polaroid.coefficients(i / 10.0)) })
        // grain statistics from the CPU model
        val s = LookSetup.create(Look(film = "kodak_portra_400"), data, 24)
        val g = s.grain
        val stats = ArrayList<String>()
        for (px in listOf(6.0, 25.0)) for (dens in listOf(0.3, 1.0)) {
            val rnd = java.util.Random(2); val d = doubleArrayOf(dens, dens, dens); val o = DoubleArray(3)
            var sum = 0.0; var sq = 0.0; val n = 40000
            repeat(n) { g.apply(d, px, rnd, o); sum += o[1]; sq += o[1] * o[1] }
            val m = sum / n
            stats += """{"px":$px,"d":$dens,"mean":$m,"std":${Math.sqrt(sq / n - m * m)}}"""
        }
        fun m3(x: Array<DoubleArray>) = (0 until 9).map { x[it / 3][it % 3] }.joinToString(",", "[", "]")
        File(out, "grain.json").writeText("""{"table":${arr(g.layerTable)},"size":${g.tableSize},"hi":${arrd(g.tableHi)},
"dmin":${m3(g.layerDensityMin)},"dmax":${m3(g.layerDensityMax)},"ppu":${m3(g.particlesPerUm2)},
"unif":${arrd(g.uniformity)},"densMin":${arrd(g.densityMin)},"stats":[${stats.joinToString(",")}]}""")
    }
}
