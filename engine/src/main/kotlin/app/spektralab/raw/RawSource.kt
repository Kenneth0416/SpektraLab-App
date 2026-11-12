package app.spektralab.raw

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Random-access bytes of a raw file, so a reader can fetch the metadata and then only the
 * pixel data it needs, instead of holding the whole file on the heap. Reads at different
 * positions may run concurrently from several threads.
 */
interface ByteSource {
    val size: Long

    /** Reads exactly [len] bytes at [pos] into [dst]; throws [IOException] past the end. */
    fun read(pos: Long, dst: ByteArray, off: Int = 0, len: Int = dst.size - off)

    /** The whole file as one array (the fallback for readers that need it). */
    fun readAll(): ByteArray {
        require(size <= Int.MAX_VALUE - 8) { "file too large" }
        return ByteArray(size.toInt()).also { read(0, it) }
    }
}

/** An in-memory file. */
class ByteArraySource(val bytes: ByteArray) : ByteSource {
    override val size get() = bytes.size.toLong()
    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int) {
        if (pos < 0 || len < 0 || pos + len > bytes.size) throw IOException("read $len at $pos past ${bytes.size}")
        System.arraycopy(bytes, pos.toInt(), dst, off, len)
    }
    override fun readAll() = bytes
}

/**
 * A file read through a [FileChannel] with positional reads (thread safe). On Android open
 * it from a content uri: `FileInputStream(pfd.fileDescriptor).channel`.
 */
class ChannelSource(private val channel: FileChannel) : ByteSource, java.io.Closeable {
    override val size: Long = channel.size()
    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int) {
        if (pos < 0 || len < 0 || pos + len > size) throw IOException("read $len at $pos past $size")
        val buf = ByteBuffer.wrap(dst, off, len)
        var p = pos
        while (buf.hasRemaining()) {
            val n = channel.read(buf, p)
            if (n < 0) throw IOException("unexpected end of file")
            p += n
        }
    }
    override fun close() = channel.close()
}

object RawFile {
    /** Opens [file] for [RawLoader]; close it when done. */
    fun open(file: File): ChannelSource = ChannelSource(RandomAccessFile(file, "r").channel)
}

/**
 * Stored sample rows of a frame, decoded on demand (strip by strip) so a frame can be
 * developed without one array holding all of it. Set on [RawImage.rows] by
 * [DngReader.open]; [RawDeveloper] is the only consumer.
 */
interface RawRows {
    /**
     * Rows that are decoded together (a compressed strip): reading whole, aligned groups
     * of them avoids decoding a strip twice. 1 when any row can be read on its own cheaply.
     */
    val segmentRows: Int

    /**
     * Decodes stored rows [y0, y1) — every one of [RawImage.rawWidth] columns, three
     * interleaved samples per pixel for linear frames — into [dst] from index 0, in the
     * same values [RawImage.data] would hold. Thread safe.
     * @throws IOException / IllegalStateException on unreadable or corrupt data
     */
    fun read(y0: Int, y1: Int, dst: ShortArray)
}

/**
 * Scratch arrays reused by the bands of one load, so decoding a frame band by band does
 * not allocate (and the garbage collector reclaim) a new buffer for every band.
 */
internal class Pool<T : Any>(private val make: (Int) -> T, private val size: (T) -> Int) {
    private val free = java.util.concurrent.ConcurrentLinkedQueue<T>()

    /** An array of at least [n] elements (contents undefined). */
    fun take(n: Int): T = free.poll()?.takeIf { size(it) >= n } ?: make(n)

    fun give(a: T) { if (free.size < 16) free.offer(a) }

    inline fun <R> use(n: Int, block: (T) -> R): R {
        val a = take(n)
        try { return block(a) } finally { give(a) }
    }

    companion object {
        fun bytes() = Pool(::ByteArray) { it.size }
        fun shorts() = Pool(::ShortArray) { it.size }
        fun floats() = Pool(::FloatArray) { it.size }
    }
}
