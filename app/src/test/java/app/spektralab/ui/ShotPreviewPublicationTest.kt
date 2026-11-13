package app.spektralab.ui

import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import app.spektralab.SpektraApp
import app.spektralab.data.RawMemoryGate
import app.spektralab.data.RawPreview
import app.spektralab.engine.Half
import app.spektralab.raw.LinearImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShotPreviewPublicationTest {
    @get:Rule val rule = createComposeRule()

    @Test fun firstBurstFrameIsInLibraryWithQuickPixelsBeforeMergeUnlocks() {
        val app = ApplicationProvider.getApplicationContext<SpektraApp>()
        val file = File(app.cacheDir, "preview-before-merge.dng").apply { writeText("no sensor decode available") }
        val uri = Uri.fromFile(file)
        val pixels = ByteBuffer.allocateDirect(8 * 6 * 8).order(ByteOrder.nativeOrder())
        for (i in 0 until 8 * 6) for (c in 0..3) pixels.putShort(i * 8 + c * 2, Half.fromFloat(if (c == 3) 1f else 0.25f))
        val preview = RawPreview(LinearImage(8, 6, pixels), 3056, 4064, 1.25f)
        val state = AppState(app)
        val store = ViewModelStore().apply { put("state", state) }
        val burst = RawMemoryGate.acquire()
        try {
            rule.runOnIdle { state.shooter.onSaved(uri, 0.5f, preview) }
            rule.waitUntil(timeoutMillis = 5_000) {
                // Robolectric pauses the Android looper; the IO -> Main continuation
                // needs its queue driven independently of Compose's virtual clock.
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                app.library.items.value.any { it.uri == uri.toString() }
            }
            val item = app.library.items.value.first { it.uri == uri.toString() }
            assertEquals(1.75f, item.autoEv, 0f)
            assertEquals(3056, item.width); assertEquals(4064, item.height)
            assertEquals(0.25f, app.quick.load(item)!!.linear!!.red(0, 0), 0f)
            assertFalse("a burst must not auto-print before completion", app.exports.isRunning())
            app.quick.forget(listOf(item.id))
            app.library.remove(listOf(item.id))
        } finally {
            burst.close()
            rule.runOnIdle { store.clear() }
            file.delete()
        }
    }
}
