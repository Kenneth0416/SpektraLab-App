package app.spektralab.raw

import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Multi-frame merge of a handheld raw burst by kernel regression, after
 * Wronski et al., "Handheld Multi-Frame Super-Resolution" (SIGGRAPH 2019), the method
 * behind Night Sight, written from the published description:
 *
 *  1. Each frame is aligned to the reference: coarse-to-fine block matching on a
 *     grey pyramid, then Lucas-Kanade refinement to sub-pixel accuracy.
 *  2. A robustness map per frame tells where the aligned frame agrees with the
 *     reference within what the sensor's noise (and local texture) can explain;
 *     elsewhere (moving things, occlusions, failed alignment) the frame is ignored.
 *  3. Every output pixel gathers the raw red, green and blue samples that land near it
 *     from all frames, weighted by an anisotropic Gaussian kernel shaped by the local
 *     structure of the reference: elongated along edges, small on detail, wide on
 *     flat noisy areas. Hand shake puts the samples at different sub-pixel positions,
 *     so this replaces demosaicing and reduces noise at the same time.
 *
 * The result is demosaiced camera RGB, still scene-linear and not white balanced.
 */

/** Motion of a frame relative to the reference on a grid of tiles, in raw pixels. */
class FlowField(val tilesX: Int, val tilesY: Int, val stride: Int, val origin: Int, val u: FloatArray, val v: FloatArray)

/** Grey image at half the raw resolution: one value per Bayer cell. */
internal class Gray(val w: Int, val h: Int, val v: FloatArray)

object BurstAlign {
    private fun gray(img: DngImage): Gray {
        val w = img.cropWidth / 2; val h = img.cropHeight / 2
        val out = FloatArray(w * h)
        val scale = FloatArray(4) { 1f / (img.white - img.black[it]).coerceAtLeast(1f) }
        IntStream.range(0, h).parallel().forEach { y ->
            for (x in 0 until w) {
                var s = 0f
                for (pos in 0 until 4) {
                    val i = (img.cropY + y * 2 + pos / 2) * img.rawWidth + img.cropX + x * 2 + pos % 2
                    s += (((img.data[i].toInt() and 0xffff) - img.black[pos]) * scale[pos]).coerceIn(0f, 1f)
                }
                out[y * w + x] = s * 0.25f
            }
        }
        return Gray(w, h, out)
    }

