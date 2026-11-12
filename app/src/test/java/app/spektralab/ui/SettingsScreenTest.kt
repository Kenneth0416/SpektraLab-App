package app.spektralab.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h740dp-mdpi")
class SettingsScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun content(
        language: AppLanguage = AppLanguage.SYSTEM,
        onBack: () -> Unit = {},
        onLanguage: (AppLanguage) -> Unit = {},
        onGray18: (Boolean) -> Unit = {},
        onReveal: (Boolean) -> Unit = {},
    ) {
        rule.setContent {
            SettingsContent(language, gray18 = false, reveal = true, onBack, onLanguage, onGray18, onReveal)
        }
    }

    @Test
    fun allLanguageChoicesAreVisibleAndSelectable() {
        var selected = AppLanguage.SYSTEM
        content(onLanguage = { selected = it })
        rule.onNodeWithText("English").assertIsDisplayed()
        rule.onNodeWithText("简体中文").assertIsDisplayed()
        rule.onNodeWithText("繁體中文").assertIsDisplayed()
        rule.onNodeWithTag("language_system").assertIsSelected()
        rule.onNodeWithTag("language_en").performClick()
        assertEquals(AppLanguage.ENGLISH, selected)
        rule.onNodeWithTag("language_zh-Hans").performClick()
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, selected)
        rule.onNodeWithTag("language_zh-Hant").performClick()
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, selected)
    }

    @Test
    fun backAndDisplayControlsInvokeCallbacks() {
        var back = false
        var gray: Boolean? = null
        var reveal: Boolean? = null
        content(onBack = { back = true }, onGray18 = { gray = it }, onReveal = { reveal = it })
        rule.onNodeWithTag("settings_back").performClick()
        rule.onNodeWithTag("settings_gray18").performClick()
        rule.onNodeWithTag("settings_reveal").performClick()
        assertTrue(back)
        assertEquals(true, gray)
        assertEquals(false, reveal)
    }

    @Test
    @Config(application = Application::class, qualifiers = "w900dp-h800dp-mdpi")
    fun wideLayoutKeepsSettingsAndLanguageChoices() {
        content(language = AppLanguage.TRADITIONAL_CHINESE)
        rule.onNodeWithTag("settings_screen").assertIsDisplayed()
        rule.onNodeWithTag("language_zh-Hant").assertIsSelected()
        rule.onNodeWithTag("language_system").assertIsDisplayed()
    }
}
