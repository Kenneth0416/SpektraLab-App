package app.spektralab.update

import android.content.Context
import app.spektralab.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object Current : UpdateStatus
    data object Failed : UpdateStatus
    data object Unsupported : UpdateStatus
    data class Available(val release: AppRelease) : UpdateStatus
}

data class UpdatePrompt(val release: AppRelease, val manual: Boolean)

interface UpdateCheckStore {
    var lastAttemptAt: Long
}

class UpdatePreferences(context: Context) : UpdateCheckStore {
    private val prefs = context.applicationContext.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    override var lastAttemptAt: Long
        get() = prefs.getLong("last_attempt_at", 0)
        set(value) { prefs.edit().putLong("last_attempt_at", value).apply() }
}

/** Called on the main thread. Requests outlive Activity recreation, but not the ViewModel. */
class UpdateManager(
    private val scope: CoroutineScope,
    private val store: UpdateCheckStore,
    private val installedVersion: Long,
    private val sdk: Int,
    private val fetch: suspend () -> AppRelease,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status = _status.asStateFlow()
    private val _prompt = MutableStateFlow<UpdatePrompt?>(null)
    val prompt = _prompt.asStateFlow()
    private var checking: Job? = null
    private var requestedManually = false

    fun check(manual: Boolean = false) {
        if (checking?.isActive == true) {
            requestedManually = requestedManually || manual
            return
        }
        val now = clock()
        val previous = store.lastAttemptAt
        if (!manual && previous > 0 && now >= previous && now - previous < DAY_MS) return
        // Record attempts, including offline failures, to avoid repeated requests on every resume.
        store.lastAttemptAt = now
        requestedManually = manual
        _status.value = UpdateStatus.Checking
        _prompt.value = null
        checking = scope.launch {
            try {
                val release = fetch()
                when {
                    release.versionCode <= installedVersion -> _status.value = UpdateStatus.Current
                    release.minSdk > sdk -> _status.value = UpdateStatus.Unsupported
                    else -> {
                        _status.value = UpdateStatus.Available(release)
                        _prompt.value = UpdatePrompt(release, requestedManually)
                    }
                }
            } catch (cancelled: CancellationException) {
                _status.value = UpdateStatus.Idle
                throw cancelled
            } catch (_: Exception) {
                // Only the settings row displays failures. Automatic checks never interrupt work.
                _status.value = UpdateStatus.Failed
            }
        }
    }

    fun dismissPrompt() {
        _prompt.value = null
        // "Later" survives restarts and grants a full day without another automatic reminder.
        store.lastAttemptAt = clock()
    }

    companion object { const val DAY_MS = 24 * 60 * 60 * 1000L }
}

/** A small HTTPS GET, without accounts, identifiers, photo uploads or a background service. */
class UpdateClient(
    private val applicationId: String,
    private val feedUrl: String = BuildConfig.UPDATE_URL,
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    suspend fun fetch(): AppRelease = withContext(Dispatchers.IO) {
        AppRelease.httpsUri(feedUrl)
        val connection = connect(URL(feedUrl))
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Update feed unavailable")
            if (connection.contentLengthLong > MAX_BYTES) throw IOException("Update feed too large")
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val size = input.read(buffer)
                    if (size == -1) break
                    if (output.size() + size > MAX_BYTES) throw IOException("Update feed too large")
                    output.write(buffer, 0, size)
                }
                output.toByteArray()
            }
            AppRelease.parse(bytes.toString(Charsets.UTF_8), applicationId, feedUrl)
        } finally {
            connection.disconnect()
        }
    }

    private companion object { const val MAX_BYTES = 64 * 1024 }
}