    private fun down(g: Gray, f: Int): Gray {
        val w = max(1, g.w / f); val h = max(1, g.h / f)
        val out = FloatArray(w * h)
        val inv = 1f / (f * f)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (dy in 0 until f) for (dx in 0 until f) s += g.v[(y * f + dy) * g.w + x * f + dx]
            out[y * w + x] = s * inv
        }
        return Gray(w, h, out)
    }

    internal class Pyramid(val levels: List<Gray>)

    internal fun pyramid(img: DngImage): Pyramid {
        val l0 = gray(img)
        val l1 = down(l0, 2); val l2 = down(l1, 4); val l3 = down(l2, 4)
        return Pyramid(listOf(l0, l1, l2, l3))
    }

    private val FACTOR = intArrayOf(1, 2, 8, 32)       // level size relative to level 0
    private val TILE = intArrayOf(16, 16, 16, 8)
    private val RADIUS = intArrayOf(1, 2, 4, 4)
    /** Search offsets, nearest first: the likely match comes early and the others stop early. */
    private val ORDER = Array(5) { r ->
        val list = ArrayList<IntArray>()
        for (dv in -r..r) for (du in -r..r) list += intArrayOf(du, dv)
        list.sortedBy { it[0] * it[0] + it[1] * it[1] }.toTypedArray()
    }

    /** Flow of [alt] relative to [ref]: ref(x) matches alt(x + flow). */
    internal fun align(ref: Pyramid, alt: Pyramid): FlowField {
        var prevU: IntArray? = null; var prevV: IntArray? = null
        var prevNx = 0; var prevNy = 0; var prevStride = 1
        for (lv in 3 downTo 0) {
            val r = ref.levels[lv]; val a = alt.levels[lv]
            val t = min(TILE[lv], min(r.w, r.h))
            val stride = max(1, t / 2)
            val nx = max(1, (r.w - t) / stride + 1); val ny = max(1, (r.h - t) / stride + 1)
            val u = IntArray(nx * ny); val v = IntArray(nx * ny)
            val up = if (lv == 3) 1 else FACTOR[lv + 1] / FACTOR[lv]
            val pu = prevU; val pv = prevV; val pnx = prevNx; val pny = prevNy; val ps = prevStride
            val rad = RADIUS[lv]
            IntStream.range(0, ny).parallel().forEach { ty ->
                for (tx in 0 until nx) {
                    val x0 = tx * stride; val y0 = ty * stride
                    // start from the coarser level's answer for the tile covering this one
                    var gu = 0; var gv = 0
                    if (pu != null && pv != null) {
                        val px = ((x0 + t / 2) / up / ps).coerceIn(0, pnx - 1)
                        val py = ((y0 + t / 2) / up / ps).coerceIn(0, pny - 1)
                        gu = pu[py * pnx + px] * up; gv = pv[py * pnx + px] * up
                    }
                    var best = Float.MAX_VALUE; var bu = gu; var bv = gv
                    for (o in ORDER[rad]) {
                        val du = o[0]; val dv = o[1]
                        val ax = x0 + gu + du; val ay = y0 + gv + dv
                        if (ax < 0 || ay < 0 || ax + t > a.w || ay + t > a.h) continue
                        var d = 0f
                        var yy = 0
                        while (yy < t && d < best) {
                            val ri = (y0 + yy) * r.w + x0; val ai = (ay + yy) * a.w + ax
                            for (xx in 0 until t) { val e = r.v[ri + xx] - a.v[ai + xx]; d += e * e }
                            yy++
                        }
                        // prefer the smaller motion on ties (flat tiles)
                        d += 1e-9f * (du * du + dv * dv)
                        if (d < best) { best = d; bu = gu + du; bv = gv + dv }
                    }
                    u[ty * nx + tx] = bu; v[ty * nx + tx] = bv
                }
            }
            prevU = u; prevV = v; prevNx = nx; prevNy = ny; prevStride = stride
        }
        // sub-pixel refinement on the finest level (Lucas-Kanade, translation only)
        val r = ref.levels[0]; val a = alt.levels[0]
        val t = min(TILE[0], min(r.w, r.h)); val stride = max(1, t / 2)
        val nx = prevNx; val ny = prevNy
        val fu = FloatArray(nx * ny); val fv = FloatArray(nx * ny)
        val iu = prevU!!; val iv = prevV!!
        val margin = 0
        IntStream.range(0, ny).parallel().forEach { ty ->
            for (tx in 0 until nx) {
                val xa = max(tx * stride - margin, 1); val ya = max(ty * stride - margin, 1)
                val xb = min(tx * stride + t + margin, r.w - 1); val yb = min(ty * stride + t + margin, r.h - 1)
                var su = iu[ty * nx + tx].toFloat(); var sv = iv[ty * nx + tx].toFloat()
                // structure of the reference window
                var axx = 0f; var axy = 0f; var ayy = 0f
                for (yy in ya until yb) for (xx in xa until xb) {
                    val i = yy * r.w + xx
                    val gx = (r.v[i + 1] - r.v[i - 1]) * 0.5f; val gy = (r.v[i + r.w] - r.v[i - r.w]) * 0.5f
                    axx += gx * gx; axy += gx * gy; ayy += gy * gy
                }
                // On one-directional structure (stripes, a single edge) only the motion across it
                // can be measured. A small damping keeps that component and leaves the other
                // at the block match instead of letting it run away.
                val damp = 2e-3f * (axx + ayy) + 1e-10f
                axx += damp; ayy += damp
                val det = axx * ayy - axy * axy
                if (det > 1e-12f) {
                    val startU = su; val startV = sv
                    for (iter in 0 until 6) {
                        var bx = 0f; var by = 0f
                        for (yy in ya until yb) for (xx in xa until xb) {
                            val i = yy * r.w + xx
                            val gx = (r.v[i + 1] - r.v[i - 1]) * 0.5f; val gy = (r.v[i + r.w] - r.v[i - r.w]) * 0.5f
                            val e = bilinear(a, xx + su, yy + sv) - r.v[i]
                            bx += e * gx; by += e * gy
                        }
                        // alt(x + s) ~ ref(x): step that reduces the residual
                        val du = -(ayy * bx - axy * by) / det; val dv = -(axx * by - axy * bx) / det
                        su += du; sv += dv
                        if (abs(du) < 0.005f && abs(dv) < 0.005f) break
                    }
                    // a diverging solve falls back to the integer match
                    if (abs(su - startU) > 1.5f || abs(sv - startV) > 1.5f || su.isNaN() || sv.isNaN()) { su = startU; sv = startV }
                }
                // grey pixels are 2 raw pixels wide
                fu[ty * nx + tx] = su * 2f; fv[ty * nx + tx] = sv * 2f
            }
        }
        return FlowField(nx, ny, stride * 2, t, fu, fv)
    }

    internal fun bilinear(g: Gray, x: Float, y: Float): Float {
        val xc = x.coerceIn(0f, (g.w - 1).toFloat()); val yc = y.coerceIn(0f, (g.h - 1).toFloat())
        val x0 = min(xc.toInt(), g.w - 2).coerceAtLeast(0); val y0 = min(yc.toInt(), g.h - 2).coerceAtLeast(0)
        val x1 = min(x0 + 1, g.w - 1); val y1 = min(y0 + 1, g.h - 1)
        val tx = xc - x0; val ty = yc - y0
        return (g.v[y0 * g.w + x0] * (1 - tx) + g.v[y0 * g.w + x1] * tx) * (1 - ty) +
            (g.v[y1 * g.w + x0] * (1 - tx) + g.v[y1 * g.w + x1] * tx) * ty
    }
}

