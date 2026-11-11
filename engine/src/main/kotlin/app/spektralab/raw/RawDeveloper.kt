package app.spektralab.raw

import app.spektralab.engine.Half
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Upright, scene-linear sRGB image as RGBA half floats (ready for an RGBA16F texture). */
class LinearImage(val width: Int, val height: Int, val halfRgba: ByteBuffer) {
    fun red(x: Int, y: Int) = Half.toFloat(halfRgba.getShort((y * width + x) * 8))
    fun green(x: Int, y: Int) = Half.toFloat(halfRgba.getShort((y * width + x) * 8 + 2))
    fun blue(x: Int, y: Int) = Half.toFloat(halfRgba.getShort((y * width + x) * 8 + 4))
}

/**
 * Develops a Bayer raw frame to scene-linear sRGB — the input spektrafilm is designed
 * for. Steps: black level, white balance, demosaic, highlight reconstruction, lens
 * shading (gain maps), camera colour matrix, chroma noise reduction. There is no tone
 * curve and no sharpening; luminance noise is left alone (the film's own grain sits on
 * top of it), only the colour blotches that film never has are removed.
 */
object RawDeveloper {
    fun supports(img: RawImage): Boolean = img.linear || img.cfa.isBayer

    class Options(
        /** Rebuild channels that clipped from the ones that did not (about one extra stop). */
        val reconstructHighlights: Boolean = true,
        /** Remove chroma noise using the sensor's noise profile. */
        val chromaDenoise: Boolean = true,
    )

    /**
     * @param maxLongEdge wanted long edge. When the sensor has at least twice that, 2x2
     *   Bayer cells are binned (fast, and averages noise); otherwise a full-resolution
     *   Malvar-He-Cutler demosaic runs unless [fast] is set.
     * @throws IllegalArgumentException when [supports] is false for [img].
     */
    fun develop(img: DngImage, maxLongEdge: Int, fast: Boolean = false, options: Options = Options()): LinearImage {
        require(supports(img)) { "Only Bayer and linear RGB frames can be developed" }
        val long = maxOf(img.cropWidth, img.cropHeight)
        if (img.linear) {
            // already demosaiced (merged burst): only reduce the size when asked for less
            val k = maxOf(1, long / maxOf(maxLongEdge, 1))
            val out = linear(img, k, options)
            if (options.chromaDenoise) ChromaDenoise.apply(out, img, samplesPerChannel = (k * k).toFloat())
            return out
        }
        val bin = fast || long / 2 >= maxLongEdge
        val k = if (bin) maxOf(1, long / 2 / maxOf(maxLongEdge, 1)) else 0
        val out = if (bin) binned(img, k, options) else malvar(img, options)
        if (options.chromaDenoise) ChromaDenoise.apply(out, img, samplesPerChannel = if (bin) (k * k).toFloat() else 1f)
        return out
    }

    // ------------------------------------------------------------------ shared
    private class Prep(val img: DngImage, val options: Options) {
        val cfa = IntArray(4) { img.cfa.color(img.cropY + it / 2, img.cropX + it % 2) }
        val black = FloatArray(4) { img.blackAt(img.cropY + it / 2, img.cropX + it % 2) }
        val scale = FloatArray(4) { 1f / (img.white - black[it]).coerceAtLeast(1f) }
        private val wb = img.wb

        /** Gain maps per colour (two for green on a Bayer sensor). */
        private val maps: Array<List<GainMap>> = Array(3) { c ->
            if (img.gainMaps.size == 1) img.gainMaps
            else if (img.linear) img.gainMaps.filter { it.plane == c }
            else (0 until 4).filter { cfa[it] == c }.mapNotNull { pos ->
                val y = img.cropY + pos / 2; val x = img.cropX + pos % 2
                img.gainMaps.firstOrNull { it.covers(x, y) }
            }
        }
        val hasGain = maps.any { it.isNotEmpty() }

