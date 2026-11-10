package app.spektralab.render

import app.spektralab.engine.Catalog
import app.spektralab.engine.Look
import kotlin.math.max
import kotlin.math.roundToInt

/** Crop and output layout for a film format (aspect, optional film-edge border). */
class Framing(
    /** Crop of the upright source, uv with y up (x0, y0, x1, y1). */
    val crop: FloatArray,
    /** Processed image size, px. */
    val imageWidth: Int,
    val imageHeight: Int,
    /** Output canvas size (image + border), px. */
    val outWidth: Int,
    val outHeight: Int,
    /** Image rect inside the output, uv y up. */
    val imageRect: FloatArray,
) {
    val hasBorder get() = outWidth != imageWidth || outHeight != imageHeight

    companion object {
        /**
         * [srcW]x[srcH] is the upright source size. The image is center-cropped to the
         * film format aspect and scaled so its long edge is at most [maxLongEdge].
         */
        fun compute(srcW: Int, srcH: Int, look: Look, maxLongEdge: Int, even: Boolean = false): Framing {
            val fmt = Catalog.format(look.format)
            val landscape = srcW >= srcH
            val target = if (landscape) fmt.aspect else 1.0 / fmt.aspect
            val srcAspect = srcW.toDouble() / srcH
            var cw = 1.0
            var ch = 1.0
            if (srcAspect > target) cw = target / srcAspect else ch = srcAspect / target
            val crop = floatArrayOf(((1 - cw) / 2).toFloat(), ((1 - ch) / 2).toFloat(),
                ((1 + cw) / 2).toFloat(), ((1 + ch) / 2).toFloat())
            val cropW = srcW * cw
            val cropH = srcH * ch
            val scale = minOf(1.0, maxLongEdge / max(cropW, cropH))
            var iw = (cropW * scale).roundToInt().coerceAtLeast(2)
            var ih = (cropH * scale).roundToInt().coerceAtLeast(2)
            if (even) { iw -= iw % 2; ih -= ih % 2 }
            var ow = iw
            var oh = ih
            var rect = floatArrayOf(0f, 0f, 1f, 1f)
            if (look.filmEdge) {
                val short = minOf(iw, ih)
                var bx = (short * 0.035).roundToInt()
                var by = (short * 0.09).roundToInt()
                if (!landscape) { val t = bx; bx = by; by = t }
                ow = iw + 2 * bx
                oh = ih + 2 * by
                if (even) { ow -= ow % 2; oh -= oh % 2 }
                rect = floatArrayOf(bx.toFloat() / ow, by.toFloat() / oh, (bx + iw).toFloat() / ow, (by + ih).toFloat() / oh)
            }
            return Framing(crop, iw, ih, ow, oh, rect)
        }

        /** Upright image uv (y up) -> texture uv of a bitmap uploaded top row first. */
        fun bitmapUvTransform(crop: FloatArray): FloatArray = floatArrayOf(
            crop[2] - crop[0], 0f, crop[0],
            0f, -(crop[3] - crop[1]), 1f - crop[1],
            0f, 0f, 1f,
        )

        /** Crop as a row-major uv transform (display uv -> cropped display uv). */
        fun cropTransform(crop: FloatArray): FloatArray = floatArrayOf(
            crop[2] - crop[0], 0f, crop[0],
            0f, crop[3] - crop[1], crop[1],
            0f, 0f, 1f,
        )

        /** Display uv -> encoded-frame uv for a video with clockwise rotation metadata. */
        fun rotationTransform(degrees: Int): FloatArray = when ((degrees % 360 + 360) % 360) {
            90 -> floatArrayOf(0f, -1f, 1f, 1f, 0f, 0f, 0f, 0f, 1f)
            180 -> floatArrayOf(-1f, 0f, 1f, 0f, -1f, 1f, 0f, 0f, 1f)
            270 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, 1f, 0f, 0f, 1f)
            else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        }

        /** SurfaceTexture 4x4 (column-major) -> row-major 3x3 uv transform. */
        fun surfaceTextureTransform(m: FloatArray): FloatArray = floatArrayOf(
            m[0], m[4], m[12],
            m[1], m[5], m[13],
            0f, 0f, 1f,
        )
    }
}
