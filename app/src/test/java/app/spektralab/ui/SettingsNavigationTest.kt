package app.spektralab.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.appcompat.app.AppCompatDelegate
import app.spektralab.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class SettingsNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    @Config(sdk = [29])
    fun languageChoicesApplyImmediatelyAndUpdateActivityResources() {
        rule.onNodeWithTag("settings_button").performClick()
        rule.onNodeWithTag("language_zh-Hans").performClick()
        rule.activityRule.scenario.recreate()
        rule.onNodeWithTag("settings_screen").assertIsDisplayed()
        rule.onNodeWithText("设置").assertIsDisplayed()
        assertEquals("zh-Hans", AppCompatDelegate.getApplicationLocales().toLanguageTags())

        rule.onNodeWithTag("language_zh-Hant").performClick()
        rule.activityRule.scenario.recreate()
        rule.onNodeWithText("設定").assertIsDisplayed()
        assertEquals("zh-Hant", AppCompatDelegate.getApplicationLocales().toLanguageTags())

        rule.onNodeWithTag("language_en").performClick()
        rule.activityRule.scenario.recreate()
        rule.onNodeWithText("Settings").assertIsDisplayed()
        assertEquals("en", AppCompatDelegate.getApplicationLocales().toLanguageTags())
    }

    @Test
    fun libraryOpensSettingsAndBackReturnsToLibrary() {
        rule.onNodeWithTag("settings_button").performClick()
        rule.onNodeWithTag("settings_screen").assertIsDisplayed()
        rule.onNodeWithTag("language_system").assertIsDisplayed()
        rule.onNodeWithTag("settings_back").performClick()
        rule.onNodeWithText("Library").assertIsDisplayed()
        rule.onNodeWithTag("settings_button").assertIsDisplayed()
    }
}