        /** Normalised, white-balanced sample at crop coordinates; saturates at wb[colour]. */
        fun sample(cx: Int, cy: Int): Float {
            val x = img.cropX + cx; val y = img.cropY + cy
            val pos = (cy and 1) * 2 + (cx and 1)
            val raw = (img.data[y * img.rawWidth + x].toInt() and 0xffff).toFloat()
            return ((raw - black[pos]) * scale[pos]).coerceIn(0f, 1f) * wb[cfa[pos]]
        }

        /**
         * A channel that clipped carries no information above its clip level, but the
         * others often still do. Each clipped channel is raised towards the unclipped
         * ones (highlights are assumed neutral, which is also how film renders them);
         * where everything clipped the result is neutral at the highest clip level.
         */
        fun reconstruct(rgb: FloatArray) {
            if (!options.reconstructHighlights) {
                for (c in 0..2) rgb[c] = min(rgb[c], 1f)
                return
            }
            var clippedAny = false
            val a = FloatArray(3)
            for (c in 0..2) {
                // how clipped: 0 below 85% of the clip level, 1 at 97%
                val t = ((rgb[c] / wb[c] - 0.85f) / 0.12f).coerceIn(0f, 1f)
                a[c] = t * t * (3 - 2 * t)
                if (t > 0f) clippedAny = true
            }
            if (!clippedAny) return
            // estimate from the channels that still hold detail, weighted by how unclipped they are
            var sum = 0f; var wsum = 0f
            for (c in 0..2) { val w = 1f - a[c]; sum += w * rgb[c]; wsum += w }
            val ceiling = max(wb[0], max(wb[1], wb[2]))
            val estimate = if (wsum > 1e-3f) sum / wsum else ceiling
            // fade to the common ceiling as the last channel clips, so blown areas are white, not tinted
            val allClipped = min(a[0], min(a[1], a[2]))
            val target = estimate + (ceiling - estimate) * allClipped
            for (c in 0..2) rgb[c] += a[c] * (max(rgb[c], target) - rgb[c])
        }

        /** Lens shading correction for colour [c] at crop coordinates (smooth, so applied after demosaic). */
        fun gain(c: Int, cx: Int, cy: Int): Float {
            val list = maps[c]
            if (list.isEmpty()) return 1f
            val x = img.cropX + cx; val y = img.cropY + cy
            var g = 0f
            for (m in list) g += m.gain(x, y, img.rawWidth, img.rawHeight)
            return g / list.size
        }
    }

    private class Writer(img: RawImage, val w: Int, val h: Int) {
        private val o = img.orientation
        val outW = if (o >= 5) h else w
        val outH = if (o >= 5) w else h
        val buf: ByteBuffer = ByteBuffer.allocateDirect(outW * outH * 8).order(ByteOrder.nativeOrder())
        private val m = img.camToSrgb
        private val one = Half.fromFloat(1f)

        fun put(u: Int, v: Int, r: Float, g: Float, b: Float) {
            val x: Int; val y: Int
            when (o) {
                2 -> { x = w - 1 - u; y = v }
                3 -> { x = w - 1 - u; y = h - 1 - v }
                4 -> { x = u; y = h - 1 - v }
                5 -> { x = v; y = u }
                6 -> { x = h - 1 - v; y = u }
                7 -> { x = h - 1 - v; y = w - 1 - u }
                8 -> { x = v; y = w - 1 - u }
                else -> { x = u; y = v }
            }
            val i = (y * outW + x) * 8
            buf.putShort(i, Half.fromFloat(m[0] * r + m[1] * g + m[2] * b))
            buf.putShort(i + 2, Half.fromFloat(m[3] * r + m[4] * g + m[5] * b))
            buf.putShort(i + 4, Half.fromFloat(m[6] * r + m[7] * g + m[8] * b))
            buf.putShort(i + 6, one)
        }

        fun image() = LinearImage(outW, outH, buf)
    }

