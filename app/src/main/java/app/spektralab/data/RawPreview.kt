package app.spektralab.data

import app.spektralab.raw.LinearImage
import app.spektralab.raw.MergedRaw
import app.spektralab.raw.RawDeveloper
import app.spektralab.raw.RawImage

/**
 * A bounded, scene-linear rendition prepared while the camera already owns its RAW
 * memory permit. It retains no sensor arrays and no permit, so importing, thumbnails
 * and the editor can use it even while the remainder of a burst is being merged.
 */
class RawPreview internal constructor(
    val image: LinearImage,
    val originalWidth: Int,
    val originalHeight: Int,
    val autoEv: Float,
) {
    fun source() = SourceImage(null, LinearImage(image.width, image.height,
        image.halfRgba.duplicate().order(image.halfRgba.order())), originalWidth, originalHeight)

    companion object {
        /** Keep metering identical to the existing 512-edge import development. */
        fun develop(raw: RawImage): RawPreview {
            val linear = RawDeveloper.develop(raw, 512, fast = true)
            val ev = Media.autoExposure(linear)
            return RawPreview(QuickSources.shrink(linear, QuickSources.EDGE), raw.uprightWidth, raw.uprightHeight, ev)
        }

        /**
         * Reuse the merged samples before they are released, avoiding a second full
         * decode of the saved DNG. The full export still develops the saved source.
         * The proxy precedes the DNG writer's half-float quantization.
         */
        fun merged(merged: MergedRaw, reference: RawImage): RawPreview = develop(RawImage(
            merged.width, merged.height, merged.rgb, reference.cfa, FloatArray(reference.cfa.size), 65535f,
            0, 0, merged.width, merged.height, reference.wb, reference.camToSrgb, reference.orientation,
            reference.baselineExposure, merged.gainMaps, reference.make, reference.model,
            merged.noise?.first?.toFloat(), merged.noise?.second?.toFloat() ?: 0f, linear = true,
        ))
    }
}