/** Result of a merge: demosaiced camera RGB over the reference frame's crop area. */
class MergedRaw(val width: Int, val height: Int, val rgb: ShortArray, val gainMaps: List<GainMap>,
                val noise: Pair<Double, Double>?, val framesUsed: Float)

object BurstMerge {
    class Options(
        /**
         * Kernel standard deviation on fine detail, raw pixels; null picks it from the
         * sensor noise (0.35 in good light for the sharpest result, up to 0.7 when noisy,
         * trading a little resolution for more averaging).
         */
        val kDetail: Float? = null,
        /** Kernel growth on flat, noisy areas (spatial denoising). */
        val kDenoise: Float = 3.5f,
        val kStretch: Float = 4f,
        val kShrink: Float = 2f,
    )

    /**
     * Everything the gather needs, worked out on the CPU: which frames, how each one moved,
     * where it may be trusted and the kernel at every position. The gather itself (the
     * expensive part) then runs on the CPU ([gather]) or on the GPU (Shaders.MERGE).
     */
    class Plan(
        val width: Int, val height: Int,
        /** Reference first, then the frames that could be aligned to it. */
        val frames: List<DngImage>,
        /** Per alternate frame (index f - 1). */
        val flows: List<FlowField>, val robust: List<ByteArray>,
        val guideW: Int, val guideH: Int,
        /** Inverse covariance of the kernel on a half-resolution grid. */
        val kernW: Int, val kernH: Int, val ka: FloatArray, val kb: FloatArray, val kc: FloatArray,
        val framesUsed: Float,
    ) {
        /** 1 / (white - black) per CFA position of frame [f]. */
        fun scale(f: Int) = FloatArray(4) { 1f / (frames[f].white - frames[f].black[it]).coerceAtLeast(1f) }
    }

    private const val EXP_N = 16384

    /** Merge [frames] (same sensor mode); the first frame is the reference. */
    fun merge(frames: List<DngImage>, options: Options = Options(), progress: (Float) -> Unit = {}): MergedRaw {
        val plan = plan(frames, options) { progress(it * 0.5f) }
        return finish(plan, gather(plan) { progress(0.5f + it * 0.5f) })
    }

    fun finish(plan: Plan, rgb: ShortArray): MergedRaw {
        val ref = plan.frames[0]
        return MergedRaw(plan.width, plan.height, rgb, cropGainMaps(ref),
            ref.noiseScale?.let { (it / plan.framesUsed).toDouble() to (ref.noiseOffset / plan.framesUsed).toDouble() }, plan.framesUsed)
    }