    // ------------------------------------------------------------------ demosaiced input
    private fun linear(img: DngImage, k: Int, options: Options): LinearImage {
        val p = Prep(img, options)
        val w = img.cropWidth / k
        val h = img.cropHeight / k
        val out = Writer(img, w, h)
        val rgb = FloatArray(3)
        val scale = 1f / (img.white - img.black[0]).coerceAtLeast(1f)
        val inv = 1f / (k * k)
        for (v in 0 until h) for (u in 0 until w) {
            rgb.fill(0f)
            for (dy in 0 until k) for (dx in 0 until k) {
                val i = ((img.cropY + v * k + dy) * img.rawWidth + img.cropX + u * k + dx) * 3
                for (c in 0..2) rgb[c] += ((img.data[i + c].toInt() and 0xffff) - img.black[0]) * scale
            }
            for (c in 0..2) rgb[c] = (rgb[c] * inv).coerceIn(0f, 1f) * img.wb[c]
            p.reconstruct(rgb)
            if (p.hasGain) for (c in 0..2) rgb[c] *= p.gain(c, u * k + k / 2, v * k + k / 2)
            out.put(u, v, rgb[0], rgb[1], rgb[2])
        }
        return out.image()
    }

    // ------------------------------------------------------------------ binning
    private fun binned(img: DngImage, k: Int, options: Options): LinearImage {
        val p = Prep(img, options)
        val cell = 2 * k
        val w = img.cropWidth / cell
        val h = img.cropHeight / cell
        val out = Writer(img, w, h)
        val sum = FloatArray(3)
        val cnt = IntArray(3)
        val rgb = FloatArray(3)
        for (v in 0 until h) for (u in 0 until w) {
            sum.fill(0f); cnt.fill(0)
            for (dy in 0 until cell) for (dx in 0 until cell) {
                val cx = u * cell + dx; val cy = v * cell + dy
                val c = p.cfa[(cy and 1) * 2 + (cx and 1)]
                sum[c] += p.sample(cx, cy); cnt[c]++
            }
            for (c in 0..2) rgb[c] = sum[c] / maxOf(cnt[c], 1)
            p.reconstruct(rgb)
            if (p.hasGain) {
                val cx = u * cell + k; val cy = v * cell + k
                for (c in 0..2) rgb[c] *= p.gain(c, cx, cy)
            }
            out.put(u, v, rgb[0], rgb[1], rgb[2])
        }
        return out.image()
    }

    // ------------------------------------------------------------------ Malvar-He-Cutler
    private fun malvar(img: DngImage, options: Options): LinearImage {
        val p = Prep(img, options)
        val w = img.cropWidth
        val h = img.cropHeight
        val plane = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) plane[y * w + x] = p.sample(x, y)
        val out = Writer(img, w, h)
        // reflect by whole CFA periods so the colour phase is kept at the borders
        fun rx(x: Int) = if (x < 0) -x else if (x >= w) 2 * (w - 1) - x else x
        fun ry(y: Int) = if (y < 0) -y else if (y >= h) 2 * (h - 1) - y else y
        fun at(x: Int, y: Int) = plane[ry(y) * w + rx(x)]
        val rgb = FloatArray(3)
        for (y in 0 until h) for (x in 0 until w) {
            val c = at(x, y)
            val n = at(x, y - 1); val s = at(x, y + 1); val e = at(x + 1, y); val wv = at(x - 1, y)
            val nn = at(x, y - 2); val ss = at(x, y + 2); val ee = at(x + 2, y); val ww = at(x - 2, y)
            val diag = at(x - 1, y - 1) + at(x + 1, y - 1) + at(x - 1, y + 1) + at(x + 1, y + 1)
            val color = p.cfa[(y and 1) * 2 + (x and 1)]
            val r: Float; val g: Float; val b: Float
            if (color == 1) {
                // green site: which colour sits left/right of it?
                val horiz = p.cfa[(y and 1) * 2 + ((x + 1) and 1)]
                val h1 = (5 * c + 4 * (e + wv) - diag - (ee + ww) + 0.5f * (nn + ss)) / 8f
                val v1 = (5 * c + 4 * (n + s) - diag - (nn + ss) + 0.5f * (ee + ww)) / 8f
                g = c
                if (horiz == 0) { r = h1; b = v1 } else { r = v1; b = h1 }
            } else {
                g = (4 * c + 2 * (n + s + e + wv) - (nn + ss + ee + ww)) / 8f
                val other = (6 * c + 2 * diag - 1.5f * (nn + ss + ee + ww)) / 8f
                if (color == 0) { r = c; b = other } else { b = c; r = other }
            }
            // the gradient-corrected filters can overshoot past a channel's clip level
            rgb[0] = r.coerceIn(0f, img.wb[0]); rgb[1] = g.coerceIn(0f, img.wb[1]); rgb[2] = b.coerceIn(0f, img.wb[2])
            p.reconstruct(rgb)
            if (p.hasGain) for (ch in 0..2) rgb[ch] *= p.gain(ch, x, y)
            out.put(x, y, rgb[0], rgb[1], rgb[2])
        }
        return out.image()
    }
}

