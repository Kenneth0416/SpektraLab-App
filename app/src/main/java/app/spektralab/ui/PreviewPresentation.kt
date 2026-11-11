package app.spektralab.ui

import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.runtime.withFrameNanos
import app.spektralab.camera.FilmViewfinder
import kotlinx.coroutines.withTimeoutOrNull

private fun View.previewTexture(): TextureView? {
    if (this is TextureView) return this
    if (this is ViewGroup) for (i in 0 until childCount) {
        getChildAt(i).previewTexture()?.let { return it }
    }
    return null
}

/** Keep the curtain closed until TextureView has latched a new, fully blurred buffer.
 * GL submission alone is insufficient: the preview may still be displaying a sharp buffer.
 * On timeout the caller keeps the opaque curtain, rather than revealing stale content.
 */
internal suspend fun PreviewView.awaitBlurredPreview(viewfinder: FilmViewfinder, after: Long): Boolean =
    withTimeoutOrNull(2_500L) {
        var target: Long? = null
        while (true) {
            withFrameNanos { }
            if (target == null) {
                viewfinder.blurredTimestamp.takeIf { it > after }?.let { target = it }
            }
            val timestamp = previewTexture()?.surfaceTexture?.timestamp
            val expected = target
            if (expected != null && timestamp != null && timestamp >= expected) break
        }
        // Leave the covering layer in place through the frame which observed the latch.
        withFrameNanos { }
        true
    } ?: false