    fun plan(frames: List<DngImage>, options: Options = Options(), progress: (Float) -> Unit = {}): Plan {
        val planner = Planner(frames[0], options)
        progress(0.2f)
        for (i in 1 until frames.size) { planner.add(frames[i]); progress(0.2f + 0.8f * i / (frames.size - 1)) }
        return planner.build()
    }

    /**
     * Builds a [Plan] one frame at a time, so the frames of a burst can be aligned while
     * the camera is still taking the next ones. Not thread safe: one caller at a time.
     */
    class Planner(private val ref: DngImage, options: Options = Options()) {
        private val w = ref.cropWidth
        private val h = ref.cropHeight
        private val noiseS = ref.noiseScale ?: 3e-4f
        private val noiseO = if (ref.noiseScale != null) ref.noiseOffset else 2e-6f
        private val refPyr: BurstAlign.Pyramid
        private val refGuide: Guide
        private val kernels: Kernels
        private val alts = ArrayList<DngImage>()
        private val flows = ArrayList<FlowField>()
        private val robust = ArrayList<ByteArray>()
        private var trust = 0.0

        init {
            require(!ref.linear) { "burst frames must be Bayer raw" }
            refPyr = BurstAlign.pyramid(ref)
            refGuide = Guide(ref)
            // relative noise at mid grey decides how wide the detail kernel may be
            val relNoise = sqrt(noiseS * 0.18f + noiseO) / 0.18f
            val kDetail = options.kDetail ?: (0.35f + 0.35f * ((relNoise - 0.01f) / 0.04f).coerceIn(0f, 1f))
            kernels = Kernels(refPyr.levels[0], noiseS, noiseO, options, kDetail)
        }

        val frames get() = alts.size + 1

        /** Align [alt] to the reference and decide where it can be trusted; false if it does not fit the burst. */
        fun add(alt: DngImage): Boolean {
            if (alt.linear || alt.cropWidth != w || alt.cropHeight != h || !alt.cfa.contentEquals(ref.cfa)) return false
            val flow = BurstAlign.align(refPyr, BurstAlign.pyramid(alt))
            val r = robustness(refGuide, Guide(alt), flow, noiseS, noiseO)
            var sum = 0L
            for (b in r) sum += b.toInt() and 0xff
            trust += sum / 255.0 / r.size
            alts += alt; flows += flow; robust += r
            return true
        }

        fun build() = Plan(w, h, listOf(ref) + alts, flows.toList(), robust.toList(), refGuide.w, refGuide.h,
            kernels.w, kernels.h, kernels.a, kernels.b, kernels.c, 1f + trust.toFloat())
    }

