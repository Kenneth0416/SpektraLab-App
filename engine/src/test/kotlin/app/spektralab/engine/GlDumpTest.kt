package app.spektralab.engine

import app.spektralab.gl.Shaders
import app.spektralab.gl.TilePlan
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
            "ground_glass_down" to Shaders.GROUND_GLASS_DOWN, "ground_glass" to Shaders.GROUND_GLASS,
            "combine" to Shaders.COMBINE, "corr" to Shaders.CORR, "develop" to Shaders.DEVELOP, "final" to Shaders.FINAL,
            "polaroid" to Shaders.POLAROID)
            .forEach { (k, v) -> File(out, "$k.glsl").writeText(v) }
        mapOf("down" to Shaders.DOWN, "combine" to Shaders.COMBINE, "develop" to Shaders.DEVELOP, "final" to Shaders.FINAL)
            .forEach { (k, v) -> File(out, "${k}_tiled.glsl").writeText(Shaders.tiled(v)) }
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
        dumpTiles(out, data)
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

    /**
     * Tiled renders for tools/glcheck/tiles.js: the look tables, the blur mixtures the
     * untiled render would use, and the [TilePlan] the app would execute, per case.
     */
    private fun dumpTiles(out: File, data: EngineData) {
        fun arr(a: FloatArray) = a.joinToString(",", "[", "]")
        fun arrd(a: DoubleArray) = a.joinToString(",", "[", "]")
        fun m3(x: Array<DoubleArray>) = (0 until 9).map { x[it / 3][it % 3] }.joinToString(",", "[", "]")
        fun mix(m: GaussianMixture) = """{"sigmas":${arrd(m.sigmasPx)},"weights":[${m.weights.joinToString(",") { arrd(it) }}],"identity":${arrd(m.identity)}}"""
        fun buf(b: TilePlan.Buf?) = if (b == null) "null" else """{"n":"${b.name}","l":${b.level},"x":${b.x0},"y":${b.y0},"w":${b.w},"h":${b.h}}"""
        fun op(o: TilePlan.Op): String = when (o) {
            is TilePlan.Op.Source -> """{"op":"Source","x":${o.x0},"y":${o.y0},"w":${o.w},"h":${o.h}}"""
            is TilePlan.Op.Expose -> """{"op":"Expose","dst":${buf(o.dst)},"uv":${arr(o.uvTransform)}}"""
            is TilePlan.Op.Down -> """{"op":"Down","dst":${buf(o.dst)},"vp":${buf(o.viewport)},"src":${buf(o.src)},"map":${arr(o.map)}}"""
            is TilePlan.Op.Blur -> """{"op":"Blur","dst":${buf(o.dst)},"tmp":${buf(o.tmp)},"src":${buf(o.src)},"sigma":${o.sigma}}"""
            is TilePlan.Op.Combine -> """{"op":"Combine","dst":${buf(o.dst)},"base":${buf(o.base)},"baseMap":${arr(o.baseMap)},"baseW":${arr(o.baseWeight)},"terms":[${
                o.terms.joinToString(",") { """{"src":${buf(it.src)},"map":${arr(it.map)},"w":${arr(it.weight)}}""" }}]}"""
            is TilePlan.Op.Corr -> """{"op":"Corr","dst":${buf(o.dst)},"l1":${buf(o.l1)},"s":${buf(o.s)},"idW":${arr(o.idW)}}"""
            is TilePlan.Op.Develop -> """{"op":"Develop","dst":${buf(o.dst)},"raw":${buf(o.raw)},"rawMap":${arr(o.rawMap)},"s":${buf(o.s)},"sMap":${arr(o.sMap)},"cb":${buf(o.cb)},"cbMap":${arr(o.cbMap)},"texel":${arr(o.texel)}}"""
            is TilePlan.Op.Final -> """{"op":"Final","x":${o.x0},"y":${o.y0},"w":${o.w},"h":${o.h},"density":${buf(o.density)},"outMap":${arr(o.outMap)},"densMap":${arr(o.densMap)}}"""
            is TilePlan.Op.Free -> """{"op":"Free","names":[${o.names.joinToString(",") { "\"$it\"" }}]}"""
        }
        class Case(val name: String, val look: Look, val w: Int, val h: Int, val pad: IntArray, val tile: Int, val levels: Int = 2,
                   val pixelEdge: Int = 0, val flipY: Boolean = true, val margin: Int? = null)
        val halo = Look(film = "kodak_portra_400", halation = 2.0, halationSize = 1.5, diffusion = "cinebloom", diffusionAmount = 1.0, grain = 1.5)
        val cases = listOf(
            // odd sizes (non-2x pyramid steps), white border, tiles smaller than the overlap
            Case("halo", halo, 701, 467, intArrayOf(21, 21, 21, 21), 96),
            // blur sizes of a 50 MP frame (8160 px): wide octaves on every level; border-only tiles
            Case("wide", halo, 701, 467, intArrayOf(150, 40, 150, 40), 128, pixelEdge = 8160),
            Case("wide_g1", halo, 701, 467, intArrayOf(150, 40, 150, 40), 160, levels = 1, pixelEdge = 8160),
            // slide film (positive), different diffusion family, no flip
            Case("velvia", Look(film = "fujifilm_velvia_100", diffusion = "black_pro_mist", diffusionAmount = 0.8), 640, 427, intArrayOf(0, 0, 0, 0), 200,
                pixelEdge = 4000, flipY = false),
            // control: the same plan with too little overlap must show seams
            Case("wide_m8", halo, 701, 467, intArrayOf(150, 40, 150, 40), 128, pixelEdge = 8160, margin = 8),
        )
        for (c in cases) {
            val s = LookSetup.create(c.look, data, 36)
            // a 4:3 source center-cropped to the image aspect (Framing.bitmapUvTransform)
            val sw = 960; val sh = 720
            val ch = (sw.toDouble() / sh) / (c.w.toDouble() / c.h)
            val crop = floatArrayOf(0f, ((1 - ch) / 2).toFloat(), 1f, ((1 + ch) / 2).toFloat())
            val uv = floatArrayOf(crop[2] - crop[0], 0f, crop[0], 0f, -(crop[3] - crop[1]), 1f - crop[1], 0f, 0f, 1f)
            val (pl, pb, pr, pt) = c.pad
            val ow = c.w + pl + pr; val oh = c.h + pb + pt
            val rect = floatArrayOf(pl.toFloat() / ow, pb.toFloat() / oh, (pl + c.w).toFloat() / ow, (pb + c.h).toFloat() / oh)
            val pixelUm = s.pixelSizeUm(if (c.pixelEdge > 0) c.pixelEdge else maxOf(c.w, c.h))
            val maxSigma = maxOf(c.w, c.h) / 3.0
            val expMix = s.exposureBlur(pixelUm, maxSigma)
            val corrMix = s.couplerBlur(pixelUm, maxSigma)
            val plan = TilePlan.build(c.w, c.h, ow, oh, rect, c.flipY, sw, sh, uv, expMix, corrMix, s.couplersActive, c.tile, c.levels, c.margin)
            val g = s.grain
            val area = pixelUm * pixelUm
            File(out, "tiles_${c.name}.json").writeText("""{"W":${c.w},"H":${c.h},"ow":$ow,"oh":$oh,"rect":${arr(rect)},"flipY":${c.flipY},
"sw":$sw,"sh":$sh,"uv":${arr(uv)},"seed":12345,"tile":${c.tile},"levels":${c.levels},"margin":${plan.margin},
"inputMatrix":${arr(s.inputMatrix)},"tcSize":${s.tcLutSize},"tcLut":${arr(s.tcLut)},"exposure":${s.exposureScale},
"curveX0":${s.curveX0},"curveX1":${s.curveX1},"curveSize":${s.curveSize},"curves":${arr(s.curves)},"curves0":${arr(s.curves0)},
"couplers":${s.couplersActive},"coupler":${arr(s.couplerMatrix)},"positive":${s.positive},"dmax":${arr(s.densityMax)},
"lutN":${s.outputLut.n},"lutLo":${arrd(s.outputLut.lo)},"lutHi":${arrd(s.outputLut.hi)},"lut":${arr(s.outputLut.values)},"glare":${s.glare},
"border":[0.9,0.88,0.85],
"grain":{"active":${g.active},"amount":${g.amount},"blurPx":${g.blurPx},"table":${arr(g.layerTable)},"size":${g.tableSize},"hi":${arrd(g.tableHi)},
"dmin":${m3(g.layerDensityMin)},"dmax":${m3(g.layerDensityMax)},"n":${(0 until 9).map { g.particlesPerUm2[it / 3][it % 3] * area }.joinToString(",", "[", "]")},
"unif":${arrd(g.uniformity)},"densMin":${arrd(g.densityMin)}},
"expMix":${mix(expMix)},"corrMix":${mix(corrMix)},
"ops":[${plan.ops.joinToString(",\n") { op(it) }}]}""")
        }
    }
}
