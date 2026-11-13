package app.spektralab.raw

import java.util.concurrent.CancellationException

/** A developed raw file and the upright size of the whole frame it came from. */
class DevelopedRaw(val image: LinearImage, val fullWidth: Int, val fullHeight: Int)

/**
 * Loads and develops a raw file with a hard cap on the bytes read whole.
 *
 * Seekable Bayer/linear DNG strips are decoded in bounded row bands. Other layouts and
 * maker formats retain the hardened [RawReader] whole-file path, capped before allocation
 * by the caller, [MAX_WHOLE_FILE], and current heap headroom. X-Trans development keeps
 * its existing whole-frame input and banded developer. Development budgets include gain
 * tables, decoder scratch, sample bands and Malvar halos; callers may supply mapped output.
 */
object RawLoader {
    /**
     * The raw frame of [source], or null when no reader can read it, [RawDeveloper] cannot
     * develop it, or the file is larger than the budget.
     * @param maxWholeFile largest file read whole. Also clamped to [MAX_WHOLE_FILE] and to
     *   about a third of the free heap, because the file and the decoded frame sit together.
     */
    fun open(source: ByteSource, maxWholeFile: Long = MAX_WHOLE_FILE): RawImage? {
        if (!RawReader.withinBudget(source.size, minOf(maxWholeFile, MAX_WHOLE_FILE))) return null
        DngReader.open(source)?.takeIf { it.linear || it.cfa.isBayer }?.let { return it }
        if (!RawReader.withinBudget(source.size, acceptableFileBytes(maxWholeFile))) return null
        val bytes = try {
            source.readAll()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } ?: return null
        val img = try {
            RawReader.read(bytes)
        } catch (e: CancellationException) {
            throw e
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
     * @throws CancellationException when [cancelled] becomes true before or during reads/development
     * @param rawHeapBudget development heap budget, excluding output storage and existing source metadata;
     *   zero keeps the requested size. Supply a mapped [allocate] callback for budgeted export.
     */
    fun load(source: ByteSource, maxLongEdge: Int, fast: Boolean = true,
             options: RawDeveloper.Options = RawDeveloper.Options(), maxWholeFile: Long = MAX_WHOLE_FILE,
             cancelled: (() -> Boolean)? = null, rawHeapBudget: Long = 0,
             allocate: (Int) -> java.nio.ByteBuffer = java.nio.ByteBuffer::allocateDirect): DevelopedRaw? {
        if (cancelled?.invoke() == true) throw CancellationException("load cancelled")
        val checked = object : ByteSource {
            override val size get() = source.size
            override fun readAll(): ByteArray {
                if (cancelled?.invoke() == true) throw CancellationException("load cancelled")
                // Preserve ByteArraySource's zero-copy fallback for pipes already read once.
                return source.readAll().also {
                    if (cancelled?.invoke() == true) throw CancellationException("load cancelled")
                }
            }
            override fun read(pos: Long, dst: ByteArray, off: Int, len: Int) {
                if (cancelled?.invoke() == true) throw CancellationException("load cancelled")
                source.read(pos, dst, off, len)
            }
        }
        val img = try { open(checked, maxWholeFile) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { null } catch (e: OutOfMemoryError) { null } ?: return null
        if (cancelled?.invoke() == true) throw CancellationException("load cancelled")
        val out = try {
            val edge = if (rawHeapBudget > 0) RawDeveloper.longEdgeWithin(img, maxLongEdge, fast, rawHeapBudget, false) else maxLongEdge
            RawDeveloper.developCancellable(img, edge, fast, options, allocate, cancelled)
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