    /** The gather on the CPU; rows [y0, y1) of the picture, interleaved RGB 0..65535. */
    fun gather(plan: Plan, y0: Int = 0, y1: Int = plan.height, progress: (Float) -> Unit = {}): ShortArray {
        val w = plan.width; val h = plan.height
        val all = plan.frames; val flows = plan.flows; val robust = plan.robust
        val out = ShortArray(w * (y1 - y0) * 3)
        val expLut = FloatArray(EXP_N + 1) { exp(-it * (50.0 / EXP_N) * 0.5).toFloat() }
        val scale = Array(all.size) { plan.scale(it) }
        val done = java.util.concurrent.atomic.AtomicInteger()
        IntStream.range(y0, y1).parallel().forEach { y ->
            val acc = FloatArray(3); val ws = FloatArray(3)
            for (x in 0 until w) {
                acc.fill(0f); ws.fill(0f)
                val ki = (min(y / 2, plan.kernH - 1)) * plan.kernW + min(x / 2, plan.kernW - 1)
                val ka = plan.ka[ki]; val kb = plan.kb[ki]; val kc = plan.kc[ki]
                for (f in all.indices) {
                    val img = all[f]
                    var px = x.toFloat(); var py = y.toFloat(); var r = 1f
                    if (f > 0) {
                        val rb = robust[f - 1]
                        r = (rb[min(y / 2, plan.guideH - 1) * plan.guideW + min(x / 2, plan.guideW - 1)].toInt() and 0xff) / 255f
                        if (r <= 0f) continue
                        val fl = flows[f - 1]
                        // bilinear flow between tile centres
                        val gx = ((x - fl.origin) / fl.stride.toFloat()).coerceIn(0f, (fl.tilesX - 1).toFloat())
                        val gy = ((y - fl.origin) / fl.stride.toFloat()).coerceIn(0f, (fl.tilesY - 1).toFloat())
                        val x0 = min(gx.toInt(), max(fl.tilesX - 2, 0)); val ty0 = min(gy.toInt(), max(fl.tilesY - 2, 0))
                        val x1 = min(x0 + 1, fl.tilesX - 1); val ty1 = min(ty0 + 1, fl.tilesY - 1)
                        val tx = gx - x0; val ty = gy - ty0
                        val i00 = ty0 * fl.tilesX + x0; val i10 = ty0 * fl.tilesX + x1; val i01 = ty1 * fl.tilesX + x0; val i11 = ty1 * fl.tilesX + x1
                        px += (fl.u[i00] * (1 - tx) + fl.u[i10] * tx) * (1 - ty) + (fl.u[i01] * (1 - tx) + fl.u[i11] * tx) * ty
                        py += (fl.v[i00] * (1 - tx) + fl.v[i10] * tx) * (1 - ty) + (fl.v[i01] * (1 - tx) + fl.v[i11] * tx) * ty
                    }
                    val cx = px.roundToInt(); val cy = py.roundToInt()
                    val sc = scale[f]
                    for (j in -1..1) {
                        val qy = cy + j
                        if (qy < 0 || qy >= h) continue
                        val dy = qy - py
                        val rowBase = (img.cropY + qy) * img.rawWidth + img.cropX
                        for (i in -1..1) {
                            val qx = cx + i
                            if (qx < 0 || qx >= w) continue
                            val dx = qx - px
                            val q = ka * dx * dx + 2f * kb * dx * dy + kc * dy * dy
                            val wgt = r * expLut[min((q * (EXP_N / 50f) + 0.5f).toInt(), EXP_N)]
                            val pos = (qy and 1) * 2 + (qx and 1)
                            val c = img.cfa[pos]
                            val v = (((img.data[rowBase + qx].toInt() and 0xffff) - img.black[pos]) * sc[pos]).coerceIn(0f, 1f)
                            acc[c] += wgt * v; ws[c] += wgt
                        }
                    }
                }
                val o = ((y - y0) * w + x) * 3
                for (c in 0..2) out[o + c] = ((acc[c] / ws[c]) * 65535f + 0.5f).toInt().coerceIn(0, 65535).toShort()
            }
            val d = done.incrementAndGet()
            if (d % 64 == 0) progress(d.toFloat() / (y1 - y0))
        }
        progress(1f)
        return out
    }

    // ------------------------------------------------------------------ guide image
    /** Half-resolution RGB (one value per Bayer cell), 3x3 box-filtered, for comparing frames. */
    private class Guide(img: DngImage) {
        val w = img.cropWidth / 2; val h = img.cropHeight / 2
        val mean = Array(3) { FloatArray(w * h) }
        /** Local variance of each channel (texture). */
        val variance = Array(3) { FloatArray(w * h) }

        init {
            val scale = FloatArray(4) { 1f / (img.white - img.black[it]).coerceAtLeast(1f) }
            val raw = Array(3) { FloatArray(w * h) }
            IntStream.range(0, h).parallel().forEach { y ->
                for (x in 0 until w) {
                    var g = 0f
                    for (pos in 0 until 4) {
                        val i = (img.cropY + y * 2 + pos / 2) * img.rawWidth + img.cropX + x * 2 + pos % 2
                        val v = (((img.data[i].toInt() and 0xffff) - img.black[pos]) * scale[pos]).coerceIn(0f, 1f)
                        when (img.cfa[pos]) { 0 -> raw[0][y * w + x] = v; 2 -> raw[2][y * w + x] = v; else -> g += v * 0.5f }
                    }
                    raw[1][y * w + x] = g
                }
            }
            for (c in 0..2) {
                val src = raw[c]
                IntStream.range(0, h).parallel().forEach { y ->
                    for (x in 0 until w) {
                        var s = 0f; var s2 = 0f; var n = 0
                        for (dy in -1..1) {
                            val yy = y + dy; if (yy < 0 || yy >= h) continue
                            for (dx in -1..1) {
                                val xx = x + dx; if (xx < 0 || xx >= w) continue
                                val v = src[yy * w + xx]; s += v; s2 += v * v; n++
                            }
                        }
                        val m = s / n
                        mean[c][y * w + x] = m
                        variance[c][y * w + x] = max(s2 / n - m * m, 0f)
                    }
                }
            }
        }
    }