/**
 * Chroma noise reduction on a developed image. Sensor noise shows up after demosaicing
 * as coloured speckle and blotches. Chroma is filtered at 1/2 and 1/8 resolution with a
 * filter that only averages between pixels whose luminance and chroma differ by no
 * more than the sensor's noise model predicts, and each full-resolution pixel is then
 * pulled to that clean chroma unless it differs by more than noise can explain — so
 * real colour edges and fine coloured detail stay. Luminance is not touched.
 */
object ChromaDenoise {
    fun apply(img: LinearImage, dng: DngImage, samplesPerChannel: Float) {
        val scale = dng.noiseScale ?: return
        val w = img.width; val h = img.height
        if (w < 32 || h < 32) return
        // noise amplification from raw samples to output channels: white balance and matrix
        val m = dng.camToSrgb
        var amp = 0f
        for (r in 0..2) for (c in 0..2) { val v = m[r * 3 + c] * dng.wb[c]; amp += v * v }
        amp /= 3f
        val varScale = amp * scale / samplesPerChannel
        val varOffset = amp * dng.noiseOffset / samplesPerChannel

        // half-resolution planes: luminance and two chroma differences
        val qw = (w + 1) / 2; val qh = (h + 1) / 2
        val y2 = FloatArray(qw * qh); val cr2 = FloatArray(qw * qh); val cb2 = FloatArray(qw * qh)
        val cnt = IntArray(qw * qh)
        val b = img.halfRgba
        for (py in 0 until h) for (px in 0 until w) {
            val i = (py * w + px) * 8
            val r = Half.toFloat(b.getShort(i)); val g = Half.toFloat(b.getShort(i + 2)); val bl = Half.toFloat(b.getShort(i + 4))
            val lum = 0.2126f * r + 0.7152f * g + 0.0722f * bl
            val q = (py / 2) * qw + px / 2
            y2[q] += lum; cr2[q] += r - lum; cb2[q] += bl - lum; cnt[q]++
        }
        for (q in y2.indices) { val n = max(cnt[q], 1); y2[q] /= n; cr2[q] /= n; cb2[q] /= n }

        // 1/8 resolution for the widest blotches
        val sw = (qw + 3) / 4; val sh = (qh + 3) / 4
        val y8 = FloatArray(sw * sh); val cr8 = FloatArray(sw * sh); val cb8 = FloatArray(sw * sh)
        val cnt8 = IntArray(sw * sh)
        for (qy in 0 until qh) for (qx in 0 until qw) {
            val s = (qy / 4) * sw + qx / 4; val q = qy * qw + qx
            y8[s] += y2[q]; cr8[s] += cr2[q]; cb8[s] += cb2[q]; cnt8[s]++
        }
        for (s in y8.indices) { val n = max(cnt8[s], 1); y8[s] /= n; cr8[s] /= n; cb8[s] /= n }

        // coarse pass, then push its correction into the half-resolution planes and filter those
        val dCr8 = FloatArray(sw * sh); val dCb8 = FloatArray(sw * sh)
        filter(y8, cr8, cb8, sw, sh, varScale / 64f, varOffset / 64f, dCr8, dCb8)
        for (qy in 0 until qh) for (qx in 0 until qw) {
            val q = qy * qw + qx
            cr2[q] += bilinear(dCr8, sw, sh, (qx + 0.5f) / 4f - 0.5f, (qy + 0.5f) / 4f - 0.5f)
            cb2[q] += bilinear(dCb8, sw, sh, (qx + 0.5f) / 4f - 0.5f, (qy + 0.5f) / 4f - 0.5f)
        }
        val dCr2 = FloatArray(qw * qh); val dCb2 = FloatArray(qw * qh)
        filter(y2, cr2, cb2, qw, qh, varScale / 4f, varOffset / 4f, dCr2, dCb2)
        for (q in cr2.indices) { cr2[q] += dCr2[q]; cb2[q] += dCb2[q] }

        // full resolution: move each pixel's chroma to the clean one unless the difference is real detail
        for (py in 0 until h) for (px in 0 until w) {
            val i = (py * w + px) * 8
            val r = Half.toFloat(b.getShort(i)); val g = Half.toFloat(b.getShort(i + 2)); val bl = Half.toFloat(b.getShort(i + 4))
            val lum = 0.2126f * r + 0.7152f * g + 0.0722f * bl
            val fx = (px + 0.5f) / 2f - 0.5f; val fy = (py + 0.5f) / 2f - 0.5f
            val dr = (r - lum) - bilinear(cr2, qw, qh, fx, fy)
            val db = (bl - lum) - bilinear(cb2, qw, qh, fx, fy)
            // per-pixel chroma variance; beyond about 3 sigma the difference is kept
            val v = 2.5f * (varScale * max(lum, 0f) + varOffset) + 1e-9f
            val keep = 1f - exp(-(dr * dr + db * db) / (2f * 9f * v))
            val nr = dr * keep - dr; val nb = db * keep - db          // change applied to (R - Y), (B - Y)
            if (nr == 0f && nb == 0f) continue
            b.putShort(i, Half.fromFloat(r + nr))
            b.putShort(i + 4, Half.fromFloat(bl + nb))
            b.putShort(i + 2, Half.fromFloat(g - (0.2126f * nr + 0.0722f * nb) / 0.7152f))
        }
    }

