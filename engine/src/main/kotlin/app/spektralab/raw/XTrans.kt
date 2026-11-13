package app.spektralab.raw

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Development of non-Bayer CFA mosaics, chiefly Fujifilm X-Trans (6x6), for
 * [RawDeveloper]. Same pipeline and semantics as the Bayer paths (black, white balance,
 * highlight reconstruction, lens shading, camera matrix), two demosaics:
 *
 * - [binned]: averages each colour over 3x3 cells (every aligned 3x3 block of an X-Trans
 *   tile holds 2 R, 5 G and 2 B), times the bin factor. Fast; for previews.
 * - [full]: full resolution. Green at red/blue sites is an edge-directed average of the
 *   adjacent greens along the four axes, sharpened by the colour-difference
 *   (constant-hue) estimate from the neighbouring sites of the same colour; red and blue
 *   are then interpolated as colour differences to green with weights that fall off with
 *   distance and with green dissimilarity, so differences are not averaged across edges.
 *   Works in bands of rows, so the only full-frame buffer is the output.
 */
internal object XTrans {
    /**
     * True for a pattern [full] and [binned] can develop: not Bayer, every 5x5 window holds
     * all three colours and every red / blue site has a green among its eight neighbours.
     */
    fun supports(cfa: CfaPattern): Boolean {
        if (cfa.isBayer) return false
        for (r in 0 until cfa.height) for (c in 0 until cfa.width) {
            val seen = BooleanArray(3)
            for (dy in -2..2) for (dx in -2..2) seen[cfa.color(r + dy, c + dx)] = true
            if (!seen[0] || !seen[1] || !seen[2]) return false
            if (cfa.color(r, c) != 1) {
                var green = false
                for (dy in -1..1) for (dx in -1..1) if (cfa.color(r + dy, c + dx) == 1) green = true
                if (!green) return false
            }
        }
        return true
    }

    /** Smallest bin cell (3 for X-Trans) whose aligned blocks, at the crop's phase, all hold the three colours. */
    fun baseCell(img: RawImage): Int {
        val cfa = img.cfa
        for (b in intArrayOf(3, 6, 2 * maxOf(cfa.width, cfa.height))) {
            var ok = true
            for (by in 0 until cfa.height * b step b) for (bx in 0 until cfa.width * b step b) {
                val seen = BooleanArray(3)
                for (dy in 0 until b) for (dx in 0 until b) seen[cfa.color(img.cropY + by + dy, img.cropX + bx + dx)] = true
                if (!seen[0] || !seen[1] || !seen[2]) ok = false
            }
            if (ok) return b
        }
        return 2 * maxOf(cfa.width, cfa.height)
    }

    /** Per-pixel normalisation for crop coordinates: colour, black and scale by CFA position. */
    private class Norm(val img: RawImage) {
        val cfa = img.cfa
        val color = IntArray(cfa.size) { cfa.colors[it] }
        val black = FloatArray(cfa.size) { img.black[it] }
        val mul = FloatArray(cfa.size) { 1f / (img.white - img.black[it]).coerceAtLeast(1f) }
        val wb = FloatArray(cfa.size) { img.wb[cfa.colors[it]] }
        /** CFA position of crop pixel (x, y) is posRow[y % height][x]. */
        val posRow = Array(cfa.height) { r -> IntArray(img.cropWidth) { x -> cfa.index(img.cropY + r, img.cropX + x) } }

        /** Sample at crop (x, y) from the frame the reader decoded. */
        fun sample(x: Int, y: Int, pos: Int): Float {
            val raw = (img.data[(img.cropY + y) * img.rawWidth + img.cropX + x].toInt() and 0xffff).toFloat()
            return ((raw - black[pos]) * mul[pos]).coerceIn(0f, 1f) * wb[pos]
        }
    }

    // ------------------------------------------------------------------ binning
    fun binned(img: RawImage, k: Int, p: RawDeveloper.Prep, allocate: (Int) -> java.nio.ByteBuffer): LinearImage {
        val n = Norm(img)
        val cell = baseCell(img) * k
        val w = img.cropWidth / cell
        val h = img.cropHeight / cell
        val out = RawDeveloper.Writer(img, w, h, allocate)
        val ph = img.cfa.height
        val sum = FloatArray(3); val cnt = IntArray(3); val rgb = FloatArray(3)
        for (v in 0 until h) for (u in 0 until w) {
            sum.fill(0f); cnt.fill(0)
            for (dy in 0 until cell) {
                val cy = v * cell + dy
                val row = n.posRow[cy % ph]
                for (dx in 0 until cell) {
                    val cx = u * cell + dx
                    val pos = row[cx]
                    val c = n.color[pos]
                    sum[c] += n.sample(cx, cy, pos); cnt[c]++
                }
            }
            for (c in 0..2) rgb[c] = sum[c] / maxOf(cnt[c], 1)
            p.reconstruct(rgb)
            if (p.hasGain) {
                val cx = u * cell + cell / 2; val cy = v * cell + cell / 2
                for (c in 0..2) rgb[c] *= p.gain(c, cx, cy)
            }
            out.put(u, v, rgb[0], rgb[1], rgb[2])
        }
        return out.image()
    }

