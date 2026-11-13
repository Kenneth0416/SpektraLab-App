package app.spektralab.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.spektralab.update.AppRelease
import app.spektralab.update.UpdateManager
import app.spektralab.update.UpdatePreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Exercise the settings → request → prompt → browser flow with real persisted preferences. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "zh-rCN-w400dp-h800dp-xhdpi", sdk = [34])
class UpdateFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @After fun close() { scope.cancel() }

    @Test fun automaticAndManualChecksShareTheUiAndPersistLater() {
        val release = AppRelease.parse("""{
          "schemaVersion":1,"applicationId":"app.spektralab","versionCode":2,"versionName":"0.1.1",
          "minSdk":29,"downloadUrl":"https://example.com/downloads/Silvercam-Native.apk",
          "releaseNotes":{"en":"Automatic update checks.","zh-Hans":"新增自动检查更新，以及设置中的手动检查入口。",
            "zh-Hant":"新增自動檢查更新，以及設定中的手動檢查入口。"}
        }""", "app.spektralab", "https://example.com/updates/android.json")
        val store = UpdatePreferences(rule.activity)
        val response = CompletableDeferred<AppRelease>()
        var requests = 0
        val manager = UpdateManager(scope, store, 1, 34, { requests++; response.await() }, { 10_000L })
        rule.setContent {
            val status by manager.status.collectAsState()
            val prompt by manager.prompt.collectAsState()
            MaterialTheme {
                SettingsContent(AppLanguage.SIMPLIFIED_CHINESE, false, true, {}, {}, {}, {}, "0.1.0", status) { manager.check(true) }
                prompt?.let { UpdateDialog(it.release, manager::dismissPrompt, manager::dismissPrompt) }
            }
        }
        rule.runOnIdle { manager.check() }
        rule.onNodeWithTag("check_updates").performScrollTo().assertIsNotEnabled()
        rule.runOnIdle { response.complete(release) }
        rule.onNodeWithTag("update_dialog").assertIsDisplayed()
        rule.onNode(hasText("发现新版本 0.1.1") and hasAnyAncestor(hasTestTag("update_dialog"))).assertIsDisplayed()
        snapshot(ShadowDialog.getLatestDialog().window!!.decorView, "available-zh-Hans")
        rule.onNodeWithTag("update_later").performClick()
        rule.onNodeWithTag("update_dialog").assertDoesNotExist()
        assertEquals(10_000L, UpdatePreferences(rule.activity).lastAttemptAt)
        rule.runOnIdle { manager.check() }
        assertEquals(1, requests)
        snapshot(rule.activity.window.decorView, "settings-zh-Hans")
        rule.onNodeWithTag("check_updates").performScrollTo().performClick()
        rule.onNodeWithTag("update_dialog").assertIsDisplayed()
        rule.onNodeWithTag("update_open").performClick()
        val opened = shadowOf(rule.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals(release.downloadUrl, opened.data.toString())
        rule.onNodeWithTag("update_dialog").assertDoesNotExist()
        assertEquals(2, requests)
        val restarted = UpdateManager(scope, UpdatePreferences(rule.activity), 1, 34, { requests++; release }, { 10_001L })
        rule.runOnIdle { restarted.check() }
        assertEquals(2, requests)
    }

    private fun snapshot(view: android.view.View, name: String) {
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(image)) }
        val file = File("build/update-snapshots/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
