package app.spektralab.raw

import java.util.concurrent.CancellationException

/** A developed raw file and the upright size of the whole frame it came from. */
class DevelopedRaw(val image: LinearImage, val fullWidth: Int, val fullHeight: Int)

/**
 * Loads and develops a raw file with a hard cap on the bytes read whole.
 *
 * Every registered reader ([RawReader]) decodes from one byte array, then a `ShortArray`
 * no larger than [RawDecodeLimits.MAX_SAMPLES]. This loader refuses the read before that
 * allocation when the file is larger than the caller's budget or [MAX_WHOLE_FILE].
 * Development uses [RawDeveloper], including its heap budget (`workingBytes` / `longEdgeWithin`).
 */
object RawLoader {
    /**
     * The raw frame of [source], or null when no reader can read it, [RawDeveloper] cannot
     * develop it, or the file is larger than the budget.
     * @param maxWholeFile largest file read whole. Also clamped to [MAX_WHOLE_FILE] and to
     *   about a third of the free heap, because the file and the decoded frame sit together.
     */
    fun open(source: ByteSource, maxWholeFile: Long = MAX_WHOLE_FILE): RawImage? {
        if (!RawReader.withinBudget(source.size, acceptableFileBytes(maxWholeFile))) return null
        val bytes = try {
            source.readAll()
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } ?: return null
        val img = try {
            RawReader.read(bytes)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
        return img?.takeIf { RawDeveloper.supports(it) }
    }

    /**
     * Develops [source] to at most about [maxLongEdge] (see [RawDeveloper.develop]).
     * Returns null when the file cannot be read or developed.
     * @throws CancellationException when [cancelled] is already true before development
     */
    fun load(source: ByteSource, maxLongEdge: Int, fast: Boolean = true,
             options: RawDeveloper.Options = RawDeveloper.Options(), maxWholeFile: Long = MAX_WHOLE_FILE,
             cancelled: (() -> Boolean)? = null): DevelopedRaw? {
        if (cancelled?.invoke() == true) throw CancellationException("load cancelled")
        val img = try { open(source, maxWholeFile) } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null } ?: return null
        if (cancelled?.invoke() == true) throw CancellationException("load cancelled")
        val out = try {
            RawDeveloper.develop(img, maxLongEdge, fast, options)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
        return DevelopedRaw(out, img.uprightWidth, img.uprightHeight)
    }

    /**
     * Bytes a whole-file reader may allocate now: the caller's cap, the hard ceiling, and a
     * third of the memory still free (the file, then a decoded frame of similar size).
     */
    fun acceptableFileBytes(requested: Long = MAX_WHOLE_FILE): Long {
        val rt = Runtime.getRuntime()
        val free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        return minOf(requested.coerceAtLeast(0L), MAX_WHOLE_FILE, maxOf(0L, free / 3))
    }

    /** Files larger than this are never read whole (a phone heap could not hold them next to the frame). */
    const val MAX_WHOLE_FILE = 192L shl 20
}