    /**
     * Noise-aware joint bilateral filter of the chroma planes (5x5). Writes the change
     * (filtered - input) to [dCr] / [dCb].
     */
    private fun filter(y: FloatArray, cr: FloatArray, cb: FloatArray, w: Int, h: Int, varScale: Float, varOffset: Float,
                       dCr: FloatArray, dCb: FloatArray) {
        for (py in 0 until h) for (px in 0 until w) {
            val i = py * w + px
            val lum = max(y[i], 0f)
            // expected variance of a difference between two pixels at this brightness
            val v = 2f * (varScale * lum + varOffset) + 1e-9f
            // chroma noise is larger than luminance noise by the colour matrix; be generous
            val invC = 1f / (2f * 9f * 2.5f * v)
            val invY = 1f / (2f * 9f * v + 2f * (0.08f * lum) * (0.08f * lum))
            var sr = 0f; var sb = 0f; var sw = 0f
            for (dy in -2..2) {
                val yy = py + dy
                if (yy < 0 || yy >= h) continue
                for (dx in -2..2) {
                    val xx = px + dx
                    if (xx < 0 || xx >= w) continue
                    val j = yy * w + xx
                    val dl = y[j] - y[i]
                    val d1 = cr[j] - cr[i]; val d2 = cb[j] - cb[i]
                    val wgt = exp(-(dl * dl) * invY - (d1 * d1 + d2 * d2) * invC)
                    sr += wgt * cr[j]; sb += wgt * cb[j]; sw += wgt
                }
            }
            dCr[i] = sr / sw - cr[i]
            dCb[i] = sb / sw - cb[i]
        }
    }

    private fun bilinear(a: FloatArray, w: Int, h: Int, fx: Float, fy: Float): Float {
        val x = fx.coerceIn(0f, (w - 1).toFloat()); val y = fy.coerceIn(0f, (h - 1).toFloat())
        val x0 = x.toInt().coerceAtMost(w - 2).coerceAtLeast(0); val y0 = y.toInt().coerceAtMost(h - 2).coerceAtLeast(0)
        val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
        val tx = x - x0; val ty = y - y0
        return (a[y0 * w + x0] * (1 - tx) + a[y0 * w + x1] * tx) * (1 - ty) + (a[y1 * w + x0] * (1 - tx) + a[y1 * w + x1] * tx) * ty
    }
}
