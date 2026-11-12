package app.spektralab.ui

import android.app.Application
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.spektralab.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h740dp-mdpi")
class CoffeePromptTest {
    @get:Rule val rule = createComposeRule()

    private val context: Application
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearStore() {
        context.getSharedPreferences("coffee", Application.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun staysQuietUntilTheTenthPhotoAndSnoozeDoesNotRetireIt() {
        val store = CoffeePromptStore(context)
        assertFalse(store.recordImportedPhotos(0))
        assertFalse(store.recordImportedPhotos(-3))
        assertFalse(store.recordImportedPhotos(9))
        assertEquals(9, store.importedPhotos())
        assertTrue(store.recordImportedPhotos(1))
        assertEquals(10, store.importedPhotos())

        val reopened = CoffeePromptStore(context)
        assertEquals(10, reopened.importedPhotos())
        assertFalse(reopened.alreadyPrompted())
        // Snoozing does not write the permanent flag, so a later import can cross again.
        assertFalse(reopened.recordImportedPhotos(4))
        assertEquals(14, reopened.importedPhotos())
    }

    @Test
    fun aSingleBatchThatCrossesTenPromptsOnceUntilDismissedForever() {
        val store = CoffeePromptStore(context)
        assertFalse(store.recordImportedPhotos(3))
        assertTrue(store.recordImportedPhotos(12))
        assertFalse(store.recordImportedPhotos(12))
        store.markPrompted()

        val reopened = CoffeePromptStore(context)
        assertTrue(reopened.alreadyPrompted())
        assertFalse(reopened.recordImportedPhotos(20))
        assertEquals(27, reopened.importedPhotos())
    }

    @Test
    fun videosDoNotAdvanceThePhotoCount() {
        val store = CoffeePromptStore(context)
        assertFalse(store.recordImportedPhotos(0))
        assertEquals(0, store.importedPhotos())
        assertFalse(store.recordImportedPhotos(9))
        assertFalse(store.recordImportedPhotos(0))
        assertEquals(9, store.importedPhotos())
    }

    @Test
    fun laterOnlyHidesWhileDontAskAgainIsPermanent() {
        var later = 0
        var forever = 0
        rule.setContent {
            CoffeePromptDialog(onLater = { later += 1 }, onDismissForever = { forever += 1 })
        }
        rule.onNodeWithTag("coffee_prompt", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Buy me a coffee", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("coffee_wechat", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("coffee_alipay", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("WeChat Pay", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Alipay", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("coffee_open").assertIsDisplayed().performClick()
        rule.onNodeWithTag("coffee_later").performClick()
        rule.onNodeWithTag("coffee_dismiss").performClick()
        assertEquals(1, later)
        assertEquals(1, forever)
    }

    @Test
    fun tappingInsideTheCardDoesNotDismissIt() {
        var later = 0
        rule.setContent { CoffeePromptDialog(onLater = { later += 1 }, onDismissForever = {}) }
        rule.onNodeWithText("Buy me a coffee", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("coffee_prompt", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0, later)
    }

    @Test
    fun chineseLocalesTranslateTheTitleAndBothChoices() {
        assertEquals("请我喝一杯", stringFor("zh-CN", R.string.coffee_title))
        assertEquals("下次再说", stringFor("zh-Hans", R.string.coffee_later))
        assertEquals("不再提醒", stringFor("zh-SG", R.string.coffee_dismiss))
        assertEquals("請我喝一杯", stringFor("zh-TW", R.string.coffee_title))
        assertEquals("下次再說", stringFor("zh-HK", R.string.coffee_later))
        assertEquals("不再提醒", stringFor("zh-Hant", R.string.coffee_dismiss))
        assertEquals("Buy me a coffee", stringFor("en", R.string.coffee_title))
        assertFalse(stringFor("zh-CN", R.string.coffee_title).contains("Buy me"))
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-mdpi")
    fun compactLandscapeStillShowsBothActions() {
        rule.setContent { CoffeePromptDialog(onLater = {}, onDismissForever = {}) }
        rule.onNodeWithTag("coffee_prompt", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("coffee_later").assertExists().performClick()
        rule.onNodeWithTag("coffee_dismiss").assertExists().performClick()
        rule.onNodeWithTag("coffee_wechat", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("coffee_alipay", useUnmergedTree = true).assertExists()
    }

    private fun stringFor(tag: String, id: Int): String {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags(tag))
        return context.createConfigurationContext(configuration).getString(id)
    }
}
