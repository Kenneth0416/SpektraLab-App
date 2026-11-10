package app.spektralab.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A blur expressed as identity + a sum of isotropic Gaussians, with
 * per-channel weights: out_c = identity_c * in + sum_k weights[k][c] * G(sigma_k) * in.
 * The GPU renderer evaluates each Gaussian once on a reduced-resolution pyramid.
 */
class GaussianMixture(val sigmasPx: DoubleArray, val weights: Array<DoubleArray>, val identity: DoubleArray) {
    val isIdentity: Boolean get() = sigmasPx.isEmpty()

    /** Transfer function at radial frequency f (cycles/pixel), channel c. */
    fun transfer(f: Double, c: Int): Double {
        var h = identity[c]
        for (k in sigmasPx.indices) h += weights[k][c] * gaussianTf(sigmasPx[k], f)
        return h
    }

    companion object {
        val IDENTITY = GaussianMixture(DoubleArray(0), emptyArray(), doubleArrayOf(1.0, 1.0, 1.0))
    }
}

internal fun gaussianTf(sigma: Double, f: Double): Double = exp(-2.0 * PI * PI * sigma * sigma * f * f)

/** Fourier transform of the unit 2D exponential exp(-r/l)/(2 pi l^2). */
internal fun exponentialTf(lambda: Double, f: Double): Double {
    val a = 2.0 * PI * lambda * f
    return (1.0 + a * a).pow(-1.5)
}

/** spektrafilm `fast_exponential_filter` surrogate (3 Gaussians). */
private val EXP_FIT3 = arrayOf(doubleArrayOf(0.1633, 0.5360), doubleArrayOf(0.6496, 1.5236), doubleArrayOf(0.1870, 2.7684))

internal fun exponentialSurrogateTf(lambda: Double, f: Double): Double =
    EXP_FIT3.sumOf { it[0] * gaussianTf(it[1] * lambda, f) }

/**
 * Diffusion (mist) filter PSF families — port of spektrafilm `model/diffusion.py`.
 */
object DiffusionFilters {
    private class Group(val lambdaUm: Double, val spread: Double, val n: Int, val alpha: Double = 3.0)
    private class Family(val core: Group, val halo: Group, val bloom: Group,
                         val wc: Double, val wh: Double, val wb: Double, val warmth: Double, val gain: Double)

    private val families = mapOf(
        "glimmerglass" to Family(Group(10.0, 1.5, 2), Group(50.0, 2.0, 3), Group(260.0, 2.5, 4, 3.2), 0.60, 0.30, 0.10, 0.0, 0.65),
        "black_pro_mist" to Family(Group(16.0, 1.5, 2), Group(95.0, 2.0, 3), Group(380.0, 2.5, 4, 3.5), 0.40, 0.47, 0.13, 0.65, 0.75),
        "pro_mist" to Family(Group(14.0, 1.5, 2), Group(150.0, 2.0, 3), Group(650.0, 2.5, 4, 2.9), 0.28, 0.42, 0.30, 0.40, 1.05),
        "cinebloom" to Family(Group(20.0, 1.5, 2), Group(200.0, 2.0, 3), Group(1000.0, 2.5, 4, 2.5), 0.22, 0.30, 0.48, 0.85, 1.00),
    )
    val names: Set<String> get() = families.keys

    private val strengthBreaks = doubleArrayOf(0.125, 0.25, 0.5, 1.0, 2.0)
    private val strengthFraction = doubleArrayOf(0.10, 0.20, 0.35, 0.55, 0.75)
    private val warmthAxis = doubleArrayOf(1.30, 0.15, -1.45)

    /** `_strength_to_scatter`: deflected photon fraction for a commercial filter strength. */
    fun scatterFraction(strength: Double, family: String): Double {
        if (strength <= 0) return 0.0
        val logS = log2(max(strength, 1e-6))
        val base = Interp.interp(logS, DoubleArray(5) { log2(strengthBreaks[it]) }, strengthFraction)
        return (base * (families[family]?.gain ?: 1.0)).coerceIn(0.0, 0.99)
    }

