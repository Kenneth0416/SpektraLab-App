package app.spektralab.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30.*
import app.spektralab.engine.GaussianMixture
import app.spektralab.engine.LookSetup
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Source image for a render: a photo (sRGB texture) or a video frame (external OES texture). */
class InputImage(
    val texture: Int,
    val external: Boolean,
    /** Row-major 3x3: image uv (y up, top = 1) -> texture uv. */
    val uvTransform: FloatArray,
)

class RenderRequest(
    val input: InputImage,
    /** Size of the processed image area, pixels. */
    val imageWidth: Int,
    val imageHeight: Int,
    val framebuffer: Int,
    val outWidth: Int,
    val outHeight: Int,
    /** Image area inside the output, uv (x0, y0, x1, y1), y up. */
    val imageRect: FloatArray = floatArrayOf(0f, 0f, 1f, 1f),
    val border: FloatArray = floatArrayOf(0.05f, 0.04f, 0.035f),
    val overlayTexture: Int = 0,
    val flipY: Boolean = false,
    val original: Boolean = false,
    val seed: Int = 1,
    /** Auto exposure (EV) measured on the source image. */
    val autoEv: Float = 0f,
)

/**
 * GLES 3.0 implementation of the spektrafilm pipeline (see [Shaders]). One
 * instance per GL context; call everything on that context's thread.
 */
class FilmRenderer {
    private val quad = FullscreenQuad()
    private val pExpose = Program(Shaders.VERTEX, Shaders.EXPOSE)
    private val pExposeExt = Program(Shaders.VERTEX, Shaders.EXPOSE_EXTERNAL)
    private val pDown = Program(Shaders.VERTEX, Shaders.DOWN)
    private val pBlur = Program(Shaders.VERTEX, Shaders.BLUR)
    private val pCombine = Program(Shaders.VERTEX, Shaders.COMBINE)
    private val pCorr = Program(Shaders.VERTEX, Shaders.CORR)
    private val pDevelop = Program(Shaders.VERTEX, Shaders.DEVELOP)
    private val pFinal = Program(Shaders.VERTEX, Shaders.FINAL)
    private val pFinalExt = Program(Shaders.VERTEX, Shaders.FINAL_EXTERNAL)

