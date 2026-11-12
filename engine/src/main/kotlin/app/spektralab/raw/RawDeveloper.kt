package app.spektralab.raw

import app.spektralab.engine.Half
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import java.util.stream.IntStream
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
 *
 * The frame is processed in bands of rows on all cores. Every output pixel is computed
 * exactly as by a single pass over the whole frame (same arithmetic in the same order),
 * so the result does not depend on the banding. A frame with [RawImage.rows] is decoded
 * band by band and never held whole.
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
     *   Malvar-He-Cutler demosaic runs unless [fast] is set. A demosaiced (linear) frame
     *   is reduced by whole pixels to about [maxLongEdge]; with [fast] by at least 2x2,
     *   the same output size a Bayer frame of that sensor bins to.
     * @param cancelled polled between bands; when it returns true a [CancellationException] is thrown
     * @throws IllegalArgumentException when [supports] is false for [img].
     */
    fun develop(img: DngImage, maxLongEdge: Int, fast: Boolean = false, options: Options = Options(),
                cancelled: (() -> Boolean)? = null): LinearImage {
        require(supports(img)) { "Only Bayer and linear RGB frames can be developed" }
        val long = maxOf(img.cropWidth, img.cropHeight)
        val job = Job(cancelled)
        if (img.linear) {
            // already demosaiced (merged burst): only reduce the size when asked for less
            var k = maxOf(1, long / maxOf(maxLongEdge, 1))
            if (fast && k < 2 && minOf(img.cropWidth, img.cropHeight) >= 4) k = 2
            val out = linear(img, k, options, job)
            if (options.chromaDenoise) ChromaDenoise.apply(out, img, samplesPerChannel = (k * k).toFloat(), cancelled)
            return out
        }
        val bin = fast || long / 2 >= maxLongEdge
        val k = if (bin) maxOf(1, long / 2 / maxOf(maxLongEdge, 1)) else 0
        val out = if (bin) binned(img, k, options, job) else malvar(img, options, job)
        if (options.chromaDenoise) ChromaDenoise.apply(out, img, samplesPerChannel = if (bin) (k * k).toFloat() else 1f, cancelled)
        return out
    }

    // ------------------------------------------------------------------ shared
    private class Job(val cancelled: (() -> Boolean)?) {
        fun check() { if (cancelled?.invoke() == true) throw CancellationException("development cancelled") }
    }

    /**
     * Runs [body] for consecutive ranges of [count] output rows on all cores. A band covers
     * whole decode segments of the source where possible ([rowsPerOut] source rows per
     * output row), so streamed strips are inflated once.
     */
    internal fun bands(count: Int, rowsPerOut: Int, segment: Int, cancelled: (() -> Boolean)?, body: (Int, Int) -> Unit) {
        if (count <= 0) return
        val src = maxOf(64, segment)
        val per = maxOf(1, if (segment > 1 && segment % rowsPerOut == 0) segment / rowsPerOut * maxOf(1, 64 / segment)
            else (if (segment > 1) 4 * src else src) / rowsPerOut)
        val n = (count + per - 1) / per
        val job = Job(cancelled)
        val run = { b: Int -> job.check(); body(b * per, minOf(count, (b + 1) * per)) }
        if (n == 1) run(0) else try {
            IntStream.range(0, n).parallel().forEach { run(it) }
        } catch (e: RuntimeException) {
            // a worker's exception may arrive wrapped; surface the original
            throw generateSequence<Throwable>(e) { it.cause }.firstOrNull { it is CancellationException } ?: e
        }
    }

    /** Source samples of crop rows: the whole frame, or one band decoded from [RawImage.rows]. */
    private class Band(val data: ShortArray, val firstRow: Int)

    /** Runs [body] on raw rows [y0, y1) of [img]; streamed rows go to a buffer from [pool]. */
    private inline fun <R> withBand(img: RawImage, y0: Int, y1: Int, pool: Pool<ShortArray>, body: (Band) -> R): R {
        val rows = img.rows ?: return body(Band(img.data, 0))
        return pool.use((y1 - y0) * img.rawWidth * (if (img.linear) 3 else 1)) { dst ->
            rows.read(y0, y1, dst)
            body(Band(dst, y0))
        }
    }

    private fun segment(img: RawImage) = img.rows?.segmentRows ?: 64

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
        private val luts: Array<Array<GainLut>> = Array(3) { c -> Array(maps[c].size) { GainLut(maps[c][it], img) } }

        /** Normalised, white-balanced sample at crop coordinates; saturates at wb[colour]. */
        fun sample(b: Band, cx: Int, cy: Int): Float {
            val x = img.cropX + cx; val y = img.cropY + cy
            val pos = (cy and 1) * 2 + (cx and 1)
            val raw = (b.data[(y - b.firstRow) * img.rawWidth + x].toInt() and 0xffff).toFloat()
            return ((raw - black[pos]) * scale[pos]).coerceIn(0f, 1f) * wb[cfa[pos]]
        }

        /**
         * A channel that clipped carries no information above its clip level, but the
         * others often still do. Each clipped channel is raised towards the unclipped
         * ones (highlights are assumed neutral, which is also how film renders them);
         * where everything clipped the result is neutral at the highest clip level.
         */
        fun reconstruct(rgb: FloatArray, a: FloatArray) {
            if (!options.reconstructHighlights) {
                for (c in 0..2) rgb[c] = min(rgb[c], 1f)
                return
            }
            var clippedAny = false
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
            val list = luts[c]
            if (list.isEmpty()) return 1f
            var g = 0f
            for (m in list) g += m.gain(cx, cy)
            return g / list.size
        }
    }

    /**
     * [GainMap.gain] over the crop with the per-row and per-column parts computed once:
     * the same arithmetic, so the same values, without the per-pixel double divisions.
     */
    private class GainLut(val m: GainMap, img: RawImage) {
        private val v0 = IntArray(img.cropHeight); private val v1 = IntArray(img.cropHeight); private val fv = FloatArray(img.cropHeight)
        private val h0 = IntArray(img.cropWidth); private val h1 = IntArray(img.cropWidth); private val fh = FloatArray(img.cropWidth)
        init {
            for (cy in 0 until img.cropHeight) {
                val v = (((img.cropY + cy).toDouble() / img.rawHeight - m.originV) / m.spacingV).coerceIn(0.0, (m.pointsV - 1).toDouble())
                val a = v.toInt().coerceAtMost(m.pointsV - 2).coerceAtLeast(0)
                v0[cy] = a; v1[cy] = minOf(a + 1, m.pointsV - 1); fv[cy] = (v - a).toFloat()
            }
            for (cx in 0 until img.cropWidth) {
                val h = (((img.cropX + cx).toDouble() / img.rawWidth - m.originH) / m.spacingH).coerceIn(0.0, (m.pointsH - 1).toDouble())
                val a = h.toInt().coerceAtMost(m.pointsH - 2).coerceAtLeast(0)
                h0[cx] = a; h1[cx] = minOf(a + 1, m.pointsH - 1); fh[cx] = (h - a).toFloat()
            }
        }

        fun gain(cx: Int, cy: Int): Float {
            val g = m.gains; val ph = m.pointsH
            val r0 = v0[cy] * ph; val r1 = v1[cy] * ph
            val a0 = h0[cx]; val a1 = h1[cx]; val fh = fh[cx]; val fv = fv[cy]
            val a = g[r0 + a0] * (1 - fh) + g[r0 + a1] * fh
            val b = g[r1 + a0] * (1 - fh) + g[r1 + a1] * fh
            return a * (1 - fv) + b * fv
        }
    }

    /** Output buffer; [put] from several threads is safe for distinct pixels. */
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
    private fun linear(img: DngImage, k: Int, options: Options, job: Job): LinearImage {
        val p = Prep(img, options)
        val w = img.cropWidth / k
        val h = img.cropHeight / k
        val out = Writer(img, w, h)
        val scale = 1f / (img.white - img.black[0]).coerceAtLeast(1f)
        val inv = 1f / (k * k)
        val pool = Pool.shorts()
        bands(h, k, segment(img), job.cancelled) { v0, v1 -> withBand(img, img.cropY + v0 * k, img.cropY + v1 * k, pool) { b ->
            val rgb = FloatArray(3); val tmp = FloatArray(3)
            for (v in v0 until v1) for (u in 0 until w) {
                rgb.fill(0f)
                for (dy in 0 until k) for (dx in 0 until k) {
                    val i = ((img.cropY + v * k + dy - b.firstRow) * img.rawWidth + img.cropX + u * k + dx) * 3
                    for (c in 0..2) rgb[c] += ((b.data[i + c].toInt() and 0xffff) - img.black[0]) * scale
                }
                for (c in 0..2) rgb[c] = (rgb[c] * inv).coerceIn(0f, 1f) * img.wb[c]
                p.reconstruct(rgb, tmp)
                if (p.hasGain) for (c in 0..2) rgb[c] *= p.gain(c, u * k + k / 2, v * k + k / 2)
                out.put(u, v, rgb[0], rgb[1], rgb[2])
            }
        } }
        return out.image()
    }

    // ------------------------------------------------------------------ binning
    private fun binned(img: DngImage, k: Int, options: Options, job: Job): LinearImage {
        val p = Prep(img, options)
        val cell = 2 * k
        val w = img.cropWidth / cell
        val h = img.cropHeight / cell
        val out = Writer(img, w, h)
        val pool = Pool.shorts()
        bands(h, cell, segment(img), job.cancelled) { v0, v1 -> withBand(img, img.cropY + v0 * cell, img.cropY + v1 * cell, pool) { b ->
            val sum = FloatArray(3); val cnt = IntArray(3); val rgb = FloatArray(3); val tmp = FloatArray(3)
            for (v in v0 until v1) for (u in 0 until w) {
                sum.fill(0f); cnt.fill(0)
                for (dy in 0 until cell) for (dx in 0 until cell) {
                    val cx = u * cell + dx; val cy = v * cell + dy
                    val c = p.cfa[(cy and 1) * 2 + (cx and 1)]
                    sum[c] += p.sample(b, cx, cy); cnt[c]++
                }
                for (c in 0..2) rgb[c] = sum[c] / maxOf(cnt[c], 1)
                p.reconstruct(rgb, tmp)
                if (p.hasGain) {
                    val cx = u * cell + k; val cy = v * cell + k
                    for (c in 0..2) rgb[c] *= p.gain(c, cx, cy)
                }
                out.put(u, v, rgb[0], rgb[1], rgb[2])
            }
        } }
        return out.image()
    }

    // ------------------------------------------------------------------ Malvar-He-Cutler
    private fun malvar(img: DngImage, options: Options, job: Job): LinearImage {
        val p = Prep(img, options)
        val w = img.cropWidth
        val h = img.cropHeight
        val out = Writer(img, w, h)
        // reflect by whole CFA periods so the colour phase is kept at the borders
        fun rx(x: Int) = if (x < 0) -x else if (x >= w) 2 * (w - 1) - x else x
        fun ry(y: Int) = if (y < 0) -y else if (y >= h) 2 * (h - 1) - y else y
        val pool = Pool.shorts()
        val planes = Pool.floats()
        bands(h, 1, segment(img), job.cancelled) { y0, y1 ->
            // the band's normalised samples with the 2-row neighbourhood the filters reach
            val p0 = maxOf(0, y0 - 2); val p1 = minOf(h, y1 + 2)
            val plane = planes.take((p1 - p0) * w)
            withBand(img, img.cropY + p0, img.cropY + p1, pool) { b ->
                for (y in p0 until p1) for (x in 0 until w) plane[(y - p0) * w + x] = p.sample(b, x, y)
            }
            fun at(x: Int, y: Int) = plane[(ry(y) - p0) * w + rx(x)]
            val rgb = FloatArray(3); val tmp = FloatArray(3)
            for (y in y0 until y1) for (x in 0 until w) {
                val c = at(x, y)
                val n = at(x, y - 1); val s = at(x, y + 1); val e = at(x + 1, y); val wv = at(x - 1, y)
                val nn = at(x, y - 2); val ss = at(x, y + 2); val ee = at(x + 2, y); val ww = at(x - 2, y)
                val diag = at(x - 1, y - 1) + at(x + 1, y - 1) + at(x - 1, y + 1) + at(x + 1, y + 1)
                val color = p.cfa[(y and 1) * 2 + (x and 1)]
                val r: Float; val g: Float; val bl: Float
                if (color == 1) {
                    // green site: which colour sits left/right of it?
                    val horiz = p.cfa[(y and 1) * 2 + ((x + 1) and 1)]
                    val h1 = (5 * c + 4 * (e + wv) - diag - (ee + ww) + 0.5f * (nn + ss)) / 8f
                    val v1 = (5 * c + 4 * (n + s) - diag - (nn + ss) + 0.5f * (ee + ww)) / 8f
                    g = c
                    if (horiz == 0) { r = h1; bl = v1 } else { r = v1; bl = h1 }
                } else {
                    g = (4 * c + 2 * (n + s + e + wv) - (nn + ss + ee + ww)) / 8f
                    val other = (6 * c + 2 * diag - 1.5f * (nn + ss + ee + ww)) / 8f
                    if (color == 0) { r = c; bl = other } else { bl = c; r = other }
                }
                // the gradient-corrected filters can overshoot past a channel's clip level
                rgb[0] = r.coerceIn(0f, img.wb[0]); rgb[1] = g.coerceIn(0f, img.wb[1]); rgb[2] = bl.coerceIn(0f, img.wb[2])
                p.reconstruct(rgb, tmp)
                if (p.hasGain) for (ch in 0..2) rgb[ch] *= p.gain(ch, x, y)
                out.put(x, y, rgb[0], rgb[1], rgb[2])
            }
            planes.give(plane)
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
 *
 * Every pass runs over rows on all cores with the per-pixel arithmetic and summation
 * order of a single sequential pass.
 */
object ChromaDenoise {
    private val HALF: FloatArray by lazy { FloatArray(65536) { Half.toFloat(it.toShort()) } }

    fun apply(img: LinearImage, dng: DngImage, samplesPerChannel: Float, cancelled: (() -> Boolean)? = null) {
        val scale = dng.noiseScale ?: return
        val w = img.width; val h = img.height
        if (w < 32 || h < 32) return
        val half = HALF
        // noise amplification from raw samples to output channels: white balance and matrix
        val m = dng.camToSrgb
        var amp = 0f
        for (r in 0..2) for (c in 0..2) { val v = m[r * 3 + c] * dng.wb[c]; amp += v * v }
        amp /= 3f
        val varScale = amp * scale / samplesPerChannel
        val varOffset = amp * dng.noiseOffset / samplesPerChannel
        fun rows(n: Int, body: (Int) -> Unit) = RawDeveloper.bands(n, 1, 1, cancelled) { a, b -> for (r in a until b) body(r) }

        // half-resolution planes: luminance and two chroma differences
        val qw = (w + 1) / 2; val qh = (h + 1) / 2
        val y2 = FloatArray(qw * qh); val cr2 = FloatArray(qw * qh); val cb2 = FloatArray(qw * qh)
        val b = img.halfRgba
        rows(qh) { qy ->
            for (py in 2 * qy until minOf(h, 2 * qy + 2)) for (px in 0 until w) {
                val i = (py * w + px) * 8
                val r = half[b.getShort(i).toInt() and 0xffff]; val g = half[b.getShort(i + 2).toInt() and 0xffff]; val bl = half[b.getShort(i + 4).toInt() and 0xffff]
                val lum = 0.2126f * r + 0.7152f * g + 0.0722f * bl
                val q = qy * qw + px / 2
                y2[q] += lum; cr2[q] += r - lum; cb2[q] += bl - lum
            }
            val ny = minOf(h, 2 * qy + 2) - 2 * qy
            for (qx in 0 until qw) {
                val n = ny * (minOf(w, 2 * qx + 2) - 2 * qx)
                val q = qy * qw + qx
                y2[q] /= n; cr2[q] /= n; cb2[q] /= n
            }
        }

        // 1/8 resolution for the widest blotches
        val sw = (qw + 3) / 4; val sh = (qh + 3) / 4
        val y8 = FloatArray(sw * sh); val cr8 = FloatArray(sw * sh); val cb8 = FloatArray(sw * sh)
        rows(sh) { sy ->
            for (qy in 4 * sy until minOf(qh, 4 * sy + 4)) for (qx in 0 until qw) {
                val s = sy * sw + qx / 4; val q = qy * qw + qx
                y8[s] += y2[q]; cr8[s] += cr2[q]; cb8[s] += cb2[q]
            }
            val ny = minOf(qh, 4 * sy + 4) - 4 * sy
            for (sx in 0 until sw) {
                val n = ny * (minOf(qw, 4 * sx + 4) - 4 * sx)
                val s = sy * sw + sx
                y8[s] /= n; cr8[s] /= n; cb8[s] /= n
            }
        }

        // coarse pass, then push its correction into the half-resolution planes and filter those
        val dCr8 = FloatArray(sw * sh); val dCb8 = FloatArray(sw * sh)
        filter(y8, cr8, cb8, sw, sh, varScale / 64f, varOffset / 64f, dCr8, dCb8, ::rows)
        rows(qh) { qy ->
            for (qx in 0 until qw) {
                val q = qy * qw + qx
                cr2[q] += bilinear(dCr8, sw, sh, (qx + 0.5f) / 4f - 0.5f, (qy + 0.5f) / 4f - 0.5f)
                cb2[q] += bilinear(dCb8, sw, sh, (qx + 0.5f) / 4f - 0.5f, (qy + 0.5f) / 4f - 0.5f)
            }
        }
        val dCr2 = FloatArray(qw * qh); val dCb2 = FloatArray(qw * qh)
        filter(y2, cr2, cb2, qw, qh, varScale / 4f, varOffset / 4f, dCr2, dCb2, ::rows)
        rows(qh) { qy -> for (q in qy * qw until qy * qw + qw) { cr2[q] += dCr2[q]; cb2[q] += dCb2[q] } }

        // full resolution: move each pixel's chroma to the clean one unless the difference is real detail
        rows(h) { py ->
            for (px in 0 until w) {
                val i = (py * w + px) * 8
                val r = half[b.getShort(i).toInt() and 0xffff]; val g = half[b.getShort(i + 2).toInt() and 0xffff]; val bl = half[b.getShort(i + 4).toInt() and 0xffff]
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
    }

    /**
     * Noise-aware joint bilateral filter of the chroma planes (5x5). Writes the change
     * (filtered - input) to [dCr] / [dCb].
     */
    private fun filter(y: FloatArray, cr: FloatArray, cb: FloatArray, w: Int, h: Int, varScale: Float, varOffset: Float,
                       dCr: FloatArray, dCb: FloatArray, rows: (Int, (Int) -> Unit) -> Unit) = rows(h) { py ->
        for (px in 0 until w) {
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