    private fun expand(g: Group, bloom: Boolean): Pair<DoubleArray, DoubleArray> {
        if (g.n == 1 || g.spread <= 1.0) return doubleArrayOf(g.lambdaUm) to doubleArrayOf(1.0)
        val lo = ln(g.lambdaUm / g.spread)
        val hi = ln(g.lambdaUm * g.spread)
        val lambdas = DoubleArray(g.n) { exp(lo + (hi - lo) * it / (g.n - 1)) }
        val w = DoubleArray(g.n) { if (bloom) lambdas[it].pow(2.0 - g.alpha) else 1.0 }
        val s = w.sum()
        for (i in w.indices) w[i] /= s
        return lambdas to w
    }

    private fun haloChannelWeights(weights: DoubleArray, warmthIn: Double): Array<DoubleArray> {
        val n = weights.size
        if (n < 2) return Array(3) { weights.copyOf() }
        val warmth = warmthIn.coerceIn(-1.5, 1.5)
        val g = DoubleArray(n) { -1.0 + 2.0 * it / (n - 1) }
        val avg = g.indices.sumOf { g[it] * weights[it] } / weights.sum()
        for (i in g.indices) g[i] -= avg
        val target = weights.sum()
        return Array(3) { c ->
            val raw = DoubleArray(n) { k -> max(weights[k] * (1.0 + warmth * warmthAxis[c] * g[k]), 0.0) }
            val s = raw.sum()
            if (s > 0) DoubleArray(n) { raw[it] * target / s } else weights.copyOf()
        }
    }

    /** Transfer function of E_out = (1-p) E + p (K * E) for one channel. */
    fun transfer(p: DiffusionFilterParams, pixelSizeUm: Double): (Double, Int) -> Double {
        val fam = families[p.family] ?: return { _, _ -> 1.0 }
        val ps = scatterFraction(p.strength, p.family)
        if (!p.active || ps <= 0 || p.spatialScale <= 0) return { _, _ -> 1.0 }
        val scale = p.spatialScale / pixelSizeUm
        val (cl, cw) = expand(fam.core, false)
        val (hl, hw) = expand(fam.halo, false)
        val (bl, bw) = expand(fam.bloom, true)
        val haloCh = haloChannelWeights(hw, fam.warmth + p.haloWarmth)
        // components: lambda (px) and per-channel weight
        val lambdas = ArrayList<Double>()
        val weights = ArrayList<DoubleArray>()
        for (i in cl.indices) { lambdas += cl[i] * scale; weights += DoubleArray(3) { fam.wc * cw[i] } }
        for (i in hl.indices) { lambdas += hl[i] * scale; weights += DoubleArray(3) { c -> fam.wh * haloCh[c][i] } }
        for (i in bl.indices) { lambdas += bl[i] * scale; weights += DoubleArray(3) { fam.wb * bw[i] } }
        // spektrafilm samples the PSF on the pixel grid and normalises the sum;
        // narrow components are evaluated the same way, wide ones analytically.
        val tfs = lambdas.map { discreteOrAnalyticExponential(it) }
        val norm = DoubleArray(3) { c -> lambdas.indices.sumOf { weights[it][c] * tfs[it](0.0) } }
        return { f, c ->
            var k = 0.0
            for (i in lambdas.indices) k += weights[i][c] * tfs[i](f)
            (1.0 - ps) + ps * k / norm[c]
        }
    }

    /** Transfer function of an exponential PSF sampled at pixel centres (unnormalised) or analytic when wide. */
    private fun discreteOrAnalyticExponential(lambda: Double): (Double) -> Double {
        if (lambda >= 4.0) return { f -> exponentialTf(lambda, f) }
        val r = kotlin.math.ceil(8 * lambda).toInt() + 1
        // marginal along x: m(x) = sum_y E(sqrt(x^2+y^2))
        val marg = DoubleArray(2 * r + 1)
        for (x in -r..r) {
            var s = 0.0
            for (y in -r..r) s += exp(-sqrt((x * x + y * y).toDouble()) / lambda) / (2 * PI * lambda * lambda)
            marg[x + r] = s
        }
        return { f ->
            var h = 0.0
            for (x in -r..r) h += marg[x + r] * kotlin.math.cos(2 * PI * f * x)
            h
        }
    }
}