    /**
     * Where can the aligned frame be merged? 255 where its local colour agrees with the
     * reference within noise and texture, 0 where it does not; eroded so that the
     * surroundings of a mismatch are also left out.
     */
    private fun robustness(ref: Guide, alt: Guide, flow: FlowField, noiseS: Float, noiseO: Float): ByteArray {
        val w = ref.w; val h = ref.h
        // tiles whose neighbours move differently: motion boundary, be stricter there
        val varying = BooleanArray(flow.tilesX * flow.tilesY)
        for (ty in 0 until flow.tilesY) for (tx in 0 until flow.tilesX) {
            val i = ty * flow.tilesX + tx
            var m = 0f
            for (dy in -1..1) for (dx in -1..1) {
                val xx = tx + dx; val yy = ty + dy
                if (xx < 0 || yy < 0 || xx >= flow.tilesX || yy >= flow.tilesY) continue
                val j = yy * flow.tilesX + xx
                m = max(m, max(abs(flow.u[i] - flow.u[j]), abs(flow.v[i] - flow.v[j])))
            }
            varying[i] = m > 1.6f
        }
        val r = FloatArray(w * h)
        IntStream.range(0, h).parallel().forEach { y ->
            for (x in 0 until w) {
                // nearest tile (guide pixels are 2 raw pixels)
                val tx = (((x * 2 - flow.origin).toFloat() / flow.stride).roundToInt()).coerceIn(0, flow.tilesX - 1)
                val ty = (((y * 2 - flow.origin).toFloat() / flow.stride).roundToInt()).coerceIn(0, flow.tilesY - 1)
                val ti = ty * flow.tilesX + tx
                val ax = (x + flow.u[ti] * 0.5f).roundToInt(); val ay = (y + flow.v[ti] * 0.5f).roundToInt()
                if (ax < 0 || ay < 0 || ax >= w || ay >= h) { r[y * w + x] = 0f; continue }
                val i = y * w + x; val j = ay * w + ax
                var ratio = 0f
                for (c in 0..2) {
                    val d = ref.mean[c][i] - alt.mean[c][j]
                    // variance of a 3x3 mean of the difference of two noisy frames (green cells hold two samples)
                    val sigma2 = (noiseS * max(ref.mean[c][i], 0f) + noiseO) * (if (c == 1) 0.5f else 1f)
                    ratio += d * d / (2f * sigma2 / 9f + 0.25f * ref.variance[c][i] + 1e-12f)
                }
                ratio /= 3f
                val s = if (varying[ti]) 2f else 12f
                r[i] = (s * exp(-ratio * 0.5f) - 0.12f).coerceIn(0f, 1f)
            }
        }
        // erosion: 5x5 minimum, separable
        val tmp = FloatArray(w * h)
        IntStream.range(0, h).parallel().forEach { y ->
            for (x in 0 until w) { var m = 1f; for (dx in -2..2) m = min(m, r[y * w + (x + dx).coerceIn(0, w - 1)]); tmp[y * w + x] = m }
        }
        val out = ByteArray(w * h)
        IntStream.range(0, h).parallel().forEach { y ->
            for (x in 0 until w) { var m = 1f; for (dy in -2..2) m = min(m, tmp[(y + dy).coerceIn(0, h - 1) * w + x]); out[y * w + x] = (m * 255f).toInt().toByte() }
        }
        return out
    }

    // ------------------------------------------------------------------ kernels
    /**
     * Inverse covariance (a, b, c) of the merge kernel at each grey pixel, so that the
     * weight of a sample at offset (dx, dy) raw pixels is exp(-(a dx² + 2 b dx dy + c dy²) / 2).
     */
    private class Kernels(g: Gray, noiseS: Float, noiseO: Float, o: Options, kDetail: Float) {
        val w = g.w; val h = g.h
        val a = FloatArray(w * h); val b = FloatArray(w * h); val c = FloatArray(w * h)

