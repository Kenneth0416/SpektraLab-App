package app.spektralab.raw

import java.util.concurrent.CancellationException

/** A developed raw file and the upright size of the whole frame it came from. */
class DevelopedRaw(val image: LinearImage, val fullWidth: Int, val fullHeight: Int)

/**
 * Loads and develops a raw file from random-access storage with bounded memory.
 *
 * DNGs with strip layouts (what Android cameras and this app's burst merge write) are
 * streamed: only their metadata is read up front and the pixels are decoded band by
 * band while developing ([DngReader.open]), so neither the file nor the full frame is
 * ever on the heap; the development itself runs on all cores. Anything else goes through
 * [RawReader.read] on the whole file, read once at its exact size.
 *
 * The result is the same as `RawDeveloper.develop(RawReader.read(bytes), ...)`.
 */
object RawLoader {
    /**
     * The raw frame of [source] (streamed when possible), or null when no reader can read
     * it or [RawDeveloper] cannot develop it.
     */
    fun open(source: ByteSource): RawImage? {
        val img = DngReader.open(source) ?: run {
            // Not streamable: the registered readers need the whole file.
            if (source.size > MAX_WHOLE_FILE) return null
            RawReader.read(source.readAll())
        }
        return img?.takeIf { RawDeveloper.supports(it) }
    }

    /**
     * Develops [source] to at most about [maxLongEdge] (see [RawDeveloper.develop]).
     * Returns null when the file cannot be read or developed (including corrupt pixel
     * data found while streaming); the caller falls back to the system decoder.
     * @throws CancellationException when [cancelled] turns true (checked between bands)
     */
    fun load(source: ByteSource, maxLongEdge: Int, fast: Boolean = true,
             options: RawDeveloper.Options = RawDeveloper.Options(), cancelled: (() -> Boolean)? = null): DevelopedRaw? {
        val img = try { open(source) } catch (e: Exception) { null } ?: return null
        if (cancelled?.invoke() == true) throw CancellationException("load cancelled")
        val out = try {
            RawDeveloper.develop(img, maxLongEdge, fast, options, cancelled)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return DevelopedRaw(out, img.uprightWidth, img.uprightHeight)
    }

    /** Files larger than this are not read whole (a phone heap could not hold them next to the frame). */
    const val MAX_WHOLE_FILE = 192L shl 20
}
