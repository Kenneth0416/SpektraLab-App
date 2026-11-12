package app.spektralab.export

import androidx.annotation.StringRes
import app.spektralab.R
import app.spektralab.data.LibraryItem
import app.spektralab.data.LookJson
import app.spektralab.engine.Look
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException

/** One print: the item as it was queued, with the options it was queued with. */
data class ExportJob(val item: LibraryItem, val options: ExportOptions, val share: Boolean = false)

/**
 * Why a print failed, in the user's terms. Stored in the queue file as its stable name and
 * shown localized, so a queue saved under one language still reads right under another.
 */
enum class ExportReason {
    SOURCE, STORAGE, MEMORY, CODEC, OTHER;

    @StringRes fun label(): Int = when (this) {
        SOURCE -> R.string.export_reason_source
        STORAGE -> R.string.export_reason_storage
        MEMORY -> R.string.export_reason_memory
        CODEC -> R.string.export_reason_codec
        OTHER -> R.string.export_reason_other
    }

    companion object {
        /** The stored name of an old queue file; unknown names become [OTHER]. */
        fun stored(name: String?): ExportReason = entries.firstOrNull { it.name == name } ?: OTHER
    }
}

/** A job that did not print, with a short reason for the user. */
data class ExportFailure(val job: ExportJob, val reason: ExportReason)

/**
 * What a print was made from: "<look>.<rest>", both short hashes. The look part alone tells
 * the library whether the print is out of date; the whole string decides if a re-export can be skipped.
 */
object PrintSignature {
    private fun hash(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(6)
            .joinToString("") { "%02x".format(it) }

    fun lookPart(look: Look) = hash(LookJson.toJson(look).toString())

    /** The source, the look and the options that change the output file (size, format, EXIF date). */
    fun of(item: LibraryItem, o: ExportOptions): String {
        val opts = if (item.isVideo) "v${o.videoLongEdge}" else "p${o.photoFormat.name}${o.photoLongEdge}"
        return lookPart(item.look) + "." + hash("${item.uri}|${item.autoEv}|${item.dateTaken}|$opts")
    }

    /** Printed, but with a look that is no longer the item's. */
    fun isStale(item: LibraryItem) = item.printSig?.substringBefore('.') != lookPart(item.look)
}

/** Pure parts of the export queue: the file it survives process death in, and failure reasons. */
object ExportQueue {
    /** A job as stored: the item is looked up again in the library on restore. */
    data class Stored(val itemId: Long, val options: ExportOptions, val share: Boolean, val reason: String? = null)

    data class Saved(val pending: List<Stored>, val failed: List<Stored>)

    fun encode(pending: List<ExportJob>, failed: List<ExportFailure>): String {
        fun obj(j: ExportJob) = JSONObject().put("id", j.item.id).put("share", j.share)
            .put("format", j.options.photoFormat.name).put("photo", j.options.photoLongEdge).put("video", j.options.videoLongEdge)
        return JSONObject()
            .put("pending", JSONArray().apply { pending.forEach { put(obj(it)) } })
            .put("failed", JSONArray().apply { failed.forEach { put(obj(it.job).put("reason", it.reason.name)) } })
            .toString()
    }

    /** Unreadable entries are skipped; an unreadable file is an empty queue. */
    fun decode(text: String): Saved {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return Saved(emptyList(), emptyList())
        fun list(key: String): List<Stored> {
            val arr = root.optJSONArray(key) ?: return emptyList()
            val d = ExportOptions()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                if (!o.has("id")) return@mapNotNull null
                val format = PhotoFormat.entries.firstOrNull { it.name == o.optString("format") } ?: d.photoFormat
                Stored(o.optLong("id"), ExportOptions(format, o.optInt("photo", d.photoLongEdge), o.optInt("video", d.videoLongEdge)),
                    o.optBoolean("share"), o.optString("reason").ifEmpty { null })
            }
        }
        return Saved(list("pending"), list("failed"))
    }

    /** Jobs and failures whose item is still in the library, with the item as it is now. */
    fun resolve(saved: Saved, item: (Long) -> LibraryItem?): Pair<List<ExportJob>, List<ExportFailure>> {
        val jobs = saved.pending.mapNotNull { s -> item(s.itemId)?.let { ExportJob(it, s.options, s.share) } }
        val failed = saved.failed.mapNotNull { s -> item(s.itemId)?.let { ExportFailure(ExportJob(it, s.options, s.share), ExportReason.stored(s.reason)) } }
        return jobs to failed
    }

    /**
     * What went wrong, in the user's terms. Matched on the cause chain by type and message,
     * since the platform errors (codec, decoder, errno) only differ there.
     */
    fun reason(t: Throwable?): ExportReason {
        val chain = generateSequence(t) { e -> e.cause?.takeIf { it !== e } }.take(8).toList()
        fun has(vararg words: String) = chain.any { e -> val m = e.message.orEmpty().lowercase(); words.any { it in m } }
        return when {
            chain.any { it is OutOfMemoryError } || has("out of memory", "out_of_memory", "too large for gpu", "framebuffer incomplete") -> ExportReason.MEMORY
            has("enospc", "no space left", "edquot") -> ExportReason.STORAGE
            chain.any { it.javaClass.name.startsWith("android.media.MediaCodec") } || has("codec", "recordable egl") -> ExportReason.CODEC
            chain.any { it is FileNotFoundException || it is SecurityException || it.javaClass.name.contains("ImageDecoder") } ||
                has("no video track", "extractor", "permission") -> ExportReason.SOURCE
            else -> ExportReason.OTHER
        }
    }
}