        init {
            val gx = FloatArray(w * h); val gy = FloatArray(w * h)
            IntStream.range(0, h).parallel().forEach { y ->
                for (x in 0 until w) {
                    val xl = max(x - 1, 0); val xr = min(x + 1, w - 1); val yu = max(y - 1, 0); val yd = min(y + 1, h - 1)
                    gx[y * w + x] = (g.v[y * w + xr] - g.v[y * w + xl]) * 0.5f
                    gy[y * w + x] = (g.v[yd * w + x] - g.v[yu * w + x]) * 0.5f
                }
            }
            IntStream.range(0, h).parallel().forEach { y ->
                for (x in 0 until w) {
                    var jxx = 0f; var jxy = 0f; var jyy = 0f
                    for (dy in -1..1) for (dx in -1..1) {
                        val i = (y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)
                        jxx += gx[i] * gx[i]; jxy += gx[i] * gy[i]; jyy += gy[i] * gy[i]
                    }
                    val tr = jxx + jyy
                    val disc = sqrt(max((jxx - jyy) * (jxx - jyy) + 4f * jxy * jxy, 0f))
                    val l1 = (tr + disc) * 0.5f; val l2 = max((tr - disc) * 0.5f, 0f)
                    val theta = 0.5f * atan2(2f * jxy, jxx - jyy)          // direction of the gradient
                    val anis = 1f + sqrt((l1 - l2) / (l1 + l2 + 1e-12f))
                    // is the gradient more than the sensor noise would produce on a flat patch?
                    val sigma2 = (noiseS * max(g.v[y * w + x], 0f) + noiseO) * 0.25f
                    val snr = sqrt(l1 / (4.5f * sigma2 + 1e-12f))
                    val flat = ((4f - snr) / 2.5f).coerceIn(0f, 1f)
                    val along = (1 - flat) * (kDetail * o.kStretch * anis) + flat * kDetail * o.kDenoise
                    val across = (1 - flat) * (kDetail / (o.kShrink * anis)) + flat * kDetail * o.kDenoise
                    val k1 = along * along; val k2 = across * across
                    val cs = cos(theta); val sn = sin(theta)
                    // inverse covariance: 1/k2 along the gradient, 1/k1 along the edge
                    val i = y * w + x
                    a[i] = sn * sn / k1 + cs * cs / k2
                    b[i] = -sn * cs / k1 + sn * cs / k2
                    c[i] = cs * cs / k1 + sn * sn / k2
                }
            }
        }
    }

    /** Lens shading maps of the reference, re-expressed per colour plane over the crop area. */
    private fun cropGainMaps(ref: DngImage): List<GainMap> {
        if (ref.gainMaps.isEmpty()) return emptyList()
        val out = ArrayList<GainMap>()
        for (c in 0..2) {
            val list = if (ref.gainMaps.size == 1) ref.gainMaps else (0 until 4).filter { ref.cfa[it] == c }.mapNotNull { pos ->
                ref.gainMaps.firstOrNull { it.covers(ref.cropX + pos % 2, ref.cropY + pos / 2) }
            }
            if (list.isEmpty()) continue
            val pv = list[0].pointsV.coerceAtLeast(2); val ph = list[0].pointsH.coerceAtLeast(2)
            val gains = FloatArray(pv * ph)
            for (i in 0 until pv) for (j in 0 until ph) {
                val x = ref.cropX + (j.toFloat() / (ph - 1) * (ref.cropWidth - 1)).toInt()
                val y = ref.cropY + (i.toFloat() / (pv - 1) * (ref.cropHeight - 1)).toInt()
                var g = 0f
                for (m in list) g += m.gain(x, y, ref.rawWidth, ref.rawHeight)
                gains[i * ph + j] = g / list.size
            }
            out += GainMap(0, 0, ref.cropHeight, ref.cropWidth, 1, 1, pv, ph, 1.0 / (pv - 1), 1.0 / (ph - 1), 0.0, 0.0, gains, c)
        }
        return out
    }
}
