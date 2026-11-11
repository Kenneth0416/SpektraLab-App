package app.spektralab.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Placement of the editor parts in each phone / foldable mode (density 1, so px == dp). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w900dp-h800dp-mdpi")
class EditorLayoutTest {
    @get:Rule val rule = createComposeRule()

    private fun layout(mode: EditorMode, hinge: Rect?, w: Int = 900, h: Int = 800) {
        rule.setContent {
            Box(Modifier.size(w.dp, h.dp)) {
                EditorLayout(mode, hinge, Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().height(40.dp).testTag("top"))
                    Box(Modifier.fillMaxSize().testTag("preview"))
                    Box(Modifier.fillMaxWidth().height(30.dp).testTag("actions"))
                    Box(Modifier.fillMaxSize().testTag("tools"))
                }
            }
        }
    }

    private fun b(tag: String): DpRect = rule.onNodeWithTag(tag).getBoundsInRoot()

    @Test fun compactStacksToolsAtBottom() {
        layout(EditorMode.COMPACT, null, 400, 800)
        assertEquals(40f, b("preview").top.value, 0.5f)
        assertEquals(800f, b("tools").bottom.value, 0.5f)
        assertEquals(b("actions").bottom.value, b("tools").top.value, 0.5f)
        assertEquals(400f, b("preview").right.value, 0.5f)
    }

    @Test fun unfoldedPutsToolsInSidePane() {
        layout(EditorMode.SIDE, null)
        val p = b("preview"); val t = b("tools")
        assertEquals(p.right.value, t.left.value, 0.5f)
        assertEquals(900f, t.right.value, 0.5f)
        assertTrue("side pane width ${t.right - t.left}", (t.right - t.left).value in 340f..460f)
        assertEquals(800f, t.bottom.value, 0.5f)
        assertEquals(800f, b("actions").bottom.value, 0.5f)
    }

    @Test fun bookPostureSplitsAtVerticalHinge() {
        layout(EditorMode.BOOK, Rect(440f, 0f, 460f, 800f))
        assertEquals(440f, b("preview").right.value, 0.5f)
        assertEquals(460f, b("tools").left.value, 0.5f)
    }

    @Test fun tabletopPutsPreviewAboveHinge() {
        layout(EditorMode.TABLETOP, Rect(0f, 390f, 900f, 410f))
        assertEquals(390f, b("preview").bottom.value, 0.5f)
        assertEquals(410f, b("actions").top.value, 0.5f)
        assertEquals(800f, b("tools").bottom.value, 0.5f)
        assertTrue(b("tools").top.value >= 410f)
    }
}