object SpatialKernels {
    /** Smallest Gaussian worth a GPU pass; narrower components are folded into the identity. */
    const val MIN_SIGMA_PX = 0.6

    /**
     * Combined linear blur on film raw exposure: camera diffusion filter, then
     * spektrafilm halation (in-emulsion scatter + back-reflection halation),
     * fitted onto an octave ladder of Gaussians.
     */
    fun exposureBlur(params: RuntimeParams, pixelSizeUm: Double, maxSigmaPx: Double): GaussianMixture {
        val h = params.halation
        val diff = DiffusionFilters.transfer(params.diffusion, pixelSizeUm)
        val wS = h.scatterTailWeight
        val sc = DoubleArray(3) { h.scatterCoreUm[it] * h.scatterSpatialScale / pixelSizeUm }
        val lt = DoubleArray(3) { h.scatterTailUm[it] * h.scatterSpatialScale / pixelSizeUm }
        val aTot = DoubleArray(3) { h.halationStrength[it] * h.halationAmount }
        val sh = DoubleArray(3) { h.halationFirstSigmaUm[it] * h.halationSpatialScale / pixelSizeUm }
        val n = h.halationNBounces
        val decay = DoubleArray(n) { h.halationBounceDecay.pow(it) }.also { d -> val s = d.sum(); for (i in d.indices) d[i] /= s }
        val s = h.scatterAmount
        val target: (Double, Int) -> Double = { f, c ->
            var t = diff(f, c)
            if (h.active) {
                if (s > 0) {
                    val scat = (1 - wS[c]) * gaussianTf(sc[c], f) + wS[c] * exponentialSurrogateTf(lt[c], f)
                    t *= (1 - s) + s * scat
                }
                if (n >= 1 && aTot[c] > 0) {
                    var hb = 0.0
                    for (k in 1..n) hb += decay[k - 1] * gaussianTf(sh[c] * sqrt(k.toDouble()), f)
                    var hh = 1.0 + aTot[c] * hb
                    if (h.halationRenormalize) hh /= (1.0 + aTot[c])
                    t *= hh
                }
            }
            t
        }
        return fitLadder(target, maxSigmaPx)
    }

    /** DIR coupler inhibitor diffusion: Gaussian core + exponential tail (exact Gaussian sum). */
    fun couplerBlur(params: RuntimeParams, pixelSizeUm: Double, maxSigmaPx: Double): GaussianMixture {
        val c = params.couplers
        if (!c.active || c.diffusionSizeUm <= 0) return GaussianMixture.IDENTITY
        val sigma = c.diffusionSizeUm / pixelSizeUm
        val lambda = c.diffusionTailUm / pixelSizeUm
        val w = c.diffusionTailWeight
        val comps = mutableListOf(sigma to 1 - w)
        for (e in EXP_FIT3) comps += (e[1] * lambda) to (w * e[0])
        var identity = 0.0
        val sig = ArrayList<Double>()
        val wts = ArrayList<DoubleArray>()
        for ((sg, wt) in comps) {
            if (sg < MIN_SIGMA_PX) identity += wt
            else { sig += sg.coerceAtMost(maxSigmaPx); wts += doubleArrayOf(wt, wt, wt) }
        }
        return GaussianMixture(sig.toDoubleArray(), wts.toTypedArray(), doubleArrayOf(identity, identity, identity))
    }

