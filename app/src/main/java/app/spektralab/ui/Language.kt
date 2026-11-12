package app.spektralab.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** Languages supported by the app. SYSTEM leaves locale selection to Android. */
enum class AppLanguage(val storedValue: String, val localeTag: String?) {
    SYSTEM("system", null),
    ENGLISH("en", "en"),
    SIMPLIFIED_CHINESE("zh-Hans", "zh-Hans"),
    TRADITIONAL_CHINESE("zh-Hant", "zh-Hant");

    fun asLocaleList(): LocaleListCompat = localeTag?.let(LocaleListCompat::forLanguageTags)
        ?: LocaleListCompat.getEmptyLocaleList()

    companion object {
        fun fromStored(value: String?): AppLanguage =
            entries.firstOrNull { it.storedValue == value } ?: SYSTEM
    }
}

/** Small synchronous preference store; no extra datastore dependency is needed for one setting. */
class LanguagePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): AppLanguage = AppLanguage.fromStored(prefs.getString(KEY, AppLanguage.SYSTEM.storedValue))

    fun save(language: AppLanguage) {
        prefs.edit().putString(KEY, language.storedValue).apply()
    }

    private companion object {
        const val FILE = "language"
        const val KEY = "selection"
    }
}

/** Applies both the in-app choice and Android 13+'s per-app language setting. */
object AppLocales {
    fun apply(language: AppLanguage) {
        AppCompatDelegate.setApplicationLocales(language.asLocaleList())
    }
}
