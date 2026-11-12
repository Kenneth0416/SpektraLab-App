package app.spektralab.ui

import android.app.Application
import android.content.res.Configuration
import android.os.LocaleList
import androidx.core.os.LocaleListCompat
import app.spektralab.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LanguagePreferenceTest {
    private val context: Application
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearPreferences() {
        context.getSharedPreferences("language", Application.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun defaultsToSystemAndPersistsEveryChoice() {
        assertEquals(AppLanguage.SYSTEM, LanguagePreferences(context).load())
        AppLanguage.entries.forEach { language ->
            LanguagePreferences(context).save(language)
            assertEquals(language, LanguagePreferences(context).load())
        }
    }

    @Test
    fun missingOrUnknownPreferenceFallsBackToSystem() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromStored(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromStored("unknown"))
    }

    @Test
    fun manualChoicesMapToSupportedLocaleTags() {
        assertTrue(AppLanguage.SYSTEM.asLocaleList().isEmpty)
        assertEquals("en", AppLanguage.ENGLISH.asLocaleList().toLanguageTags())
        assertEquals("zh-Hans", AppLanguage.SIMPLIFIED_CHINESE.asLocaleList().toLanguageTags())
        assertEquals("zh-Hant", AppLanguage.TRADITIONAL_CHINESE.asLocaleList().toLanguageTags())
    }

    @Test
    fun resourcesResolveForEnglishAndChineseScriptsAndRegions() {
        assertEquals("Library", stringFor("en"))
        assertEquals("图库", stringFor("zh-CN"))
        assertEquals("图库", stringFor("zh-SG"))
        assertEquals("圖庫", stringFor("zh-TW"))
        assertEquals("圖庫", stringFor("zh-HK"))
        assertEquals("圖庫", stringFor("zh-Hant"))
        assertEquals("Library", stringFor("fr"))
    }

    private fun stringFor(tag: String): String {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags(tag))
        return context.createConfigurationContext(configuration).getString(R.string.library_title)
    }
}
