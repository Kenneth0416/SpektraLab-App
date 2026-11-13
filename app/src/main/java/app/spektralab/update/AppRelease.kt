package app.spektralab.update

import org.json.JSONObject
import java.net.URI
import java.util.Locale

/** The native app has its own feed; a WebView prototype is never an upgrade. */
data class AppRelease(
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val downloadUrl: String,
    val notes: Map<String, String>,
) {
    fun notesFor(locale: Locale): String {
        val key = when {
            locale.language != "zh" -> "en"
            locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO") -> "zh-Hant"
            else -> "zh-Hans"
        }
        return notes[key]?.takeIf { it.isNotBlank() } ?: notes["en"].orEmpty()
    }

    companion object {
        fun parse(text: String, applicationId: String, feedUrl: String): AppRelease {
            val json = JSONObject(text)
            require(json.getInt("schemaVersion") == 1) { "Unsupported update schema" }
            require(json.getString("applicationId") == applicationId) { "Wrong application" }
            // Do not coerce floating point, strings, negatives or overflowing version codes.
            val code = json.get("versionCode")
            require(code is Int || code is Long) { "Invalid version code" }
            val versionCode = (code as Number).toLong()
            require(versionCode in 1..2_100_000_000L) { "Invalid version code" }
            val versionName = json.getString("versionName").trim()
            require(versionName.isNotEmpty() && versionName.length <= 80) { "Invalid version name" }
            val sdk = json.get("minSdk")
            require(sdk is Int && sdk in 1..1000) { "Invalid minimum SDK" }
            val download = json.getString("downloadUrl")
            val feed = httpsUri(feedUrl)
            val destination = httpsUri(download)
            require(destination.host.equals(feed.host, ignoreCase = true) && destination.port == feed.port) {
                "Download must be hosted with the update feed"
            }
            val notesJson = json.getJSONObject("releaseNotes")
            val notes = listOf("en", "zh-Hans", "zh-Hant").associateWith { key ->
                notesJson.optString(key, "").also { require(it.length <= 8000) }
            }
            require(notes.getValue("en").isNotBlank()) { "Missing release notes" }
            return AppRelease(versionCode, versionName, sdk, download, notes)
        }

        internal fun httpsUri(value: String): URI = URI(value).also {
            require(it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null && it.fragment == null) {
                "Expected an HTTPS URL"
            }
        }
    }
}
