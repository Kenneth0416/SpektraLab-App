package app.spektralab.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import java.io.File

/** Only attachment shares are imports; text/URLs are never fetched. */
object IncomingShare {
    fun uris(intent: Intent): List<Uri> {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return emptyList()
        return runCatching {
            val streams = if (intent.action == Intent.ACTION_SEND) {
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            } else {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            }
            val clip = intent.clipData
            (streams + (0 until (clip?.itemCount ?: 0)).mapNotNull { clip?.getItemAt(it)?.uri })
                .filter { it.scheme == "content" }.distinct()
        }.getOrDefault(emptyList())
    }

    /** Share grants are temporary. Keep an owned original, streaming even large videos to disk. */
    internal fun copyOriginal(context: Context, uri: Uri): File {
        val directory = File(context.filesDir, "shared-originals").apply { mkdirs() }
        val extension = Media.name(context, uri).substringAfterLast('.', "")
            .takeIf { it.length in 1..12 && it.all { c -> c.isLetterOrDigit() } }
        val file = File.createTempFile("share-", extension?.let { ".$it" } ?: ".bin", directory)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("Missing shared stream")
            input.use { source -> file.outputStream().use { target -> source.copyTo(target) } }
            return file
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    /** Invalid/unreadable documents are skipped through the existing import feedback. */
    fun importItem(context: Context, uri: Uri, id: Long, quick: QuickSources? = null,
                   mimeType: String? = null): LibraryItem? {
        var original: File? = null
        var retained = false
        return try {
            val name = Media.name(context, uri)
            val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                ?.takeIf { it.isNotBlank() && it != "application/octet-stream" && it != "*/*" }
                ?: mimeType
            original = copyOriginal(context, uri)
            Media.importItem(context, Uri.fromFile(original), id, quick = quick, mimeType = type)
                ?.copy(name = name)?.also { retained = true }
        } catch (e: Exception) {
            null
        } finally {
            if (!retained) original?.delete()
        }
    }
}
