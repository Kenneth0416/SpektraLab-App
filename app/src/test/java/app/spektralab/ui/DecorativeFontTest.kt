package app.spektralab.ui

import android.app.Application
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import app.spektralab.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h900dp-mdpi", sdk = [34])
class DecorativeFontTest {
    @get:Rule val rule = createComposeRule()

    private fun checkTitle(locale: String) {
        val app = RuntimeEnvironment.getApplication()
        val configuration = Configuration(app.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(locale))
        }
        val context = app.createConfigurationContext(configuration)
        val text = context.getString(R.string.coffee_title) + " · " + context.getString(R.string.empty_lightbox)
        val face = ResourcesCompat.getFont(app, R.font.lxgw_wenkai_regular)
        assertNotNull(face)
        assertEquals("The embedded face is real Regular, not synthesized Bold", 400, face!!.weight)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                androidx.compose.foundation.layout.Box(Modifier.width(320.dp)) {
                    Text(text, fontFamily = Fonts.hand, fontWeight = FontWeight.Normal,
                        fontSize = 32.sp, modifier = Modifier.fillMaxWidth().testTag("decorative_title"))
                }
            }
        }
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("decorative_title").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(1, results.size)
        assertTrue("Large text must actually reflow", results.single().lineCount > 1)
        assertFalse("All lines remain visible at double font scale", results.single().hasVisualOverflow)
        // The independent cmap gate verifies primary-face glyph coverage; hasGlyph
        // here would include Android fallback and cannot prove absence of missing glyphs.
    }

    @Test fun traditionalDecorativeTitlesReflowWithRealRegular() = checkTitle("zh-TW")
    @Test fun simplifiedDecorativeTitlesReflowWithRealRegular() = checkTitle("zh-CN")
}
