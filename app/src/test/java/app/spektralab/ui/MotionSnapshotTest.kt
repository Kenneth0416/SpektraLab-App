package app.spektralab.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders animated components at fixed points of their animation and writes PNGs to
 * build/motion-snapshots, so motion can be reviewed without a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-xhdpi", sdk = [34])
class MotionSnapshotTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val out = File("build/motion-snapshots").apply { mkdirs() }

    private fun shot(name: String): Bitmap {
        // software draw of the window (PixelCopy needs a running frame clock)
        val view = rule.activity.window.decorView
        val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(full)) }
        val b = rule.onNodeWithTag("stage").fetchSemanticsNode().boundsInRoot
        val bmp = Bitmap.createBitmap(full, b.left.toInt(), b.top.toInt().coerceAtLeast(0), b.width.toInt(), b.height.toInt())
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }

    private fun redPixels(b: Bitmap): Int {
        var n = 0
        for (y in 0 until b.height step 2) for (x in 0 until b.width step 2) {
            val p = b.getPixel(x, y)
            if (Color(p).red > 0.6f && Color(p).green < 0.35f) n++
        }
        return n
    }

    @Test fun crayonCircleDrawsItselfThenNumberPops() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.testTag("stage").background(Pal.paper).padding(12.dp)) {
                Box(Modifier.size(180.dp, 120.dp).background(Color(0xFF3A2C22))) { CrayonCircle(3, Modifier.size(180.dp, 120.dp)) }
            }
        }
        rule.mainClock.advanceTimeBy(60)
        val early = redPixels(shot("crayon_060ms"))
        rule.mainClock.advanceTimeBy(140)
        val mid = redPixels(shot("crayon_200ms"))
        rule.mainClock.advanceTimeBy(900)
        val done = redPixels(shot("crayon_done"))
        println("crayon red pixels: $early -> $mid -> $done")
        assertTrue("stroke grows", early < mid && mid < done)
    }

    @Test fun rulerGlidesBackOnReset() {
        rule.mainClock.autoAdvance = false
        var v by mutableStateOf(1.5)
        var prev = 0
        rule.setContent {
            Column(Modifier.testTag("stage").background(Pal.darkPanel).width(360.dp).padding(16.dp)) {
                RulerSlider("胶片曝光", v, -3.0..3.0, 0.1, 0.0, Color(0xFFE9C48A), { signed(it, 1, " EV") },
                    minus = Pal.yellowFilter, plus = Pal.blueShift) { v = it }
                Row { Chip("Portra Endura", true, Color(0xFFE9C48A)) {}; Chip("2383", false, Color(0xFFE9C48A)) {} }
                ToggleRow("日期后背", true, Color(0xFFE9C48A)) {}
            }
        }
        rule.mainClock.advanceTimeBy(500)
        shot("ruler_at_1_5")
        rule.runOnUiThread { v = 0.0 }
        for (t in listOf(48, 96, 160, 320)) {
            rule.mainClock.advanceTimeBy(if (t == 48) 48L else (t - prev).toLong())
            prev = t
            shot("ruler_reset_${t}ms")
        }
        rule.mainClock.advanceTimeBy(1500)
        shot("ruler_reset_done")
    }

    @Test fun filmAdvanceGuidanceWaitsThenDisappearsWhenReady() {
        rule.mainClock.autoAdvance = false
        var cocked by mutableStateOf(false)
        var enabled by mutableStateOf(true)
        rule.setContent {
            Box(Modifier.testTag("stage").background(Color.Black).padding(16.dp)) {
                FilmAdvanceLever(cocked, Color.Red, 0, 0, Modifier.testTag("lever"), guidanceEnabled = enabled) { cocked = true }
            }
        }
        rule.mainClock.advanceTimeBy(100)
        val initial = redPixels(shot("guide_initial"))
        rule.mainClock.advanceTimeBy(9000)
        assertTrue("no guidance before ten seconds", redPixels(shot("guide_9s")) == initial)
        rule.mainClock.advanceTimeBy(2000)
        assertTrue("visible gesture after ten seconds", redPixels(shot("guide_11s")) > initial + 40)
        rule.onNodeWithTag("lever").performTouchInput {
            down(Offset(width * 0.12f, height * 0.8f))
            moveTo(Offset(width * 0.4f, height * 0.65f), delayMillis = 200)
            up()
        }
        rule.mainClock.advanceTimeBy(1500)
        assertTrue("partial drag hides guidance without advancing film", !cocked && redPixels(shot("guide_after_drag")) == initial)
        rule.mainClock.advanceTimeBy(11000)
        assertTrue("idle partial drag gets a new demonstration", redPixels(shot("guide_after_retry")) > initial + 40)
        rule.runOnUiThread { enabled = false }
        rule.mainClock.advanceTimeBy(100)
        assertTrue("auto wind or overlay hides guidance", redPixels(shot("guide_disabled")) == initial)
        rule.runOnUiThread { enabled = true }
        rule.mainClock.advanceTimeBy(9000)
        assertTrue("reenabling restarts the idle interval", redPixels(shot("guide_restarted")) == initial)
        rule.runOnUiThread { cocked = true }
        rule.mainClock.advanceTimeBy(11000)
        assertTrue("ready film needs no guidance", redPixels(shot("guide_ready")) < initial)
    }

    @Test fun filmAdvanceLeverStroke() {
        rule.mainClock.autoAdvance = false
        var stroke by mutableStateOf(0)
        var cocked by mutableStateOf(false)
        var frame by mutableStateOf(12)
        rule.setContent {
            Row(Modifier.testTag("stage").background(Pal.dark).padding(12.dp)) {
                FrameCounter(frame)
                FilmAdvanceLever(cocked, Color(0xFFE9C48A), 0, stroke) { cocked = true; frame++ }
            }
        }
        rule.mainClock.advanceTimeBy(100)
        shot("lever_rest")
        rule.runOnUiThread { stroke = 1 }
        rule.mainClock.advanceTimeBy(160)
        shot("lever_mid")
        rule.mainClock.advanceTimeBy(260)
        shot("lever_full")
        rule.mainClock.advanceTimeBy(1500)
        shot("lever_back")
        assertTrue("a full stroke advances the film", cocked && frame == 13)
    }
}
