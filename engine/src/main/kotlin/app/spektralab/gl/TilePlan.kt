package app.spektralab.gl

import app.spektralab.engine.GaussianMixture
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The renderer's pipeline (FilmRenderer.render) cut into tiles, as a list of GPU passes
 * that the app (FilmRenderer.renderTiled) and the WebGL check (tools/glcheck/tiles.js)
 * both execute. It reproduces the untiled render of the same image size:
 *
 *  - Every pyramid level spans the whole image in uv, as in the untiled render. Levels
 *    0..[tiledLevels] are computed per tile on windows of the level grid; coarser levels
 *    are built once for the whole image (they are small) and sampled by every tile. The
 *    Gaussians of the blur mixtures run on the level the untiled render uses, so wide
 *    halation / diffusion components are global and only the 1-2 octaves of the tiled
 *    levels need a margin.
 *  - The DIR coupler inhibitors are blurred from a pyramid of the coupler amount, which
 *    depends on the blurred exposure everywhere: hence three sweeps over the tiles (global
 *    exposure levels; global coupler levels; the print itself).
 *  - A window is a sub-rectangle of a level's texel grid, clipped to the image, so texture
 *    clamping at the image edges is the untiled one. Each tile pass is computed on windows
 *    that extend [margin] px beyond the tile; [margin] covers every footprint that reaches
 *    the tile's own pixels.
 *  - Grain is seeded by image pixel position and the blur sizes come from the whole image,
 *    so tiles carry no look of their own.
 *
 * Windows and texel rows are in GL orientation (row 0 at the bottom, v up).
 */
