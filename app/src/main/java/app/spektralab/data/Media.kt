package app.spektralab.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import androidx.exifinterface.media.ExifInterface
import app.spektralab.engine.AutoExposure
import app.spektralab.engine.Look
import app.spektralab.raw.LinearImage
import app.spektralab.raw.RawDeveloper
import app.spektralab.raw.RawImage
import app.spektralab.raw.RawLoader
import app.spektralab.raw.RawReader
import app.spektralab.raw.ByteArraySource
import app.spektralab.raw.ChannelSource
import app.spektralab.raw.DevelopedRaw
import java.util.concurrent.CancellationException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.pow

/** A decoded photo: either a display-referred sRGB bitmap or a scene-linear raw development. */
class SourceImage internal constructor(val bitmap: Bitmap?, val linear: LinearImage?,
                  val originalWidth: Int, val originalHeight: Int,
                  private val memory: java.io.Closeable?, private val owners: java.util.concurrent.atomic.AtomicInteger) : java.io.Closeable {
    constructor(bitmap: Bitmap?, linear: LinearImage?,
                originalWidth: Int = bitmap?.width ?: linear!!.width,
                originalHeight: Int = bitmap?.height ?: linear!!.height,
                memory: java.io.Closeable? = null) :
        this(bitmap, linear, originalWidth, originalHeight, memory, java.util.concurrent.atomic.AtomicInteger(if (memory == null) 0 else 1))

    val width get() = bitmap?.width ?: linear!!.width
    val height get() = bitmap?.height ?: linear!!.height
    private val closed = java.util.concurrent.atomic.AtomicBoolean()

    /** Another owner of the same pixels. Each handle closes once; the lease ends with the last one. */
    fun share(): SourceImage {
        while (true) {
            val current = owners.get()
            if (current <= 0) return SourceImage(bitmap, linear, originalWidth, originalHeight, null)
            if (owners.compareAndSet(current, current + 1)) {
                return SourceImage(bitmap, linear, originalWidth, originalHeight, memory, owners)
            }
        }
    }

    /** Hands this lease to the returned image. The receiver must be closed; this one must not. */
    internal fun releasing(memory: java.io.Closeable) = SourceImage(bitmap, linear, originalWidth, originalHeight, memory)

    /** Drops this handle. Closing the same handle twice does nothing. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (owners.decrementAndGet() == 0) memory?.close()
    }
}

/**
 * Read exactly one declared byte range. A content provider can expose a slice of a larger
 * file: its offset and length are authoritative, and [AssetFileDescriptor.getLength] of
 * [AssetFileDescriptor.UNKNOWN_LENGTH] means the stream must be consumed instead.
 * Known sizes are filled in place, so there is no second growing copy of the compressed file.
 */
internal fun readDeclaredBytes(input: java.io.InputStream, offset: Long, length: Long): ByteArray {
    require(offset >= 0) { "RAW slice starts before the file" }
    // A file stream reports where it already is. AssetFileDescriptor.createInputStream()
    // starts at the slice; a plain stream starts at zero and must skip to it.
    val position = (input as? java.io.FileInputStream)?.channel?.position()
    var skipped = if (position != null && position <= offset) position else 0L
    while (skipped < offset) {
        val n = input.skip(offset - skipped)
        if (n > 0) skipped += n
        else if (input.read() < 0) throw java.io.EOFException("RAW slice starts beyond its file")
        else skipped++
    }
    if (length < 0) return input.readBytes()
    require(length <= Int.MAX_VALUE) { "RAW file is too large" }
    val bytes = ByteArray(length.toInt())
    var filled = 0
    while (filled < bytes.size) {
        val count = input.read(bytes, filled, bytes.size - filled)
        if (count < 0) throw java.io.EOFException("RAW file ended before its declared size")
        if (count == 0) {
            val value = input.read()
            if (value < 0) throw java.io.EOFException("RAW file ended before its declared size")
            bytes[filled++] = value.toByte()
        } else filled += count
    }
    return bytes
}

/** Known-size streams need one buffer; a negative length is a pipe whose end is the EOF. */
internal fun readRawBytes(input: java.io.InputStream, size: Long): ByteArray = readDeclaredBytes(input, 0, size)

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
     * MIME filter of the document picker. `application/octet-stream` is included because many
     * document providers report it for camera raw files they do not recognise.
     */
    val PICKER_TYPES = arrayOf("image/*", "video/*", "application/octet-stream")

    private fun isOpaqueMime(type: String) = type.isEmpty() || type == "application/octet-stream" ||
        type == "image/tiff" || type == "image/*" || type == "*/*" || type == "application/x-unknown"

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
     * Load a photo for rendering. DNGs written by Android cameras are developed from the
     * sensor data to scene-linear RGB (see [RawDeveloper]); everything else, and raw files
     * we cannot parse, goes through the system decoder.
     * @param fast prefer speed (previews, thumbnails) over full-resolution demosaicing
     * @param rawHeapBudget when set, a raw is developed into a file mapping (off the Java heap)
     *   and binned if its development would still allocate more Java heap than this, in bytes
     */
    fun loadSource(context: Context, uri: Uri, maxLongEdge: Int, tryRaw: Boolean, fast: Boolean = true,
                   rawHeapBudget: Long = 0, heavyWorkHeld: Boolean = false,
                   cancelled: (() -> Boolean)? = null): SourceImage {
        if (cancelled?.invoke() == true) throw CancellationException("RAW load cancelled")
        if (tryRaw) {
            // Parsing expands the full sensor even for a small rendition. The permit covers
            // that work only: the returned image does not keep it, so a preview can stay open
            // while an export or a sharper decode waits its turn. Cancellation still runs finally.
            RawMemoryGate.acquire(alreadyHeld = heavyWorkHeld, cancelled = cancelled).use {
                val source = try {
                    developRaw(context, uri, maxLongEdge, fast, rawHeapBudget, cancelled)?.let { raw ->
                        SourceImage(null, raw.image, raw.fullWidth, raw.fullHeight)
                    }
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) { null }
                  catch (e: OutOfMemoryError) { null }
                if (source != null) return source
            }
        }
        if (cancelled?.invoke() == true) throw CancellationException("RAW load cancelled")
        return SourceImage(decodeImage(context, uri, maxLongEdge), null)
    }

    /** Develop within a provider's declared slice; keep its descriptor open until all bands drain. */
    private fun developRaw(context: Context, uri: Uri, edge: Int, fast: Boolean, budget: Long,
                           cancelled: (() -> Boolean)?): DevelopedRaw? {
        val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r") ?: return null
        return descriptor.use { afd ->
            val length = afd.declaredLength
            if (length > RawLoader.MAX_WHOLE_FILE) return null
            afd.createInputStream().use { input ->
                val channel = input.channel
                val seekableSize = runCatching { channel.size() }.getOrNull()?.takeIf { it > 0 }
                val source = if (seekableSize != null) {
                    ChannelSource(channel, afd.startOffset, if (length >= 0) length else seekableSize - afd.startOffset)
                } else {
                    val cap = RawLoader.acceptableFileBytes()
                    if (length > cap) return null
                    ByteArraySource(readAtMost(input, cap, cancelled) ?: return null)
                }
                RawLoader.load(source, edge, fast, cancelled = cancelled, rawHeapBudget = budget,
                    allocate = { if (budget > 0) mappedBuffer(context, it) else ByteBuffer.allocateDirect(it) })
            }
        }
    }

    /** The whole stream, or null when it is longer than [limit] bytes. Does not grow past the cap. */
    private fun readAtMost(input: java.io.InputStream, limit: Long, cancelled: (() -> Boolean)? = null): ByteArray? {
        if (limit < 0) return null
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            if (cancelled?.invoke() == true) throw CancellationException("RAW read cancelled")
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) continue
            total += n
            if (total > limit) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** True when the first bytes look like a registered raw container. A short read is not raw. */
    private fun sniffRaw(cr: ContentResolver, uri: Uri): Boolean {
        val head = runCatching {
            cr.openInputStream(uri)?.use { input ->
                val buf = ByteArray(RawReader.SNIFF_BYTES)
                var filled = 0
                while (filled < buf.size) {
                    val n = input.read(buf, filled, buf.size - filled)
                    if (n < 0) break
                    if (n == 0) continue
                    filled += n
                }
                buf.copyOf(filled)
            }
        }.getOrNull() ?: return false
        return RawReader.mayBeRaw(head)
    }

    /**
     * [size] bytes backed by an unlinked cache file: not Java heap, and the kernel can page it
     * out instead of killing the app. Blocks are reserved up front, so a full disk shows here
     * (then: a plain direct buffer, as before) rather than as a fault on a later write.
     */
    private fun mappedBuffer(context: Context, size: Int): ByteBuffer {
        val f = runCatching { File.createTempFile("develop", ".bin", context.cacheDir) }.getOrNull()
            ?: return ByteBuffer.allocateDirect(size)
        try {
            RandomAccessFile(f, "rw").use { raf ->
                Os.posix_fallocate(raf.fd, 0, size.toLong())
                return raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, size.toLong())
            }
        } catch (e: Exception) {
            android.util.Log.w("SpektraMedia", "no file mapping for the raw development", e)
            return ByteBuffer.allocateDirect(size)
        } finally {
            // the mapping keeps the data; nothing is left behind if the app dies
            f.delete()
        }
    }

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
    fun importItem(context: Context, uri: Uri, id: Long, quick: QuickSources? = null,
                   captured: RawPreview? = null): LibraryItem? {
        val cr = context.contentResolver
        runCatching { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val type = cr.getType(uri) ?: ""
        val name = displayName(cr, uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        val isVideo = type.startsWith("video/")
        val isRaw = captured != null || ext in RAW_EXT || isRawMime(type) || (isOpaqueMime(type) && sniffRaw(cr, uri))
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
                // A captured preview owns no RAW arrays/permit. Do not reacquire the
                // memory gate here: the rest of its burst may still be holding it.
                val src = captured?.source() ?: loadSource(context, uri, 512, tryRaw = isRaw)
                src.use { loaded ->
                if (loaded.linear != null) {
                    // developed from sensor data: size and exposure come from the raw frame
                    var date: Long? = null
                    runCatching {
                        cr.openInputStream(uri)?.use { s ->
                            val exif = ExifInterface(s)
                            date = parseExifDate(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME))
                        }
                    }
                    return LibraryItem(id, uri.toString(), false, true, loaded.originalWidth, loaded.originalHeight, 0, 0,
                        date ?: System.currentTimeMillis(), captured?.autoEv ?: autoExposure(loaded.linear), Look.DEFAULT, name)
                        .also { quick?.keep(it, loaded) }
                }
                val bmp = loaded.bitmap!!
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
                LibraryItem(id, uri.toString(), false, isRaw, w, h, 0, 0, date ?: System.currentTimeMillis(),
                    autoExposure(bmp), Look.DEFAULT, name)
                }
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
