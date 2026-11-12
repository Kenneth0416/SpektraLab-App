package app.spektralab.ui

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import app.spektralab.data.SourceImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoSourceEffectsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun replacingUriUnderSameIdReloadsBothSourcesAndRejectsLateOldDecodes() {
        val first = 7L to "content://photos/first"
        val merged = 7L to "content://photos/merged"
        val shown = mutableStateOf(first)
        val completions = listOf(first, merged).flatMap { key -> listOf(false, true).map { key to it } }
            .associateWith { CompletableDeferred<SourceImage?>() }
        val starts = mutableListOf<Pair<Pair<Long, String>, Boolean>>()
        val installed = mutableListOf<Pair<Pair<Long, String>, Boolean>>()
        val image = SourceImage(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888), null)
        rule.setContent {
            val key = shown.value
            PhotoSourceEffects(key, zoomed = true, worthFull = true,
                load = { full ->
                    starts += key to full
                    // Model a native decoder that cannot be interrupted mid-decode.
                    withContext(NonCancellable) { completions.getValue(key to full).await() }
                },
                onSource = {}, onPreview = { _, k -> installed += k to false },
                onFull = { _, k -> installed += k to true }, onError = { fail("unexpected decode failure") })
        }
        rule.runOnIdle { assertEquals(2, starts.size); shown.value = merged }
        rule.runOnIdle {
            assertEquals(4, starts.size)
            completions.getValue(merged to false).complete(image)
            completions.getValue(merged to true).complete(image)
        }
        rule.runOnIdle {
            assertEquals(setOf(merged to false, merged to true), installed.toSet())
            completions.getValue(first to false).complete(image)
            completions.getValue(first to true).complete(image)
        }
        rule.runOnIdle { assertEquals(2, installed.size) }
    }

    @Test fun fullDecodeWaitsForZoomAndSurvivesZoomOutWithoutDuplicateRequests() {
        val zoomed = mutableStateOf(false)
        val full = CompletableDeferred<SourceImage?>()
        var fullLoads = 0
        var installedFull = 0
        val image = SourceImage(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888), null)
        rule.setContent {
            PhotoSourceEffects(8L to "content://photos/current", zoomed.value, worthFull = true,
                load = { highRes -> if (highRes) { fullLoads++; full.await() } else image },
                onSource = {}, onPreview = { _, _ -> }, onFull = { _, _ -> installedFull++ }, onError = {})
        }
        rule.runOnIdle { assertEquals(0, fullLoads); zoomed.value = true }
        rule.runOnIdle { assertEquals(1, fullLoads); zoomed.value = false }
        rule.runOnIdle { full.complete(image) }
        rule.runOnIdle { assertEquals(1, installedFull); zoomed.value = true }
        rule.runOnIdle { assertEquals(1, fullLoads) }
    }

    @Test fun quickRenditionShowsFirstThenIsRefinedAndIsSkippedWhenLate() {
        val small = SourceImage(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888), null)
        val big = SourceImage(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888), null)
        val shown = mutableStateOf(1L to "content://photos/a")
        val quick = mapOf(1L to CompletableDeferred<SourceImage?>(), 2L to CompletableDeferred())
        val preview = mapOf(1L to CompletableDeferred<SourceImage?>(), 2L to CompletableDeferred())
        val events = mutableListOf<String>()
        rule.setContent {
            val key = shown.value
            PhotoSourceEffects(key, zoomed = false, worthFull = false,
                load = { preview.getValue(key.first).await() },
                onSource = {}, onPreview = { src, k -> events += "${k.first} preview ${src.width}" },
                onFull = { _, _ -> }, onError = { events += "error" },
                quick = { quick.getValue(key.first).await() },
                onRefine = { src, k -> events += "${k.first} refine ${src.width}" })
        }
        // quick first: shown at once, then swapped for the preview without a second "preview"
        rule.runOnIdle { quick.getValue(1L).complete(small) }
        rule.runOnIdle { assertEquals(listOf("1 preview 2"), events); preview.getValue(1L).complete(big) }
        rule.runOnIdle { assertEquals(listOf("1 preview 2", "1 refine 4"), events); events.clear(); shown.value = 2L to "content://photos/b" }
        // preview first: the late quick rendition never replaces it
        rule.runOnIdle { preview.getValue(2L).complete(big) }
        rule.runOnIdle { quick.getValue(2L).complete(small) }
        rule.runOnIdle { assertEquals(listOf("2 preview 4"), events) }
    }
}
