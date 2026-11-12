package app.spektralab.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import app.spektralab.engine.AutoExposure
import app.spektralab.engine.Look
import app.spektralab.raw.ByteArraySource
import app.spektralab.raw.ChannelSource
import app.spektralab.raw.DevelopedRaw
import app.spektralab.raw.LinearImage
import app.spektralab.raw.RawLoader
import app.spektralab.raw.RawReader
import java.util.concurrent.CancellationException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.pow

/** A decoded photo: either a display-referred sRGB bitmap or a scene-linear raw development. */
class SourceImage(val bitmap: Bitmap?, val linear: LinearImage?) {
    val width get() = bitmap?.width ?: linear!!.width
    val height get() = bitmap?.height ?: linear!!.height
}

object Media {
    /** Extensions of the raw formats the engine's readers handle (NRW is Nikon, SR2 Sony, RWL Leica's RW2). */
    private val RAW_EXT = setOf("dng", "cr2", "cr3", "nef", "nrw", "arw", "sr2", "orf", "rw2", "rwl", "raf", "pef", "srw")

    /**
     * Raw MIME types as providers report them: `image/x-adobe-dng`, `image/x-sony-arw`,
     * `image/x-canon-cr3`, `image/x-panasonic-raw` / `-rw2`, `image/x-fuji-raf`, `image/dng` ...
     */
    internal fun isRawMime(type: String): Boolean {
        val t = type.lowercase()
        if ("dng" in t) return true
        if (!t.startsWith("image/x-")) return false
        return "raw" in t || t.substringAfterLast('-') in RAW_EXT
    }

    /**
     * MIME filter of the document picker: images and videos, plus `application/octet-stream`, which
     * many document providers (cloud drives, USB / SD card readers) report for camera raw files they
     * do not know, so that those files are not greyed out. Anything picked that is not a photo or a
     * video is skipped on import.
     */
    val PICKER_TYPES = arrayOf("image/*", "video/*", "application/octet-stream")

    /** Types that say nothing about the content, so the file's first bytes are looked at. */
    private fun isOpaqueMime(type: String) = type.isEmpty() || type == "application/octet-stream" ||
        type == "image/tiff" || type == "image/*" || type == "*/*" || type == "application/x-unknown"

    /**
     * Largest file the non-streamable raw readers may read whole now: it and then its decoded frame
     * (about as large, up to about twice for compressed raws) sit on the heap together, next to what
     * the app already holds; a third of the free heap leaves room for both and the development.
     */
    private fun wholeFileBudget(): Long {
        val rt = Runtime.getRuntime()
        val free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        return maxOf(0L, free / 3)
    }

    /** Decode an upright, sRGB, software bitmap whose long edge is at most [maxLongEdge]. */
    fun decodeImage(context: Context, uri: Uri, maxLongEdge: Int): Bitmap {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val long = max(w, h)
            if (long > maxLongEdge) {
                val s = maxLongEdge.toDouble() / long
                decoder.setTargetSize(max(1, (w * s).toInt()), max(1, (h * s).toInt()))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_DEFAULT
        }.let { if (it.config != Bitmap.Config.ARGB_8888) it.copy(Bitmap.Config.ARGB_8888, false) else it }
    }

    /**
     * Load a photo for rendering. Raw files (DNG and the maker formats the engine reads:
     * CR2, CR3, NEF, ARW, ORF, RW2, RAF, PEF, SRW) are developed from the sensor data to
     * scene-linear RGB (see [RawLoader]); everything else, and raw files we cannot parse,
     * goes through the system decoder.
     * @param fast prefer speed (previews, thumbnails) over full-resolution demosaicing
     * @param cancelled polled while a raw file develops; when true the load stops with a
     *   [CancellationException] instead of falling back to the system decoder
     */
    fun loadSource(context: Context, uri: Uri, maxLongEdge: Int, tryRaw: Boolean, fast: Boolean = true,
                   cancelled: (() -> Boolean)? = null): SourceImage {
        if (tryRaw) {
            val raw = try { developRaw(context, uri, maxLongEdge, fast, cancelled) }
                catch (e: CancellationException) { throw e }
                catch (t: Throwable) { null }
            if (raw != null) return SourceImage(null, raw.image)
        }
        return SourceImage(decodeImage(context, uri, maxLongEdge), null)
    }

