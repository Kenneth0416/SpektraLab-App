package app.spektralab.ui

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h1000dp-mdpi", sdk = [34])
class ActionGroupTest {
    @get:Rule val rule = createComposeRule()

    private val labels = listOf("Paste settings from another photo", "Recipes", "Export", "Remove")

    private fun show(scale: Float, chip: Boolean = false, clicked: (Int) -> Unit = {}) {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                ActionGroup(labels.size, Modifier.width(272.dp)) { index, cell ->
                    if (chip) {
                        Chip(labels[index], false, Pal.paper, cell.testTag("action_$index")) { clicked(index) }
                    } else {
                        ActionButton(labels[index], Pal.ink, cell.testTag("action_$index"), fill = Pal.paper) { clicked(index) }
                    }
                }
            }
        }
    }

    private fun assertCompleteLabels(values: List<String> = labels) {
        values.forEach { label ->
            val results = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(label, useUnmergedTree = true)
                .assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertTrue("Text layout is available for $label", results.isNotEmpty())
            assertFalse("No clipped text for $label: size=${results.single().size}, paragraph=${results.single().multiParagraph.width}x${results.single().multiParagraph.height}, lines=${results.single().lineCount}, constraints=${results.single().layoutInput.constraints}", results.single().hasVisualOverflow)
        }
    }

    @Test fun narrowBarUsesTwoEqualHeightColumnsAndKeepsEveryActionClickable() {
        val clicked = mutableListOf<Int>()
        show(1f, clicked = { clicked += it })
        val bounds = (0..3).map { rule.onNodeWithTag("action_$it").fetchSemanticsNode().boundsInRoot }
        assertEquals(bounds[0].top, bounds[1].top, 0.1f)
        assertEquals(bounds[0].height, bounds[1].height, 0.1f)
        assertEquals(bounds[0].width, bounds[1].width, 0.1f)
        assertTrue(bounds[2].top >= bounds[0].bottom)
        bounds.forEach { assertTrue(it.height >= 48f); assertTrue(it.right <= 272f) }
        assertCompleteLabels()
        (0..3).forEach { rule.onNodeWithTag("action_$it").performClick() }
        assertEquals(listOf(0, 1, 2, 3), clicked)
    }

    @Test fun doubleFontScaleStacksWithoutClippingOrShrinkingText() {
        show(2f)
        val bounds = (0..3).map { rule.onNodeWithTag("action_$it").fetchSemanticsNode().boundsInRoot }
        bounds.zipWithNext().forEach { (first, next) -> assertTrue(next.top >= first.bottom) }
        bounds.forEach { assertEquals(272f, it.width, 0.1f); assertTrue(it.height >= 48f) }
        assertCompleteLabels()
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(labels.first(), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue("Large labels actually wrap", results.single().lineCount > 1)
    }

    @Test fun bilingualExportActionsGrowTogetherAtLargerTextSize() {
        val translated = listOf("分享…", "Save to gallery / 保存到相册")
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                ActionGroup(2, Modifier.width(320.dp)) { index, cell ->
                    ActionButton(translated[index], Pal.ink, cell.testTag("export_$index")) {}
                }
            }
        }
        val first = rule.onNodeWithTag("export_0").fetchSemanticsNode().boundsInRoot
        val second = rule.onNodeWithTag("export_1").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, second.top, 0.1f)
        assertEquals(first.height, second.height, 0.1f)
        assertTrue(first.height > 48f)
        assertCompleteLabels(translated)
    }

    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    @Test fun standaloneEditorChipsKeepContentWidthInsteadOfFillingTheFlowRow() {
        rule.setContent {
            androidx.compose.foundation.layout.FlowRow(Modifier.width(320.dp)) {
                Chip("4K", false, Pal.paper, Modifier.testTag("compact_0")) {}
                Chip("1080p", false, Pal.paper, Modifier.testTag("compact_1")) {}
            }
        }
        val first = rule.onNodeWithTag("compact_0").fetchSemanticsNode().boundsInRoot
        val second = rule.onNodeWithTag("compact_1").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, second.top, 0.1f)
        assertTrue(first.width < 160f)
        assertTrue(second.width < 160f)
        assertTrue(first.height >= 48f)
        assertCompleteLabels(listOf("4K", "1080p"))
    }

    @Test fun chipGroupAlsoHasEqualRowHeightsAndCompleteLongLabels() {
        show(1f, chip = true)
        val first = rule.onNodeWithTag("action_0").fetchSemanticsNode().boundsInRoot
        val second = rule.onNodeWithTag("action_1").fetchSemanticsNode().boundsInRoot
        assertEquals(first.height, second.height, 0.1f)
        assertTrue(first.height >= 48f)
        assertCompleteLabels()
    }
}
