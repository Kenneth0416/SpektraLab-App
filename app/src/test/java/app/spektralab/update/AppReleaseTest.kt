package app.spektralab.update

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class AppReleaseTest {
    private val feed = "https://example.com/updates/android.json"
    private fun json() = JSONObject("""{
        "schemaVersion":1,"applicationId":"app.spektralab","versionCode":12,
        "versionName":"0.10.0","minSdk":29,"downloadUrl":"https://example.com/downloads/native.apk",
        "releaseNotes":{"en":"New features","zh-Hans":"新功能","zh-Hant":"新功能繁體"}
    }""")
    private fun parse(data: JSONObject = json()) = AppRelease.parse(data.toString(), "app.spektralab", feed)

    @Test fun parsesNumericVersionAndLocalizedNotes() {
        val release = parse()
        assertEquals(12L, release.versionCode)
        assertEquals("0.10.0", release.versionName)
        assertEquals("新功能", release.notesFor(Locale.forLanguageTag("zh-Hans")))
        assertEquals("新功能繁體", release.notesFor(Locale.forLanguageTag("zh-Hant")))
        assertEquals("新功能繁體", release.notesFor(Locale.forLanguageTag("zh-HK")))
        assertEquals("New features", release.notesFor(Locale.FRENCH))
    }

    @Test fun rejectsWrongPackageMalformedVersionsAndUnsafeLinks() {
        for ((key, value) in listOf(
            "applicationId" to "app.spektralab.prototype", "schemaVersion" to 2,
            "versionCode" to -1, "versionCode" to 2.5, "versionCode" to "12", "versionCode" to 2147483648L,
            "versionName" to "", "minSdk" to 0, "minSdk" to 29.5,
            "downloadUrl" to "http://example.com/app.apk",
            "downloadUrl" to "https://unrelated.example/app.apk",
            "downloadUrl" to "https://user@example.com/app.apk",
        )) {
            assertThrows("$key=$value", Exception::class.java) { parse(json().put(key, value)) }
        }
        assertThrows(Exception::class.java) { parse(json().apply { remove("versionCode") }) }
        assertThrows(Exception::class.java) { AppRelease.parse("<html>not a feed</html>", "app.spektralab", feed) }
    }

    private class Connection(url: URL, val body: ByteArray, val code: Int = 200) : HttpURLConnection(url) {
        var closed = false
        override fun connect() {}
        override fun usingProxy() = false
        override fun disconnect() { closed = true }
        override fun getResponseCode() = code
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getContentLengthLong() = -1L // Also exercise the streaming size cap.
    }

    @Test fun fetchSetsTimeoutsDisablesCacheAndClosesConnection() = runBlocking {
        val connection = Connection(URL(feed), json().toString().toByteArray())
        val release = UpdateClient("app.spektralab", feed) { connection }.fetch()
        assertEquals(12L, release.versionCode)
        assertEquals(5000, connection.connectTimeout)
        assertEquals(5000, connection.readTimeout)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertEquals("no-cache", connection.getRequestProperty("Cache-Control"))
        assertTrue(connection.closed)
    }

    @Test fun httpErrorsRedirectsAndOversizedFeedsFailClosed() = runBlocking {
        for (connection in listOf(
            Connection(URL(feed), byteArrayOf(), 404), Connection(URL(feed), byteArrayOf(), 302),
            Connection(URL(feed), ByteArray(65537) { 32 }),
            Connection(URL(feed), "<!doctype html>".toByteArray()),
        )) {
            try {
                UpdateClient("app.spektralab", feed) { connection }.fetch()
                fail("Invalid feed must fail")
            } catch (_: Exception) { }
            assertTrue(connection.closed)
        }
    }
}
