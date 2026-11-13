package app.spektralab.ui

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.spektralab.update.AppRelease
import app.spektralab.update.UpdateStatus
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h740dp-mdpi")
class UpdateUiTest {
    @get:Rule val rule = createComposeRule()
    private val release = AppRelease(2, "0.1.1", 29, "https://example.com/native.apk",
        mapOf("en" to "Improved updates", "zh-Hans" to "改善更新", "zh-Hant" to "改善更新繁體"))

    @Test fun manualCheckIsReachableInFullSettingsAndShowsInstalledVersion() {
        var checked = 0
        rule.setContent {
            SettingsContent(AppLanguage.SYSTEM, false, true, {}, {}, {}, {},
                versionName = "0.1.0", onCheckUpdates = { checked++ })
        }
        rule.onNodeWithTag("check_updates").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithText("Current version 0.1.0").assertIsDisplayed()
        assertEquals(1, checked)
    }

    @Test fun checkingDisablesDuplicateClicks() {
        rule.setContent { UpdateSettings("0.1.0", UpdateStatus.Checking) {} }
        rule.onNodeWithTag("check_updates").assertIsNotEnabled()
        rule.onNodeWithTag("update_status").assertTextEquals("Checking for updates…")
    }

    @Test fun offlineStatusAllowsRetry() {
        var retries = 0
        rule.setContent { UpdateSettings("0.1.0", UpdateStatus.Failed) { retries++ } }
        rule.onNodeWithTag("update_status").assertTextEquals("Could not check for updates. Check your connection and try again.")
        rule.onNodeWithTag("check_updates").assertIsEnabled().performClick()
        assertEquals(1, retries)
    }

    @Test fun dialogShowsNotesAndBothActions() {
        var opens = 0
        var later = 0
        rule.setContent { UpdateDialogContent(release, { later++ }) { opens++; true } }
        rule.onNodeWithText("New version 0.1.1 available").assertIsDisplayed()
        rule.onNodeWithText("Improved updates").assertIsDisplayed()
        rule.onNodeWithTag("update_open").performClick()
        rule.onNodeWithTag("update_later").performClick()
        assertEquals(1, opens)
        assertEquals(1, later)
    }

    @Test fun missingBrowserLeavesDialogDismissible() {
        var later = false
        rule.setContent { UpdateDialogContent(release, { later = true }) { false } }
        rule.onNodeWithTag("update_open").performClick()
        rule.onNodeWithTag("update_open_failed").assertIsDisplayed()
        rule.onNodeWithTag("update_later").performClick()
        assertTrue(later)
    }

    @Test @Config(qualifiers = "zh-rCN-w360dp-h740dp-mdpi")
    fun simplifiedChineseUsesChineseNotesAndActions() {
        rule.setContent { UpdateDialogContent(release, {}) { true } }
        rule.onNodeWithText("发现新版本 0.1.1").assertIsDisplayed()
        rule.onNodeWithText("改善更新").assertIsDisplayed()
        rule.onNodeWithText("稍后提醒").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "zh-rTW-w360dp-h740dp-mdpi")
    fun traditionalChineseUsesChineseNotesAndActions() {
        rule.setContent { UpdateDialogContent(release, {}) { true } }
        rule.onNodeWithText("發現新版本 0.1.1").assertIsDisplayed()
        rule.onNodeWithText("改善更新繁體").assertIsDisplayed()
        rule.onNodeWithText("稍後提醒").assertIsDisplayed()
    }
}
