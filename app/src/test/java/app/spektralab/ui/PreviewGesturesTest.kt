package app.spektralab.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h600dp-mdpi")
class PreviewGesturesTest {
    @get:Rule val rule = createComposeRule()

    @Test fun simultaneousPinchAndPanTracksTheMovingCentroid() {
        var zoom = PreviewZoom(2f, -0.5f, -0.5f)
        var releasedAt = Offset.Zero
        rule.setContent {
            Box(Modifier.size(300.dp).testTag("preview").previewGestures(
                zoom = { zoom }, onZoom = { zoom = it },
                onRelease = { anchor, _ -> releasedAt = anchor },
                onDoubleTap = {}, onHold = {},
            ))
        }
        rule.onNodeWithTag("preview").performTouchInput {
            down(0, Offset(120f, 120f))
            down(1, Offset(180f, 180f))
            // Centroid moves (.5,.5) -> (.6,.6) as separation doubles.
            updatePointerTo(0, Offset(120f, 120f))
            updatePointerTo(1, Offset(240f, 240f))
            move()
            up(0)
            up(1)
        }
        rule.runOnIdle {
            assertEquals(4f, zoom.scale, 0.001f)
            // The content at (.5,.5) before the gesture remains under the centroid.
            assertEquals(0.6f, 0.5f * zoom.scale + zoom.x, 0.001f)
            assertEquals(0.6f, 0.5f * zoom.scale + zoom.y, 0.001f)
        }
    }
}