    /** Least-squares fit (non-negative, energy preserving) of a transfer function on octave Gaussians. */
    fun fitLadder(target: (Double, Int) -> Double, maxSigmaPx: Double): GaussianMixture {
        val ladder = ArrayList<Double>()
        var sg = 0.75
        while (sg <= maxSigmaPx) { ladder += sg; sg *= 2.0 }
        val freqs = DoubleArray(72) { 10.0.pow(-4.5 + 4.2 * it / 71.0) } // up to ~0.5 cycles/px
        // quick exit: is the target the identity everywhere?
        var maxDev = 0.0
        for (c in 0..2) for (f in freqs) maxDev = max(maxDev, abs(target(f, c) - 1.0))
        if (maxDev < 1e-4 || ladder.isEmpty()) return GaussianMixture.IDENTITY
        val nb = ladder.size + 1
        val weights = Array(ladder.size) { DoubleArray(3) }
        val identity = DoubleArray(3)
        for (c in 0..2) {
            val rows = freqs.size + 1
            val a = Array(rows) { DoubleArray(nb) }
            val b = DoubleArray(rows)
            for ((i, f) in freqs.withIndex()) {
                a[i][0] = 1.0
                for (k in ladder.indices) a[i][k + 1] = gaussianTf(ladder[k], f)
                b[i] = target(f, c)
            }
            // strong constraint row: total energy (f = 0) preserved
            val big = 50.0
            for (k in 0 until nb) a[rows - 1][k] = big
            b[rows - 1] = big * target(0.0, c)
            val x = Nnls.solve(a, b)
            identity[c] = x[0]
            for (k in ladder.indices) weights[k][c] = x[k + 1]
        }
        // drop unused octaves
        val keep = ladder.indices.filter { k -> (0..2).any { weights[k][it] > 1e-5 } }
        return GaussianMixture(DoubleArray(keep.size) { ladder[keep[it]] }, Array(keep.size) { weights[keep[it]] }, identity)
    }
}

/** Lawson–Hanson non-negative least squares for small dense problems. */
object Nnls {
    fun solve(a: Array<DoubleArray>, b: DoubleArray, maxIter: Int = 200): DoubleArray {
        val m = a.size
        val n = a[0].size
        val x = DoubleArray(n)
        val passive = BooleanArray(n)
        fun gradient(): DoubleArray {
            val r = DoubleArray(m) { i -> b[i] - (0 until n).sumOf { a[i][it] * x[it] } }
            return DoubleArray(n) { j -> (0 until m).sumOf { a[it][j] * r[it] } }
        }
        var iter = 0
        while (iter++ < maxIter) {
            val w = gradient()
            var jMax = -1
            var wMax = 1e-10
            for (j in 0 until n) if (!passive[j] && w[j] > wMax) { wMax = w[j]; jMax = j }
            if (jMax < 0) break
            passive[jMax] = true
            while (true) {
                val p = (0 until n).filter { passive[it] }
                val z = leastSquares(a, b, p)
                if (p.indices.all { z[it] > 0 }) {
                    for (j in 0 until n) x[j] = 0.0
                    for ((i, j) in p.withIndex()) x[j] = z[i]
                    break
                }
                var alpha = Double.MAX_VALUE
                for ((i, j) in p.withIndex()) if (z[i] <= 0) alpha = minOf(alpha, x[j] / (x[j] - z[i]))
                for ((i, j) in p.withIndex()) x[j] += alpha * (z[i] - x[j])
                for (j in p) if (x[j] <= 1e-12) { passive[j] = false; x[j] = 0.0 }
            }
        }
        return x
    }

    private fun leastSquares(a: Array<DoubleArray>, b: DoubleArray, cols: List<Int>): DoubleArray {
        val k = cols.size
        val ata = Array(k) { DoubleArray(k) }
        val atb = DoubleArray(k)
        for (i in a.indices) {
            for (p in 0 until k) {
                val ap = a[i][cols[p]]
                atb[p] += ap * b[i]
                for (q in 0 until k) ata[p][q] += ap * a[i][cols[q]]
            }
        }
        for (p in 0 until k) ata[p][p] += 1e-12
        return solveSpd(ata, atb)
    }

    private fun solveSpd(m: Array<DoubleArray>, v: DoubleArray): DoubleArray {
        val n = v.size
        val a = Array(n) { m[it].copyOf() }
        val b = v.copyOf()
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
            val t = a[col]; a[col] = a[piv]; a[piv] = t
            val tb = b[col]; b[col] = b[piv]; b[piv] = tb
            val d = a[col][col]
            if (abs(d) < 1e-300) continue
            for (r in col + 1 until n) {
                val f = a[r][col] / d
                if (f == 0.0) continue
                for (cc in col until n) a[r][cc] -= f * a[col][cc]
                b[r] -= f * b[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = b[r]
            for (cc in r + 1 until n) s -= a[r][cc] * x[cc]
            x[r] = if (abs(a[r][r]) < 1e-300) 0.0 else s / a[r][r]
        }
        return x
    }
}