    /** Raw developments hold large buffers: one at a time, so a preview, a zoom and thumbnails never stack up. */
    private val rawGate = Semaphore(1, true)

    /**
     * Develops a raw file, streamed from its file descriptor (neither the file nor the
     * whole sensor frame is read onto the heap); null when no raw reader handles it.
     */
    fun developRaw(context: Context, uri: Uri, maxLongEdge: Int, fast: Boolean, cancelled: (() -> Boolean)? = null): DevelopedRaw? {
        while (!rawGate.tryAcquire(50, TimeUnit.MILLISECONDS)) {
            if (cancelled?.invoke() == true) throw CancellationException("raw load cancelled")
        }
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
                val channel = stream.channel
                val seekable = runCatching { channel.size() > 0 }.getOrDefault(false)
                val budget = wholeFileBudget()
                // a pipe from some document providers: read it once instead, unless it is larger than the heap allows
                val source = if (seekable) ChannelSource(channel) else ByteArraySource(readAtMost(stream, budget) ?: return null)
                RawLoader.load(source, maxLongEdge, fast, cancelled = cancelled, maxWholeFile = budget)
            }
        } finally {
            rawGate.release()
        }
    }

    /** The whole stream, or null when it is longer than [limit] bytes. */
    private fun readAtMost(stream: java.io.InputStream, limit: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val n = stream.read(buf)
            if (n < 0) return out.toByteArray()
            total += n
            if (total > limit) return null
            out.write(buf, 0, n)
        }
    }

    /** First bytes of a file, for [RawReader.mayBeRaw]. */
    private fun head(cr: ContentResolver, uri: Uri): ByteArray? = runCatching {
        cr.openInputStream(uri)?.use { s ->
            val b = ByteArray(RawReader.SNIFF_BYTES)
            var n = 0
            while (n < b.size) { val r = s.read(b, n, b.size - n); if (r < 0) break; n += r }
            b.copyOf(n)
        }
    }.getOrNull()

    /** Center-weighted auto exposure of a developed raw frame. */
    fun autoExposure(img: LinearImage): Float {
        val step = maxOf(1, maxOf(img.width, img.height) / 192)
        val w = img.width / step
        val h = img.height / step
        val lin = FloatArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 3
            lin[i] = img.red(x * step, y * step).coerceAtLeast(0f)
            lin[i + 1] = img.green(x * step, y * step).coerceAtLeast(0f)
            lin[i + 2] = img.blue(x * step, y * step).coerceAtLeast(0f)
        }
        return AutoExposure.centerWeightedEv(lin, w, h).toFloat()
    }

    /** A frame of a video at [timeUs], upright, long edge at most [maxLongEdge]. */
    fun videoFrame(context: Context, uri: Uri, timeUs: Long, maxLongEdge: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val ew = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val eh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val long = max(ew, eh).coerceAtLeast(1)
            val s = minOf(1.0, maxLongEdge.toDouble() / long)
            var bmp = r.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                (ew * s).toInt().coerceAtLeast(1), (eh * s).toInt().coerceAtLeast(1)) ?: return null
            // some platform versions return the encoded orientation; rotate if needed
            val needsRotate = (rot == 90 || rot == 270) && (bmp.width >= bmp.height) == (ew >= eh) && ew != eh
            if (needsRotate || rot == 180) {
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            if (bmp.config != Bitmap.Config.ARGB_8888) bmp = bmp.copy(Bitmap.Config.ARGB_8888, false)
            bmp
        } catch (e: Exception) {
            null
        } finally {
            r.release()
        }
    }

    /** spektrafilm center-weighted auto exposure on a small rendition. */
    fun autoExposure(bmp: Bitmap): Float {
        val small = if (max(bmp.width, bmp.height) > 192) {
            val s = 192.0 / max(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * s).toInt()), max(1, (bmp.height * s).toInt()), true)
        } else bmp
        val w = small.width
        val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val lin = FloatArray(w * h * 3)
        val lut = FloatArray(256) { i ->
            val c = i / 255.0
            (if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)).toFloat()
        }
        for (i in px.indices) {
            val p = px[i]
            lin[i * 3] = lut[(p shr 16) and 0xff]
            lin[i * 3 + 1] = lut[(p shr 8) and 0xff]
            lin[i * 3 + 2] = lut[p and 0xff]
        }
        return AutoExposure.centerWeightedEv(lin, w, h).toFloat()
    }

    fun name(context: Context, uri: Uri): String = displayName(context.contentResolver, uri)

    private fun displayName(cr: ContentResolver, uri: Uri): String =
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: (uri.lastPathSegment ?: "")

    /** Inspect a picked uri and build a library item (blocking; call off the main thread). */
    fun importItem(context: Context, uri: Uri, id: Long): LibraryItem? {
        val cr = context.contentResolver
        runCatching { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val type = cr.getType(uri) ?: ""
        val name = displayName(cr, uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        val isVideo = type.startsWith("video/")
        // named as raw (extension or MIME type); else, when neither says what the file is, its magic bytes
        val namedRaw = !isVideo && (ext in RAW_EXT || isRawMime(type))
        val tryRaw = namedRaw || (!isVideo && (isOpaqueMime(type) || ext.isEmpty()) && !type.startsWith("image/jp") &&
            head(cr, uri)?.let { RawReader.mayBeRaw(it) } == true)
        return try {
            if (isVideo) {
                val r = MediaMetadataRetriever()
                r.setDataSource(context, uri)
                val ew = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val eh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                val date = parseVideoDate(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
                r.release()
                if (ew <= 0 || eh <= 0) return null
                val swap = rot == 90 || rot == 270
                val frame = videoFrame(context, uri, minOf(dur * 500, 1_000_000L), 256)
                LibraryItem(id, uri.toString(), true, false, if (swap) eh else ew, if (swap) ew else eh, dur, rot,
                    date ?: System.currentTimeMillis(), frame?.let { autoExposure(it) } ?: 0f, Look.DEFAULT, name)
            } else {
                val raw = if (tryRaw) runCatching { developRaw(context, uri, 512, fast = true) }.getOrNull() else null
                if (raw != null) {
                    // developed from sensor data: size and exposure come from the raw frame
                    var date: Long? = null
                    runCatching {
                        cr.openInputStream(uri)?.use { s ->
                            val exif = ExifInterface(s)
                            date = parseExifDate(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME))
                        }
                    }
                    return LibraryItem(id, uri.toString(), false, true, raw.fullWidth, raw.fullHeight, 0, 0,
                        date ?: System.currentTimeMillis(), autoExposure(raw.image), Look.DEFAULT, name)
                }
                val bmp = decodeImage(context, uri, 512)
                var date: Long? = null
                var w = bmp.width
                var h = bmp.height
                runCatching {
                    cr.openInputStream(uri)?.use { s ->
                        val exif = ExifInterface(s)
                        date = parseExifDate(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME))
                        val ew = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                        val eh = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                        if (ew > 0 && eh > 0) {
                            val rotated = exif.rotationDegrees == 90 || exif.rotationDegrees == 270
                            w = if (rotated) eh else ew; h = if (rotated) ew else eh
                            // trust the decoded aspect if EXIF disagrees
                            if ((w >= h) != (bmp.width >= bmp.height)) { val t = w; w = h; h = t }
                        }
                    }
                }
                if (w <= bmp.width) { w = bmp.width; h = bmp.height }
                // a raw the readers could not develop (unsupported variant) keeps its RAW tag; a file that
                // was only sniffed (a plain TIFF, say) is an ordinary photo
                LibraryItem(id, uri.toString(), false, namedRaw, w, h, 0, 0, date ?: System.currentTimeMillis(),
                    autoExposure(bmp), Look.DEFAULT, name)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseExifDate(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return runCatching { java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).parse(s.trim())?.time }.getOrNull()
    }

    private fun parseVideoDate(s: String?): Long? {
        if (s == null) return null
        return runCatching {
            java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.parse(s.take(15))?.time
        }.getOrNull()
    }
}