    // ------------------------------------------------------------------ full resolution
    private const val BAND = 64
    /** Rows of context a band needs: colour differences (2) <- refined green (2) <- first green (2). */
    private const val M_RAW = 6
    private const val M_G0 = 4
    private const val M_G = 2

    /** Band work buffers, one set per worker thread. */
    private class Buffers(w: Int) {
        val v = FloatArray((BAND + 2 * M_RAW) * w)      // samples, rows y0 - M_RAW ..
        val col = IntArray((BAND + 2 * M_RAW) * w)      // colour of each sample, -1 outside the crop
        val g0 = FloatArray((BAND + 2 * M_G0) * w)      // first green estimate, rows y0 - M_G0 ..
        val g = FloatArray((BAND + 2 * M_G) * w)        // refined green, rows y0 - M_G ..
        val rgb = FloatArray(3)
    }

    fun full(img: RawImage, p: RawDeveloper.Prep, allocate: (Int) -> java.nio.ByteBuffer): LinearImage {
        val n = Norm(img)
        val w = img.cropWidth
        val h = img.cropHeight
        val out = RawDeveloper.Writer(img, w, h, allocate)
        val buf = Buffers(w)
        var y0 = 0
        while (y0 < h) {
            val y1 = min(y0 + BAND, h)
            band(img, p, n, out, buf, y0, y1)
            y0 = y1
        }
        return out.image()
    }

    private fun band(img: RawImage, p: RawDeveloper.Prep, n: Norm, out: RawDeveloper.Writer, b: Buffers, y0: Int, y1: Int) {
        val w = img.cropWidth
        val h = img.cropHeight
        val ph = img.cfa.height
        val v = b.v; val col = b.col; val g0 = b.g0; val g = b.g; val rgb = b.rgb
        val wb0 = img.wb[0]; val wb1 = img.wb[1]; val wb2 = img.wb[2]
        for (j in 0 until BAND + 2 * M_RAW) {
            val y = y0 - M_RAW + j
            val o = j * w
            if (y < 0 || y >= h) { java.util.Arrays.fill(col, o, o + w, -1); continue }
            val row = n.posRow[y % ph]
            for (x in 0 until w) { val pos = row[x]; col[o + x] = n.color[pos]; v[o + x] = n.sample(x, y, pos) }
        }
        // first green: edge-directed average of adjacent greens
        for (j in 0 until BAND + 2 * M_G0) {
            val y = y0 - M_G0 + j
            if (y < 0 || y >= h) continue
            val vj = j + (M_RAW - M_G0)
            for (x in 0 until w) g0[j * w + x] = greenAt(v, col, w, vj, x)
        }
        // refined green at red / blue sites: v - (colour difference of nearby same-colour sites)
        for (j in 0 until BAND + 2 * M_G) {
            val y = y0 - M_G + j
            if (y < 0 || y >= h) continue
            val vj = j + (M_RAW - M_G)
            val gj = j + (M_G0 - M_G)
            for (x in 0 until w) g[j * w + x] = refineGreen(v, col, g0, w, vj, gj, x)
        }
        // red and blue as colour differences to the refined green
        for (y in y0 until y1) {
            val vj = y - y0 + M_RAW
            val gj = y - y0 + M_G
            for (x in 0 until w) {
                val i = vj * w + x
                val c = col[i]
                val gc = g[gj * w + x]
                val r: Float; val bl: Float
                if (c == 0) { r = v[i]; bl = gc + difference(v, col, g, w, vj, gj, x, 2, gc) }
                else if (c == 2) { bl = v[i]; r = gc + difference(v, col, g, w, vj, gj, x, 0, gc) }
                else { r = gc + difference(v, col, g, w, vj, gj, x, 0, gc); bl = gc + difference(v, col, g, w, vj, gj, x, 2, gc) }
                rgb[0] = r.coerceIn(0f, wb0); rgb[1] = gc.coerceIn(0f, wb1); rgb[2] = bl.coerceIn(0f, wb2)
                p.reconstruct(rgb)
                if (p.hasGain) for (ch in 0..2) rgb[ch] *= p.gain(ch, x, y)
                out.put(x, y, rgb[0], rgb[1], rgb[2])
            }
        }
    }

    private val AXES = arrayOf(intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(1, 1), intArrayOf(1, -1))

