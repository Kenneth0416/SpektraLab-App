package app.spektralab.engine

import kotlin.math.abs
import kotlin.math.floor

/** Interpolation helpers mirroring numpy / spektrafilm semantics. */
object Interp {
    /** `np.interp(x, xp, fp)` with endpoint clamping; xp increasing. */
    fun interp(x: Double, xp: DoubleArray, fp: DoubleArray): Double {
        val n = xp.size
        if (x.isNaN()) return Double.NaN
        if (x <= xp[0]) return fp[0]
        if (x >= xp[n - 1]) return fp[n - 1]
        // right-biased search like np.searchsorted(side='right')
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (xp[mid] <= x) lo = mid else hi = mid
        }
        val dx = xp[hi] - xp[lo]
        if (dx == 0.0) return fp[hi]
        val t = (x - xp[lo]) / dx
        return fp[lo] + t * (fp[hi] - fp[lo])
    }

    /**
     * `fast_interp` on a uniform axis [x0 .. x0+(K-1)*dx] for one channel of a
     * [K][3] table (spektrafilm `interpolate_exposure_to_density`).
     */
    fun interpUniform(x: Double, x0: Double, dx: Double, table: Array<DoubleArray>, ch: Int): Double {
        val k = table.size
        val t = (x - x0) / dx
        if (t <= 0.0) return table[0][ch]
        if (t >= (k - 1).toDouble()) return table[k - 1][ch]
        val i = floor(t).toInt()
        val f = t - i
        return table[i][ch] + f * (table[i + 1][ch] - table[i][ch])
    }

    fun mitchell(t: Double, b: Double = 1.0 / 3.0, c: Double = 1.0 / 3.0): Double {
        val x = abs(t)
        return when {
            x < 1 -> ((12 - 9 * b - 6 * c) * x * x * x + (-18 + 12 * b + 6 * c) * x * x + (6 - 2 * b)) / 6
            x < 2 -> ((-b - 6 * c) * x * x * x + (6 * b + 30 * c) * x * x + (-12 * b - 48 * c) * x + (8 * b + 24 * c)) / 6
            else -> 0.0
        }
    }

    private fun reflect(idx: Int, n: Int): Int = when {
        idx < 0 -> -idx
        idx >= n -> 2 * (n - 1) - idx
        else -> idx
    }

    /**
     * Mitchell–Netravali cubic lookup of a [L][L][C] LUT at fractional texel
     * coordinates (x, y), with reflected borders — `apply_lut_cubic_2d`.
     */
    fun cubic2d(lut: NdArray, xIn: Double, yIn: Double, out: DoubleArray) {
        val size = lut.shape[0]
        val channels = lut.shape[2]
        fun baseFrac(coord: Double): Pair<Int, Double> {
            var c = coord
            if (c <= 0.0) c = 0.0
            val upper = (size - 1).toDouble()
            if (c >= upper) return (size - 2) to 1.0
            val b = floor(c).toInt()
            return b to (c - b)
        }
        val (xb, xf) = baseFrac(xIn)
        val (yb, yf) = baseFrac(yIn)
        val wx = doubleArrayOf(mitchell(xf + 1), mitchell(xf), mitchell(xf - 1), mitchell(xf - 2))
        val wy = doubleArrayOf(mitchell(yf + 1), mitchell(yf), mitchell(yf - 1), mitchell(yf - 2))
        for (c in 0 until channels) out[c] = 0.0
        var wsum = 0.0
        for (i in 0..3) {
            val xi = reflect(xb - 1 + i, size)
            for (j in 0..3) {
                val yj = reflect(yb - 1 + j, size)
                val w = wx[i] * wy[j]
                wsum += w
                val base = (xi * size + yj) * channels
                for (c in 0 until channels) out[c] += w * lut.data[base + c]
            }
        }
        if (wsum != 0.0) for (c in 0 until channels) out[c] /= wsum
    }
}
