package app.spektralab.ui

import android.content.Context

/**
 * Optional "buy me a coffee" prompt. It appears once, after the user has imported
 * [PHOTO_THRESHOLD] still photos. Videos do not count.
 *
 * "Maybe later" only hides this appearance and asks again on the next import.
 * "Not now" is the only choice that retires the prompt permanently.
 */
class CoffeePromptStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun importedPhotos(): Int = prefs.getInt(KEY_COUNT, 0).coerceAtLeast(0)

    fun alreadyPrompted(): Boolean = prefs.getBoolean(KEY_PROMPTED, false)

    /**
     * Records [photos] newly imported stills. Returns true only on the crossing
     * that first reaches [PHOTO_THRESHOLD], and only if the prompt was never retired.
     */
    fun recordImportedPhotos(photos: Int): Boolean {
        if (photos <= 0 || alreadyPrompted()) return false
        val previous = importedPhotos()
        val next = previous + photos
        prefs.edit().putInt(KEY_COUNT, next).apply()
        return previous < PHOTO_THRESHOLD && next >= PHOTO_THRESHOLD
    }

    fun markPrompted() {
        prefs.edit().putBoolean(KEY_PROMPTED, true).apply()
    }

    private companion object {
        const val FILE = "coffee"
        const val KEY_COUNT = "imported_photos"
        const val KEY_PROMPTED = "prompted"
        const val PHOTO_THRESHOLD = 10
    }
}
