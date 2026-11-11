package app.spektralab.raw

import app.spektralab.engine.Half
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Upright, scene-linear sRGB image as RGBA half floats (ready for an RGBA16F texture). */
class LinearImage(val width: Int, val height: Int, val halfRgba: ByteBuffer) {
    fun red(x: Int, y: Int) = Half.toFloat(halfRgba.getShort((y * width + x) * 8))
    fun green(x: Int, y: Int) = Half.toFloat(halfRgba.getShort((y * width + x) * 8 + 2))
    fun blue(x: Int, y: Int) = Half.toFloat(halfRgba.getShort((y * width + x) * 8 + 4))
}

/**
 * Develops a Bayer raw frame to scene-linear sRGB — the input spektrafilm is designed
 * for: black level, lens shading (gain maps), white balance, highlight clipping,
 * demosaic and the camera colour matrix. No tone curve, sharpening or noise reduction.
 */
object RawDeveloper {
    /**
     * @param maxLongEdge wanted long edge. When the sensor has at least twice that, 2x2
     *   Bayer cells are binned (fast, and averages noise); otherwise a full-resolution
     *   Malvar-He-Cutler demosaic runs unless [fast] is set.
     */
    fun develop(img: DngImage, maxLongEdge: Int, fast: Boolean = false): LinearImage {
        val long = maxOf(img.cropWidth, img.cropHeight)
        return if (fast || long / 2 >= maxLongEdge) binned(img, maxOf(1, long / 2 / maxOf(maxLongEdge, 1)))
        else malvar(img)
    }

    // ------------------------------------------------------------------ shared
    private class Prep(val img: DngImage) {
        val scale = FloatArray(4) { 1f / (img.white - img.black[it]).coerceAtLeast(1f) }
        /** Gain map index for each CFA position, -1 if none. */
        val mapFor = IntArray(4) { pos ->
            val y = img.cropY + pos / 2; val x = img.cropX + pos % 2
            img.gainMaps.indexOfFirst { it.covers(x, y) }.let { i ->
                if (i >= 0) i else if (img.gainMaps.size == 1) 0 else -1
            }
        }

        /** Normalised, white-balanced, shading-corrected sample at crop coordinates. */
        fun sample(cx: Int, cy: Int): Float {
            val x = img.cropX + cx; val y = img.cropY + cy
            val pos = (cy and 1) * 2 + (cx and 1)
            val raw = (img.data[y * img.rawWidth + x].toInt() and 0xffff).toFloat()
            var v = ((raw - img.black[pos]) * scale[pos]).coerceIn(0f, 1f)
            // clip after white balance so blown highlights stay neutral
            v = minOf(v * img.wb[img.cfa[pos]], 1f)
            val m = mapFor[pos]
            if (m >= 0) v *= img.gainMaps[m].gain(x, y, img.rawWidth, img.rawHeight)
            return v
        }
    }

    private class Writer(img: DngImage, val w: Int, val h: Int) {
        private val o = img.orientation
        val outW = if (o == 6 || o == 8) h else w
        val outH = if (o == 6 || o == 8) w else h
        val buf: ByteBuffer = ByteBuffer.allocateDirect(outW * outH * 8).order(ByteOrder.nativeOrder())
        private val m = img.camToSrgb
        private val one = Half.fromFloat(1f)

        fun put(u: Int, v: Int, r: Float, g: Float, b: Float) {
            val x: Int; val y: Int
            when (o) {
                3 -> { x = w - 1 - u; y = h - 1 - v }
                6 -> { x = h - 1 - v; y = u }
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

    // ------------------------------------------------------------------ binning
    private fun binned(img: DngImage, k: Int): LinearImage {
        val p = Prep(img)
        val cell = 2 * k
        val w = img.cropWidth / cell
        val h = img.cropHeight / cell
        val out = Writer(img, w, h)
        val sum = FloatArray(3)
        val cnt = IntArray(3)
        for (v in 0 until h) for (u in 0 until w) {
            sum.fill(0f); cnt.fill(0)
            for (dy in 0 until cell) for (dx in 0 until cell) {
                val cx = u * cell + dx; val cy = v * cell + dy
                val c = img.cfa[(cy and 1) * 2 + (cx and 1)]
                sum[c] += p.sample(cx, cy); cnt[c]++
            }
            out.put(u, v, sum[0] / maxOf(cnt[0], 1), sum[1] / maxOf(cnt[1], 1), sum[2] / maxOf(cnt[2], 1))
        }
        return out.image()
    }

    // ------------------------------------------------------------------ Malvar-He-Cutler
    private fun malvar(img: DngImage): LinearImage {
        val p = Prep(img)
        val w = img.cropWidth
        val h = img.cropHeight
        val plane = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) plane[y * w + x] = p.sample(x, y)
        val out = Writer(img, w, h)
        // reflect by whole CFA periods so the colour phase is kept at the borders
        fun rx(x: Int) = if (x < 0) -x else if (x >= w) 2 * (w - 1) - x else x
        fun ry(y: Int) = if (y < 0) -y else if (y >= h) 2 * (h - 1) - y else y
        fun at(x: Int, y: Int) = plane[ry(y) * w + rx(x)]
        for (y in 0 until h) for (x in 0 until w) {
            val c = at(x, y)
            val n = at(x, y - 1); val s = at(x, y + 1); val e = at(x + 1, y); val wv = at(x - 1, y)
            val nn = at(x, y - 2); val ss = at(x, y + 2); val ee = at(x + 2, y); val ww = at(x - 2, y)
            val diag = at(x - 1, y - 1) + at(x + 1, y - 1) + at(x - 1, y + 1) + at(x + 1, y + 1)
            val color = img.cfa[(y and 1) * 2 + (x and 1)]
            val r: Float; val g: Float; val b: Float
            if (color == 1) {
                // green site: which colour sits left/right of it?
                val horiz = img.cfa[(y and 1) * 2 + ((x + 1) and 1)]
                val h1 = (5 * c + 4 * (e + wv) - diag - (ee + ww) + 0.5f * (nn + ss)) / 8f
                val v1 = (5 * c + 4 * (n + s) - diag - (nn + ss) + 0.5f * (ee + ww)) / 8f
                g = c
                if (horiz == 0) { r = h1; b = v1 } else { r = v1; b = h1 }
            } else {
                g = (4 * c + 2 * (n + s + e + wv) - (nn + ss + ee + ww)) / 8f
                val other = (6 * c + 2 * diag - 1.5f * (nn + ss + ee + ww)) / 8f
                if (color == 0) { r = c; b = other } else { b = c; r = other }
            }
            out.put(x, y, maxOf(r, 0f), maxOf(g, 0f), maxOf(b, 0f))
        }
        return out.image()
    }
}
