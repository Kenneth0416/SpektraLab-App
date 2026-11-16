package app.spektralab.engine

import java.io.File
import kotlin.math.pow
import kotlin.test.Test

/**
 * Look tables for `tools/promo/render.js`, which runs real photos through the app's
 * shaders in headless Chromium (the same pass order as FilmRenderer.render). Runs only
 * with -Dspektra.promodump=<dir>; reads <dir>/jobs.tsv, one look per line:
 * `name <TAB> longEdgePx <TAB> film=..;paper=..;halation=..;grain=..;border=..` (Look fields).
 */
class PromoDumpTest {
    private val outDir = System.getProperty("spektra.promodump")?.takeIf { it.isNotEmpty() }

    @Test
    fun dump() {
        val out = File(outDir ?: return)
        val assets = File(System.getProperty("spektra.assets"))
        val data = EngineData { File(assets, it).inputStream() }
        fun arr(a: FloatArray) = a.joinToString(",", "[", "]")
        fun arrd(a: DoubleArray) = a.joinToString(",", "[", "]")
        fun m3(x: Array<DoubleArray>) = (0 until 9).map { x[it / 3][it % 3] }.joinToString(",", "[", "]")
        fun mix(m: GaussianMixture) = """{"sigmas":${arrd(m.sigmasPx)},"weights":[${m.weights.joinToString(",") { arrd(it) }}],"identity":${arrd(m.identity)}}"""
        fun encode(v: Double): Double { val c = v.coerceIn(0.0, 1.0); return if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055 }
        for (line in File(out, "jobs.tsv").readLines().filter { it.isNotBlank() }) {
            val (name, edge, spec) = line.split("\t")
            var look = Look()
            for (kv in spec.split(";").filter { it.isNotBlank() }) {
                val (k, v) = kv.split("=")
                look = when (k) {
                    "film" -> look.copy(film = v); "paper" -> look.copy(paper = v)
                    "printEv" -> look.copy(printEv = v.toDouble()); "glare" -> look.copy(glare = v.toDouble())
                    "filmEv" -> look.copy(filmEv = v.toDouble()); "push" -> look.copy(push = v.toInt())
                    "halation" -> look.copy(halation = v.toDouble()); "halationSize" -> look.copy(halationSize = v.toDouble())
                    "grain" -> look.copy(grain = v.toDouble()); "grainSize" -> look.copy(grainSize = v.toDouble())
                    "diffusion" -> look.copy(diffusion = v); "diffusionAmount" -> look.copy(diffusionAmount = v.toDouble())
                    "yFilter" -> look.copy(yFilter = v.toDouble()); "mFilter" -> look.copy(mFilter = v.toDouble())
                    "format" -> look.copy(format = v); "border" -> look.copy(border = v)
                    "dateStamp" -> look.copy(dateStamp = v.toBoolean())
                    else -> error("unknown look field $k")
                }
            }
            // export quality: 48^3 print LUT (ExportManager)
            val s = LookSetup.create(look, data, 48)
            val longEdge = edge.toInt()
            val pixelUm = s.pixelSizeUm(longEdge)
            val expMix = s.exposureBlur(pixelUm, longEdge / 3.0)
            val corrMix = s.couplerBlur(pixelUm, longEdge / 3.0)
            val g = s.grain
            val area = pixelUm * pixelUm
            // Borders.color (app module)
            val border = when {
                look.whiteBorder -> s.paperWhite?.let { w -> DoubleArray(3) { encode(w[it]) } } ?: doubleArrayOf(1.0, 1.0, 1.0)
                look.filmEdge -> DoubleArray(3) { encode(s.rebateColor[it]) }
                else -> doubleArrayOf(0.0, 0.0, 0.0)
            }
            File(out, "look_$name.json").writeText("""{"film":"${look.film}","paper":"${LookMapper.resolvePaper(look, data)}","borderKind":"${look.border}",
"inputMatrix":${arr(s.inputMatrix)},"tcSize":${s.tcLutSize},"tcLut":${arr(s.tcLut)},"exposure":${s.exposureScale},
"curveX0":${s.curveX0},"curveX1":${s.curveX1},"curveSize":${s.curveSize},"curves":${arr(s.curves)},"curves0":${arr(s.curves0)},
"couplers":${s.couplersActive},"coupler":${arr(s.couplerMatrix)},"positive":${s.positive},"dmax":${arr(s.densityMax)},
"lutN":${s.outputLut.n},"lutLo":${arrd(s.outputLut.lo)},"lutHi":${arrd(s.outputLut.hi)},"lut":${arr(s.outputLut.values)},"glare":${s.glare},
"border":${arrd(border)},
"grain":{"active":${g.active},"amount":${g.amount},"blurPx":${g.blurPx},"table":${arr(g.layerTable)},"size":${g.tableSize},"hi":${arrd(g.tableHi)},
"dmin":${m3(g.layerDensityMin)},"dmax":${m3(g.layerDensityMax)},"n":${(0 until 9).map { g.particlesPerUm2[it / 3][it % 3] * area }.joinToString(",", "[", "]")},
"unif":${arrd(g.uniformity)},"densMin":${arrd(g.densityMin)}},
"expMix":${mix(expMix)},"corrMix":${mix(corrMix)}}""")
        }
        // shaders, as GlDumpTest
        mapOf("vertex" to app.spektralab.gl.Shaders.VERTEX, "expose" to app.spektralab.gl.Shaders.EXPOSE, "down" to app.spektralab.gl.Shaders.DOWN,
            "blur" to app.spektralab.gl.Shaders.BLUR, "combine" to app.spektralab.gl.Shaders.COMBINE, "corr" to app.spektralab.gl.Shaders.CORR,
            "develop" to app.spektralab.gl.Shaders.DEVELOP, "final" to app.spektralab.gl.Shaders.FINAL)
            .forEach { (k, v) -> File(out, "$k.glsl").writeText(v) }
    }
}