class TilePlan private constructor(
    val ops: List<Op>,
    /** Exposure mixture weights folded into the develop pass (as in the untiled render). */
    val idW: FloatArray,
    val fineW: FloatArray,
    val couplers: Boolean,
    val flipY: Boolean,
    val imageWidth: Int,
    val imageHeight: Int,
    val outWidth: Int,
    val outHeight: Int,
    val imageRect: FloatArray,
    /** Overlap of the tile windows, full-resolution px. */
    val margin: Int,
    val tile: Int,
) {
    /** A render target: window [x0, x0 + w) x [y0, y0 + h) of pyramid level [level]. */
    class Buf(val name: String, val level: Int, val x0: Int, val y0: Int, val w: Int, val h: Int)

    /** Affine uv maps are (scale.x, scale.y, offset.x, offset.y): sampled uv = uv * scale + offset. */
    sealed class Op {
        /** Upload the source pixels [x0, x0 + w) x [y0, y0 + h) (source rows top-down, as stored). */
        class Source(val x0: Int, val y0: Int, val w: Int, val h: Int) : Op()
        /** Expose pass into [dst]; [uvTransform] (row-major 3x3) takes its uv to the source window's uv. */
        class Expose(val dst: Buf, val uvTransform: FloatArray) : Op()
        /** 2x reduction of [src] into [viewport], a window inside [dst] (all of it, or a tile core of a global level). */
        class Down(val dst: Buf, val viewport: Buf, val src: Buf, val map: FloatArray) : Op()
        /** One Gaussian octave: separable blur of [src] at [sigma] texels, through [tmp]. */
        class Blur(val dst: Buf, val tmp: Buf, val src: Buf, val sigma: Double) : Op()
        class Term(val src: Buf, val map: FloatArray, val weight: FloatArray)
        /** Weighted sum; a null base is the zero texture. */
        class Combine(val dst: Buf, val base: Buf?, val baseMap: FloatArray, val baseWeight: FloatArray, val terms: List<Term>) : Op()
        /** Coupler amount; [l1] and [s] share [dst]'s window. */
        class Corr(val dst: Buf, val l1: Buf, val s: Buf?, val idW: FloatArray) : Op()
        class Develop(
            val dst: Buf, val raw: Buf, val rawMap: FloatArray, val s: Buf?, val sMap: FloatArray, val cb: Buf?, val cbMap: FloatArray,
            /** One texel of [raw], in its uv. */
            val texel: FloatArray,
        ) : Op()
        /** Print the output pixels [x0, x0 + w) x [y0, y0 + h) (framebuffer rows of the untiled output). */
        class Final(val x0: Int, val y0: Int, val w: Int, val h: Int, val density: Buf?, val outMap: FloatArray, val densMap: FloatArray) : Op()
        class Free(val names: List<String>) : Op()
    }

    companion object {
        /** Same as [Shaders.MAX_COMBINE]: the untiled render combines only that many octaves. */
        private const val MAX_TERMS = Shaders.MAX_COMBINE

        /**
         * @param imageRect image area in the output, uv (x0, y0, x1, y1) with v up; its edges must be on whole pixels
         * @param flipY the output's framebuffer row 0 is its top row (as for a bitmap read back)
         * @param uvTransform row-major 3x3: image uv (v up) -> source texture uv (source row 0 at v = 0)
         * @param expMix exposure blur, [corrMix] coupler blur: computed for the whole image size
         * @param tile tile size, px
         * @param tiledLevels pyramid levels below full resolution computed per tile; coarser ones are global
         * @param marginOverride overlap in px instead of the computed one (checks: what too little overlap does)
         */
        fun build(
            imageWidth: Int, imageHeight: Int, outWidth: Int, outHeight: Int, imageRect: FloatArray, flipY: Boolean,
            sourceWidth: Int, sourceHeight: Int, uvTransform: FloatArray,
            expMix: GaussianMixture, corrMix: GaussianMixture, couplers: Boolean,
            tile: Int, tiledLevels: Int = 2, marginOverride: Int? = null,
        ): TilePlan = Builder(imageWidth, imageHeight, outWidth, outHeight, imageRect, flipY, sourceWidth, sourceHeight, uvTransform,
            expMix, corrMix, couplers, tile, tiledLevels, marginOverride).build()

        /** Number of half-resolution levels FilmRenderer.pyramid makes from a [w] x [h] image. */
        fun pyramidDepth(w: Int, h: Int): Int {
            var cw = w; var ch = h; var i = 0
            while (min(cw, ch) > 6 && i < 12) { cw = (cw + 1) / 2; ch = (ch + 1) / 2; i++ }
            return i
        }

        /** FilmRenderer.blurOctave: the level a Gaussian of [sigma] px runs on, and its sigma there. */
        fun octave(sigma: Double, levels: Int): Pair<Int, Double> {
            val j = floor(ln(sigma) / ln(2.0)).toInt().coerceIn(1, levels)
            val sl = sigma / 2.0.pow(j)
            return j to sqrt(max(sl * sl - 0.11, 0.05))
        }

        /** FilmRenderer.blurPass: texels from the centre the outermost (linearly sampled) tap reads. */
        fun blurReach(sigma: Double): Int = ceil(3 * sigma).toInt().coerceIn(1, 28) + 1
    }

    private class Octave(val index: Int, val level: Int, val sigma: Double, val weight: FloatArray)

    private class Builder(
        val w0: Int, val h0: Int, val ow: Int, val oh: Int, val rect: FloatArray, val flipY: Boolean,
        val sw: Int, val sh: Int, val uvTransform: FloatArray,
        val expMix: GaussianMixture, val corrMix: GaussianMixture, val couplers: Boolean,
        val tile: Int, val g: Int, val marginOverride: Int?,
    ) {
        val ops = ArrayList<Op>()
        val depth = pyramidDepth(w0, h0)
        /** The coupler pyramid starts from the half-resolution level. */
        val cDepth: Int
        val widths: IntArray
        val heights: IntArray

        init {
            require(w0 > 0 && h0 > 0 && ow > 0 && oh > 0 && sw > 0 && sh > 0) { "non-positive dimensions" }
            require(tile >= 16 && g >= 1)
            // the first global level must exist in both pyramids
            require(depth >= g + 1) { "image too small to tile" }
            val n = 14
            widths = IntArray(n); heights = IntArray(n)
            widths[0] = w0; heights[0] = h0
            for (r in 1 until n) { widths[r] = (widths[r - 1] + 1) / 2; heights[r] = (heights[r - 1] + 1) / 2 }
            cDepth = pyramidDepth(widths[1], heights[1])
        }

        /** Size of a level-r texel in full-resolution px (the larger axis). */
        fun texel(r: Int) = max(w0.toDouble() / widths[r], h0.toDouble() / heights[r])

        fun global(name: String, r: Int) = Buf(name, r, 0, 0, widths[r], heights[r])

        /** Level-r window covering full-resolution px [x0, x1) x [y0, y1). */
        fun window(name: String, r: Int, x0: Int, y0: Int, x1: Int, y1: Int): Buf {
            val a = Math.floorDiv(x0.toLong() * widths[r], w0.toLong()).toInt()
            val b = Math.floorDiv(y0.toLong() * heights[r], h0.toLong()).toInt()
            val c = (-Math.floorDiv(-x1.toLong() * widths[r], w0.toLong())).toInt()
            val d = (-Math.floorDiv(-y1.toLong() * heights[r], h0.toLong())).toInt()
            return Buf(name, r, a, b, c - a, d - b)
        }

        /** Uv map from [dst]'s window to [src]'s: both are windows of levels spanning the image. */
        fun map(dst: Buf, src: Buf): FloatArray {
            val nd = widths[dst.level].toDouble(); val ns = widths[src.level].toDouble()
            val md = heights[dst.level].toDouble(); val ms = heights[src.level].toDouble()
            return floatArrayOf(
                (dst.w * ns / (nd * src.w)).toFloat(), (dst.h * ms / (md * src.h)).toFloat(),
                ((dst.x0 * ns / nd - src.x0) / src.w).toFloat(), ((dst.y0 * ms / md - src.y0) / src.h).toFloat(),
            )
        }

        /** First level-r texel whose centre is at or right of full-resolution px edge [x] (core partition). */
        fun firstCentre(x: Int, r: Int, horizontal: Boolean): Int {
            val n0 = (if (horizontal) w0 else h0).toLong()
            val nr = (if (horizontal) widths[r] else heights[r]).toLong()
            // smallest i with (i + 0.5) / nr >= x / n0
            return max(0L, -Math.floorDiv(-(2L * x * nr - n0), 2L * n0)).toInt()
        }

        // ---------------------------------------------------------------- blur octaves (as FilmRenderer.render)
        val idW = FloatArray(3) { expMix.identity[it].toFloat() }
        val fineW = FloatArray(3)
        val eTerms = ArrayList<Octave>()
        val qTerms = ArrayList<Octave>()
        var qBase = 0.0

        fun octaves() {
            for (k in expMix.sigmasPx.indices) {
                val sg = expMix.sigmasPx[k]
                val wk = FloatArray(3) { expMix.weights[k][it].toFloat() }
                if (sg < 1.0) { for (c in 0..2) fineW[c] += wk[c]; continue }
                val (j, s2) = octave(sg, depth)
                eTerms += Octave(k, j, s2, wk)
            }
            if (couplers) {
                var base = 0.0
                for (k in corrMix.sigmasPx.indices) {
                    val sg = corrMix.sigmasPx[k]
                    val wk = corrMix.weights[k][0]
                    if (sg < 1.0) { base += wk; continue }
                    val (j, s2) = octave(sg, 1 + cDepth)
                    qTerms += Octave(k, j, s2, FloatArray(3) { wk.toFloat() })
                }
                base += corrMix.identity[0]
                qBase = base
            }
            // only these are combined by the untiled render
            while (eTerms.size > MAX_TERMS) eTerms.removeAt(eTerms.size - 1)
            while (qTerms.size > MAX_TERMS) qTerms.removeAt(qTerms.size - 1)
        }

        /**
         * Margin: how far (px) from a window's inner edges each buffer may be wrong, built up
         * pass by pass from the footprints of the reads (bilinear reads reach one texel).
         */
        fun margin(): Int {
            val bL = DoubleArray(g + 1)
            for (r in 1..g) bL[r] = bL[r - 1] + texel(r - 1)
            var bS = 0.0
            for (t in eTerms) if (t.level <= g) bS = max(bS, bL[t.level] + blurReach(t.sigma) * texel(t.level) + texel(t.level))
            var need = 0.0
            var bD = max(2 * texel(0), bS + texel(1))
            if (eTerms.any { it.level > g }) need = max(need, bL[g] + texel(g))
            if (couplers) {
                val bC = DoubleArray(g + 1)
                bC[1] = max(bL[1], bS) + texel(1)
                for (r in 2..g) bC[r] = bC[r - 1] + texel(r - 1)
                var bCb = bC[1] + texel(1)
                for (t in qTerms) if (t.level <= g) bCb = max(bCb, bC[t.level] + blurReach(t.sigma) * texel(t.level) + texel(t.level))
                bD = max(bD, bCb + texel(1))
                if (qTerms.any { it.level > g }) need = max(need, bC[g] + texel(g))
            }
            // the print pass reads the developed density one texel (plus filtering) around its pixels
            need = max(need, bD + 2 * texel(0))
            return ceil(need).toInt() + 2
        }

        // ---------------------------------------------------------------- per-tile passes
        var m = 0

        class Windows(val a: Buf, val l: List<Buf>, val c: List<Buf>)

        fun windows(kx0: Int, ky0: Int, kx1: Int, ky1: Int): Windows {
            val x0 = max(0, kx0 - m); val y0 = max(0, ky0 - m)
            val x1 = min(w0, kx1 + m); val y1 = min(h0, ky1 + m)
            return Windows(
                window("A", 0, x0, y0, x1, y1),
                (1..g).map { window("L$it", it, x0, y0, x1, y1) },
                (1..g).map { window("C$it", it, x0, y0, x1, y1) },
            )
        }

        /** Source pixels the expose pass of window [a] samples (bilinear, plus a texel of slack). */
        fun expose(a: Buf) {
            val m3 = uvTransform
            var sx0 = Double.MAX_VALUE; var sx1 = -Double.MAX_VALUE; var sy0 = Double.MAX_VALUE; var sy1 = -Double.MAX_VALUE
            for (cx in 0..1) for (cy in 0..1) {
                val u = (a.x0 + cx * a.w).toDouble() / w0; val v = (a.y0 + cy * a.h).toDouble() / h0
                val s = (m3[0] * u + m3[1] * v + m3[2]) * sw; val t = (m3[3] * u + m3[4] * v + m3[5]) * sh
                sx0 = min(sx0, s); sx1 = max(sx1, s); sy0 = min(sy0, t); sy1 = max(sy1, t)
            }
            val x0 = (floor(sx0).toInt() - 2).coerceIn(0, sw - 1); val x1 = (ceil(sx1).toInt() + 2).coerceIn(x0 + 1, sw)
            val y0 = (floor(sy0).toInt() - 2).coerceIn(0, sh - 1); val y1 = (ceil(sy1).toInt() + 2).coerceIn(y0 + 1, sh)
            ops += Op.Source(x0, y0, x1 - x0, y1 - y0)
            // window uv -> image uv -> source uv -> source window uv
            val toImage = doubleArrayOf(a.w.toDouble() / w0, 0.0, a.x0.toDouble() / w0, 0.0, a.h.toDouble() / h0, a.y0.toDouble() / h0, 0.0, 0.0, 1.0)
            val toWindow = doubleArrayOf(sw.toDouble() / (x1 - x0), 0.0, -x0.toDouble() / (x1 - x0), 0.0, sh.toDouble() / (y1 - y0), -y0.toDouble() / (y1 - y0), 0.0, 0.0, 1.0)
            val t = mul(toWindow, mul(DoubleArray(9) { m3[it].toDouble() }, toImage))
            ops += Op.Expose(a, FloatArray(9) { t[it].toFloat() })
        }

        fun down(dst: Buf, src: Buf, viewport: Buf = dst) { ops += Op.Down(dst, viewport, src, map(viewport, src)) }

        fun blur(prefix: String, t: Octave, src: Buf): Buf {
            val dst = Buf("$prefix${t.index}", src.level, src.x0, src.y0, src.w, src.h)
            ops += Op.Blur(dst, Buf("$prefix${t.index}t", src.level, src.x0, src.y0, src.w, src.h), src, t.sigma)
            return dst
        }

        /** Exposure, the tiled exposure levels, and the blurred exposure S (null: no octaves). */
        fun exposure(w: Windows, levels: Int): Buf? {
            expose(w.a)
            for (r in 1..levels) down(w.l[r - 1], if (r == 1) w.a else w.l[r - 2])
            if (eTerms.isEmpty()) return null
            val sWin = Buf("S", 1, w.l[0].x0, w.l[0].y0, w.l[0].w, w.l[0].h)
            val terms = eTerms.map { t ->
                val src = if (t.level <= g) blur("E", t, w.l[t.level - 1]) else global("gE${t.index}", t.level)
                Op.Term(src, map(sWin, src), t.weight)
            }
            ops += Op.Combine(sWin, null, ZERO_MAP, FloatArray(3), terms)
            return sWin
        }

        fun coupler(w: Windows, s: Buf?): Buf {
            ops += Op.Corr(w.c[0], w.l[0], s, FloatArray(3) { idW[it] + fineW[it] })
            return w.c[0]
        }

        fun build(): TilePlan {
            octaves()
            m = marginOverride ?: margin()
            val eGlobal = eTerms.filter { it.level > g }
            val qGlobal = qTerms.filter { it.level > g }
            val eLocal = eTerms.filter { it.level <= g }.maxOfOrNull { it.level } ?: 0
            val qLocal = qTerms.filter { it.level <= g }.maxOfOrNull { it.level } ?: 1
            // levels a print tile needs: L1 also feeds the coupler pass
            val lPrint = max(eLocal, if (couplers) 1 else 0)
            val imageTiles = ArrayList<IntArray>()
            for (y in 0 until h0 step tile) for (x in 0 until w0 step tile) imageTiles += intArrayOf(x, y, min(w0, x + tile), min(h0, y + tile))

            // sweep 1: the first global exposure level, written core by core
            if (eGlobal.isNotEmpty()) {
                val top = global("gL${g + 1}", g + 1)
                for ((x0, y0, x1, y1) in imageTiles) {
                    val w = windows(x0, y0, x1, y1)
                    expose(w.a)
                    for (r in 1..g) down(w.l[r - 1], if (r == 1) w.a else w.l[r - 2])
                    coreDown(top, w.l[g - 1], x0, y0, x1, y1)
                }
                globalOctaves("gL", "gE", eGlobal)
            }
            // sweep 2: the first global coupler level
            if (couplers && qGlobal.isNotEmpty()) {
                val top = global("gC${g + 1}", g + 1)
                for ((x0, y0, x1, y1) in imageTiles) {
                    val w = windows(x0, y0, x1, y1)
                    coupler(w, exposure(w, max(eLocal, 1)))
                    for (r in 2..g) down(w.c[r - 1], w.c[r - 2])
                    coreDown(top, w.c[g - 1], x0, y0, x1, y1)
                }
                globalOctaves("gC", "gQ", qGlobal)
            }
            // sweep 3: print, one output tile at a time
            val padL = Math.round(rect[0] * ow); val padR = Math.round(rect[2] * ow)
            val padB = Math.round(rect[1] * oh); val padT = Math.round(rect[3] * oh)
            check(padL >= 0 && padB >= 0 && padR <= ow && padT <= oh && padR - padL == w0 && padT - padB == h0) { "image rect off the pixel grid" }
            for (oy in 0 until oh step tile) for (ox in 0 until ow step tile) {
                val tw = min(tile, ow - ox); val th = min(tile, oh - oy)
                val outMap = floatArrayOf(tw.toFloat() / ow, th.toFloat() / oh, ox.toFloat() / ow, oy.toFloat() / oh)
                // image pixels under this tile (rows: see the y flip in the print shader)
                val kx0 = max(0, ox - padL); val kx1 = min(w0, ox + tw - padL)
                val ky0 = max(0, if (flipY) oh - padB - (oy + th) else oy - padB)
                val ky1 = min(h0, if (flipY) oh - padB - oy else oy + th - padB)
                if (kx0 >= kx1 || ky0 >= ky1) {
                    ops += Op.Final(ox, oy, tw, th, null, outMap, ZERO_MAP)
                    continue
                }
                val w = windows(kx0, ky0, kx1, ky1)
                val s = exposure(w, lPrint)
                var cb: Buf? = null
                if (couplers) {
                    val c1 = coupler(w, s)
                    for (r in 2..qLocal) down(w.c[r - 1], w.c[r - 2])
                    val cbWin = Buf("Cb", 1, c1.x0, c1.y0, c1.w, c1.h)
                    val terms = qTerms.map { t ->
                        val src = if (t.level <= g) blur("Q", t, w.c[t.level - 1]) else global("gQ${t.index}", t.level)
                        Op.Term(src, map(cbWin, src), t.weight)
                    }
                    ops += Op.Combine(cbWin, c1, map(cbWin, c1), FloatArray(3) { qBase.toFloat() }, terms)
                    cb = cbWin
                }
                // density for the tile and the print shader's 3x3 grain blur around it
                val d = window("D", 0, max(0, kx0 - 2), max(0, ky0 - 2), min(w0, kx1 + 2), min(h0, ky1 + 2))
                ops += Op.Develop(d, w.a, map(d, w.a), s, s?.let { map(d, it) } ?: ZERO_MAP, cb, cb?.let { map(d, it) } ?: ZERO_MAP,
                    floatArrayOf(1f / w.a.w, 1f / w.a.h))
                ops += Op.Final(ox, oy, tw, th, d,
                    outMap, floatArrayOf(w0.toFloat() / d.w, h0.toFloat() / d.h, -d.x0.toFloat() / d.w, -d.y0.toFloat() / d.h))
            }
            return TilePlan(ops, idW, fineW, couplers, flipY, w0, h0, ow, oh, rect, m, tile)
        }

        /** Write the level-(g+1) texels whose centres lie in the tile core [x0, x1) x [y0, y1). */
        fun coreDown(top: Buf, src: Buf, x0: Int, y0: Int, x1: Int, y1: Int) {
            val r = top.level
            val a = firstCentre(x0, r, true); val b = firstCentre(y0, r, false)
            val c = firstCentre(x1, r, true); val d = firstCentre(y1, r, false)
            if (c <= a || d <= b) return
            down(top, src, Buf(top.name, r, a, b, c - a, d - b))
        }

        /** The rest of a global pyramid (from level g+1) and the octaves on it; then free the pyramid. */
        fun globalOctaves(levelPrefix: String, outPrefix: String, terms: List<Octave>) {
            val deepest = terms.maxOf { it.level }
            for (r in g + 2..deepest) down(global("$levelPrefix$r", r), global("$levelPrefix${r - 1}", r - 1))
            for (t in terms) {
                val src = global("$levelPrefix${t.level}", t.level)
                // one scratch buffer per level is enough: the octaves run one after another
                ops += Op.Blur(global("$outPrefix${t.index}", t.level), global("${levelPrefix}t${t.level}", t.level), src, t.sigma)
            }
            ops += Op.Free((g + 1..deepest).flatMap { listOf("$levelPrefix$it", "${levelPrefix}t$it") })
        }
    }
}

private val ZERO_MAP = floatArrayOf(0f, 0f, 0f, 0f)

private fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
    val r = i / 3; val c = i % 3
    a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
}