    /** Green at band row [j] (in [v] coordinates), column [x]. */
    private fun greenAt(v: FloatArray, col: IntArray, w: Int, j: Int, x: Int): Float {
        val i = j * w + x
        if (col[i] == 1) return v[i]
        fun green(dx: Int, dy: Int): Float {           // NaN when not a green sample inside the crop
            val xx = x + dx
            if (xx < 0 || xx >= w) return Float.NaN
            val k = (j + dy) * w + xx
            return if (col[k] == 1) v[k] else Float.NaN
        }
        var sum = 0f; var wsum = 0f
        for (a in AXES) {
            val dx = a[0]; val dy = a[1]
            val ga = green(dx, dy); val gb = green(-dx, -dy)
            val base = if (dx != 0 && dy != 0) 0.5f else 1f
            val est: Float; val grad: Float; val weight: Float
            if (!ga.isNaN() && !gb.isNaN()) {
                est = 0.5f * (ga + gb); grad = abs(ga - gb); weight = base
            } else if (!ga.isNaN() || !gb.isNaN()) {
                val s = if (ga.isNaN()) -1 else 1
                val g1 = if (ga.isNaN()) gb else ga
                val g2 = green(2 * s * dx, 2 * s * dy)
                val g3 = green(-s * 2 * dx, -s * 2 * dy)
                est = g1
                // gradient from the next green along the axis on either side, if any
                grad = if (!g2.isNaN()) abs(g1 - g2) else if (!g3.isNaN()) abs(g1 - g3) else Float.NaN
                weight = 0.5f * base
            } else continue
            val e = 0.002f + 0.02f * est
            val wgt = if (grad.isNaN()) weight / (4 * e) else weight / (e + grad)
            sum += wgt * est; wsum += wgt
        }
        return if (wsum > 0f) sum / wsum else localMean(v, col, w, j, x, 1)
    }

    /** Mean of the colour-[c] samples within radius [r]; 0 if none. */
    private fun localMean(v: FloatArray, col: IntArray, w: Int, j: Int, x: Int, c: Int): Float {
        var s = 0f; var n = 0
        for (dy in -2..2) for (dx in -2..2) {
            val xx = x + dx
            if (xx < 0 || xx >= w) continue
            val k = (j + dy) * w + xx
            if (col[k] == c) { s += v[k]; n++ }
        }
        return if (n > 0) s / n else 0f
    }

    /**
     * Green at a red / blue site from the colour differences (sample - green) of the
     * other sites of the same colour in the 5x5 window, weighted by green similarity;
     * kept within the range of the adjacent greens (stretched by a little), so it adds
     * the site's own detail without overshooting edges.
     */
    private fun refineGreen(v: FloatArray, col: IntArray, g0: FloatArray, w: Int, vj: Int, gj: Int, x: Int): Float {
        val i = vj * w + x
        val c = col[i]
        val gc = g0[gj * w + x]
        if (c == 1) return gc
        var sum = 0f; var wsum = 0f
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        val e = 0.002f + 0.02f * gc
        val dx0 = max(-2, -x); val dx1 = min(2, w - 1 - x)
        for (dy in -2..2) for (dx in dx0..dx1) {
            val xx = x + dx
            val k = (vj + dy) * w + xx
            val ck = col[k]
            if (ck == 1 && abs(dx) <= 1 && abs(dy) <= 1) { lo = min(lo, v[k]); hi = max(hi, v[k]) }
            if (ck != c || (dx == 0 && dy == 0)) continue
            val gk = g0[(gj + dy) * w + xx]
            val d = abs(gk - gc)
            val wgt = SPATIAL[(dy + 2) * 5 + dx + 2] / (e + d)
            sum += wgt * (v[k] - gk); wsum += wgt
        }
        if (wsum <= 0f || lo > hi) return gc
        val est = v[i] - sum / wsum
        val margin = 0.25f * (hi - lo)
        return est.coerceIn(lo - margin, hi + margin)
    }

    /** Colour difference (colour [c] - green) at a pixel from the colour-[c] sites in its 5x5 window. */
    private fun difference(v: FloatArray, col: IntArray, g: FloatArray, w: Int, vj: Int, gj: Int, x: Int, c: Int, gc: Float): Float {
        var sum = 0f; var wsum = 0f
        val e = 0.002f + 0.02f * gc
        val dx0 = max(-2, -x); val dx1 = min(2, w - 1 - x)
        for (dy in -2..2) {
            val vo = (vj + dy) * w
            val go = (gj + dy) * w
            for (dx in dx0..dx1) {
                val xx = x + dx
                if (col[vo + xx] != c) continue
                val gk = g[go + xx]
                val wgt = SPATIAL[(dy + 2) * 5 + dx + 2] / (e + abs(gk - gc))
                sum += wgt * (v[vo + xx] - gk); wsum += wgt
            }
        }
        return if (wsum > 0f) sum / wsum else 0f
    }

    /** Spatial weights of the 5x5 window: 1 / (1 + squared distance). */
    private val SPATIAL = FloatArray(25) { val dy = it / 5 - 2; val dx = it % 5 - 2; 1f / (1f + dx * dx + dy * dy) }
}