    private val texTc = GlUtil.texture2d()
    private val texCurves = GlUtil.texture2d()
    private val texCurves0 = GlUtil.texture2d()
    private val texLayers = GlUtil.texture2d()
    private val texZero = GlUtil.texture2d().also {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, 1, 1, 0, GL_RGBA, GL_HALF_FLOAT, GlUtil.halfBuffer(floatArrayOf(0f, 0f, 0f, 0f), 4, 4))
    }
    private val texLut: Int = IntArray(1).also { glGenTextures(1, it, 0) }[0].also {
        glBindTexture(GL_TEXTURE_3D, it)
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE)
    }

    var setup: LookSetup? = null
        private set

    private val targets = HashMap<String, RenderTarget>()
    private var mixKey: String? = null
    private var expMix: GaussianMixture = GaussianMixture.IDENTITY
    private var corrMix: GaussianMixture = GaussianMixture.IDENTITY

    /** Upload the look tables (spectral LUTs, curves, grain tables). */
    fun setLook(s: LookSetup) {
        if (s === setup) return
        GlUtil.uploadHalfTexture(texTc, s.tcLut, s.tcLutSize, s.tcLutSize, 3)
        GlUtil.uploadHalfTexture(texCurves, s.curves, s.curveSize, 1, 3)
        GlUtil.uploadHalfTexture(texCurves0, s.curves0, s.curveSize, 1, 3)
        GlUtil.uploadHalfTexture(texLayers, s.grain.layerTable, s.grain.tableSize, 3, 3)
        val n = s.outputLut.n
        glBindTexture(GL_TEXTURE_3D, texLut)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glTexImage3D(GL_TEXTURE_3D, 0, GL_RGBA16F, n, n, n, 0, GL_RGBA, GL_HALF_FLOAT,
            GlUtil.halfBuffer(s.outputLut.values, 3, 4))
        setup = s
        mixKey = null
        GlUtil.checkError("setLook")
    }

    private fun target(name: String, w: Int, h: Int, format: Int = GL_RGBA16F): RenderTarget {
        val t = targets[name]
        if (t != null && t.width == w && t.height == h && t.internalFormat == format) return t
        t?.release()
        return RenderTarget(w, h, format).also { targets[name] = it }
    }

    /** Free intermediate buffers (large at full resolution). */
    fun releaseTargets() {
        targets.values.forEach { it.release() }
        targets.clear()
    }

    fun render(r: RenderRequest) {
        val s = setup ?: return
        val w = r.imageWidth
        val h = r.imageHeight
        glDisable(GL_BLEND)
        glDisable(GL_DEPTH_TEST)
        if (r.original) {
            drawFinal(r, s, 0)
            return
        }
        val pixelUm = s.pixelSizeUm(max(w, h))
        val key = "%.4f/%d/%d".format(pixelUm, w, h)
        if (key != mixKey) {
            val maxSigma = max(w, h) / 3.0
            expMix = s.exposureBlur(pixelUm, maxSigma)
            corrMix = s.couplerBlur(pixelUm, maxSigma)
            mixKey = key
        }

        // 1. expose
        val a = target("A", w, h)
        a.bind()
        val pe = if (r.input.external) pExposeExt else pExpose
        pe.use()
        if (r.input.external) pe.texture("uInput", 0, r.input.texture, GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        else pe.texture("uInput", 0, r.input.texture)
        pe.setMat3RowMajor("uUvTransform", r.input.uvTransform)
        pe.setMat3RowMajor("uToXyz", s.inputMatrix)
        pe.texture("uTcLut", 1, texTc)
        pe.set1f("uTcSize", s.tcLutSize.toFloat())
        pe.set1f("uExposure", s.exposureScale * 2f.pow(r.autoEv))
        quad.draw()

        // 2. pyramid + exposure blur (halation, scatter, diffusion filter)
        val levels = pyramid("L", a)
        val l1 = levels[0]
        val idW = FloatArray(3) { expMix.identity[it].toFloat() }
        val fineW = FloatArray(3)
        val blurred = ArrayList<Pair<RenderTarget, FloatArray>>()
        for (k in expMix.sigmasPx.indices) {
            val sg = expMix.sigmasPx[k]
            val wk = FloatArray(3) { expMix.weights[k][it].toFloat() }
            if (sg < 1.0) { for (c in 0..2) fineW[c] += wk[c]; continue }
            blurred += blurOctave("E$k", levels, sg) to wk
        }
        val sTex = if (blurred.isEmpty()) texZero else {
            val sT = target("S", l1.width, l1.height)
            combine(sT, texZero, FloatArray(3), blurred)
            sT.texture
        }

        // 3. DIR coupler inhibitors (half resolution, diffused)
        var cbTex = texZero
        if (s.couplersActive) {
            val c1 = target("C1", l1.width, l1.height)
            c1.bind()
            pCorr.use()
            pCorr.texture("uL1", 0, l1.texture)
            pCorr.texture("uS", 1, sTex)
            pCorr.set3f("uIdW", idW[0] + fineW[0], idW[1] + fineW[1], idW[2] + fineW[2])
            bindCurves(pCorr, s, 2)
            pCorr.setMat3ColumnMajor("uCoupler", s.couplerMatrix)
            pCorr.set3f("uDmax", s.densityMax[0], s.densityMax[1], s.densityMax[2])
            pCorr.set1i("uPositive", if (s.positive) 1 else 0)
            quad.draw()
            val cLevels = listOf(c1) + pyramid("CL", c1).drop(0)
            var base = 0.0
            val cBlur = ArrayList<Pair<RenderTarget, FloatArray>>()
            for (k in corrMix.sigmasPx.indices) {
                val sg = corrMix.sigmasPx[k]
                val wk = corrMix.weights[k][0]
                if (sg < 1.0) { base += wk; continue }
                cBlur += blurOctave("Q$k", cLevels, sg) to FloatArray(3) { wk.toFloat() }
            }
            base += corrMix.identity[0]
            val cb = target("Cb", l1.width, l1.height)
            combine(cb, c1.texture, FloatArray(3) { base.toFloat() }, cBlur)
            cbTex = cb.texture
        }

        // 4. develop + grain
        val d = target("D", w, h)
        d.bind()
        val pd = pDevelop
        pd.use()
        pd.texture("uRaw", 0, a.texture)
        pd.texture("uS", 1, sTex)
        pd.texture("uCb", 2, cbTex)
        bindCurves(pd, s, 3)
        pd.set3f("uIdW", idW[0], idW[1], idW[2])
        pd.set3f("uFineW", fineW[0], fineW[1], fineW[2])
        pd.set2f("uTexel", 1f / w, 1f / h)
        pd.set1i("uCouplers", if (s.couplersActive) 1 else 0)
        val g = s.grain
        pd.set1i("uGrain", if (g.active) 1 else 0)
        if (g.active) {
            pd.texture("uLayers", 5, texLayers)
            pd.set1f("uGrainAmount", g.amount.toFloat())
            pd.set3f("uTableHi", g.tableHi[0].toFloat(), g.tableHi[1].toFloat(), g.tableHi[2].toFloat())
            val area = pixelUm * pixelUm
            pd.set3fv("uLayerDmin", FloatArray(9) { g.layerDensityMin[it / 3][it % 3].toFloat() })
            pd.set3fv("uLayerDmax", FloatArray(9) { g.layerDensityMax[it / 3][it % 3].toFloat() })
            pd.set3fv("uLayerN", FloatArray(9) { (g.particlesPerUm2[it / 3][it % 3] * area).toFloat() })
            pd.set3f("uUniformity", g.uniformity[0].toFloat(), g.uniformity[1].toFloat(), g.uniformity[2].toFloat())
            pd.set3f("uDmin", g.densityMin[0].toFloat(), g.densityMin[1].toFloat(), g.densityMin[2].toFloat())
            pd.set1ui("uSeed", r.seed)
        }
        quad.draw()

        // 5. print + scan + display
        drawFinal(r, s, d.texture)
        GlUtil.checkError("render")
    }

    private fun bindCurves(p: Program, s: LookSetup, unit: Int) {
        p.texture("uCurves", unit, texCurves)
        p.texture("uCurves0", unit + 1, texCurves0)
        p.set2f("uCurveAxis", s.curveX0, s.curveX1)
        p.set1f("uCurveSize", s.curveSize.toFloat())
    }

    private fun drawFinal(r: RenderRequest, s: LookSetup, density: Int) {
        glBindFramebuffer(GL_FRAMEBUFFER, r.framebuffer)
        glViewport(0, 0, r.outWidth, r.outHeight)
        val p = if (r.original && r.input.external) pFinalExt else pFinal
        p.use()
        if (r.original) {
            if (r.input.external) p.texture("uInput", 0, r.input.texture, GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
            else p.texture("uInput", 0, r.input.texture)
            p.setMat3RowMajor("uUvTransform", r.input.uvTransform)
        } else {
            p.texture("uDensity", 1, density)
            p.texture("uLut", 2, texLut, GL_TEXTURE_3D)
            p.set3f("uLutLo", s.outputLut.lo[0].toFloat(), s.outputLut.lo[1].toFloat(), s.outputLut.lo[2].toFloat())
            p.set3f("uLutHi", s.outputLut.hi[0].toFloat(), s.outputLut.hi[1].toFloat(), s.outputLut.hi[2].toFloat())
            p.set1f("uLutSize", s.outputLut.n.toFloat())
            p.set1f("uGlare", s.glare)
            p.set2f("uTexel", 1f / r.imageWidth, 1f / r.imageHeight)
            val sigma = if (s.grain.active) s.grain.blurPx else 0.0
            p.set3fv("uGrainBlur", grainBlurWeights(sigma))
        }
        p.set1i("uOriginal", if (r.original) 1 else 0)
        p.set4f("uImageRect", r.imageRect[0], r.imageRect[1], r.imageRect[2], r.imageRect[3])
        p.set3f("uBorder", r.border[0], r.border[1], r.border[2])
        p.set1i("uFlipY", if (r.flipY) 1 else 0)
        p.set1i("uOverlay", if (r.overlayTexture != 0) 1 else 0)
        if (r.overlayTexture != 0) p.texture("uOverlayTex", 3, r.overlayTexture)
        quad.draw()
    }

    /** Pyramid of half-resolution reductions of [src]; element 0 is half size. */
    private fun pyramid(prefix: String, src: RenderTarget): List<RenderTarget> {
        val out = ArrayList<RenderTarget>()
        var cur = src
        var i = 0
        while (min(cur.width, cur.height) > 6 && i < 12) {
            val t = target("$prefix$i", (cur.width + 1) / 2, (cur.height + 1) / 2)
            t.bind()
            pDown.use()
            pDown.texture("uSrc", 0, cur.texture)
            quad.draw()
            out += t
            cur = t
            i++
        }
        if (out.isEmpty()) out += src
        return out
    }

    /**
     * Gaussian of [sigma] (full-resolution pixels) evaluated on the pyramid:
     * levels[j-1] has 2^j reduction; we blur at the level where sigma is 1..2 texels.
     */
    private fun blurOctave(name: String, levels: List<RenderTarget>, sigma: Double): RenderTarget {
        val j = floor(ln(sigma) / ln(2.0)).toInt().coerceIn(1, levels.size)
        val src = levels[j - 1]
        val sl = sigma / 2.0.pow(j)
        val s2 = sqrt(max(sl * sl - 0.11, 0.05))
        val tmp = target("${name}t", src.width, src.height)
        val dst = target(name, src.width, src.height)
        blurPass(src.texture, tmp, s2, 1f / src.width, 0f)
        blurPass(tmp.texture, dst, s2, 0f, 1f / src.height)
        return dst
    }

    private fun blurPass(src: Int, dst: RenderTarget, sigma: Double, dx: Float, dy: Float) {
        val radius = ceil(3 * sigma).toInt().coerceIn(1, 28)
        val g = DoubleArray(radius + 2) { if (it <= radius) exp(-it * it / (2 * sigma * sigma)) else 0.0 }
        val norm = g[0] + 2 * (1..radius).sumOf { g[it] }
        val offsets = FloatArray(16)
        val weights = FloatArray(16)
        weights[0] = (g[0] / norm).toFloat()
        var taps = 1
        var k = 1
        while (k <= radius && taps < 16) {
            val w = g[k] + g[k + 1]
            offsets[taps] = ((k * g[k] + (k + 1) * g[k + 1]) / w).toFloat()
            weights[taps] = (w / norm).toFloat()
            taps++
            k += 2
        }
        dst.bind()
        pBlur.use()
        pBlur.texture("uSrc", 0, src)
        pBlur.set2f("uStep", dx, dy)
        pBlur.set1i("uTaps", taps)
        pBlur.set1fv("uOffsets", offsets)
        pBlur.set1fv("uWeights", weights)
        quad.draw()
    }

    private fun combine(dst: RenderTarget, base: Int, baseW: FloatArray, terms: List<Pair<RenderTarget, FloatArray>>) {
        dst.bind()
        pCombine.use()
        pCombine.texture("uBase", 0, base)
        pCombine.set3f("uBaseW", baseW[0], baseW[1], baseW[2])
        val n = min(terms.size, Shaders.MAX_COMBINE)
        pCombine.set1i("uCount", n)
        val w = FloatArray(Shaders.MAX_COMBINE * 3)
        for (i in 0 until n) {
            pCombine.texture("uT$i", i + 1, terms[i].first.texture)
            for (c in 0..2) w[i * 3 + c] = terms[i].second[c]
        }
        for (i in n until Shaders.MAX_COMBINE) pCombine.texture("uT$i", i + 1, texZero)
        pCombine.set3fv("uW", w)
        quad.draw()
    }

    fun release() {
        releaseTargets()
        listOf(pExpose, pExposeExt, pDown, pBlur, pCombine, pCorr, pDevelop, pFinal, pFinalExt).forEach { it.release() }
        listOf(texTc, texCurves, texCurves0, texLayers, texZero, texLut).forEach { GlUtil.deleteTexture(it) }
        quad.release()
    }

    companion object {
        /** 3x3 Gaussian (center, edge, corner) weights for the grain blur. */
        fun grainBlurWeights(sigma: Double): FloatArray {
            if (sigma <= 0.0) return floatArrayOf(1f, 0f, 0f)
            val g1 = exp(-1 / (2 * sigma * sigma))
            val n = 1 + 2 * g1
            val w0 = 1 / n
            val w1 = g1 / n
            return floatArrayOf((w0 * w0).toFloat(), (w0 * w1).toFloat(), (w1 * w1).toFloat())
        }

        /** Row-major 3x3 uv transform helpers. */
        fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

        fun multiply(a: FloatArray, b: FloatArray): FloatArray = FloatArray(9) { i ->
            val r = i / 3; val c = i % 3
            a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
        }
    }
}
