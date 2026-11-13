package app.spektralab.raw

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Random-access bytes of a raw file. DNG strips can be streamed; maker readers that need
 * the whole file go through [RawLoader]'s capped fallback. Positional reads are thread safe.
 */
interface ByteSource {
    val size: Long

    /** Reads exactly [len] bytes at [pos] into [dst]; throws [IOException] past the end. */
    fun read(pos: Long, dst: ByteArray, off: Int = 0, len: Int = dst.size - off)

    /** The whole file as one array. Callers must already have checked [size]. */
    fun readAll(): ByteArray {
        require(size in 0..Int.MAX_VALUE - 8) { "file too large" }
        return ByteArray(size.toInt()).also { read(0, it) }
    }
}

/** An in-memory file. */
class ByteArraySource(val bytes: ByteArray) : ByteSource {
    override val size get() = bytes.size.toLong()
    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int) {
        if (pos < 0 || len < 0 || off < 0 || pos > size || len > size - pos || off > dst.size || len > dst.size - off) {
            throw IOException("read $len at $pos past ${bytes.size}")
        }
        System.arraycopy(bytes, pos.toInt(), dst, off, len)
    }
    override fun readAll() = bytes
}

/**
 * A file read through a [FileChannel] with positional reads (thread safe). On Android open
 * it from a content uri: `FileInputStream(pfd.fileDescriptor).channel`.
 */
class ChannelSource(private val channel: FileChannel, private val start: Long = 0,
                    length: Long = channel.size() - start) : ByteSource, java.io.Closeable {
    init { require(start >= 0 && length >= 0 && start <= channel.size() && length <= channel.size() - start) }
    override val size: Long = length
    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int) {
        if (pos < 0 || len < 0 || off < 0 || pos > size || len > size - pos || off > dst.size || len > dst.size - off) {
            throw IOException("read $len at $pos past $size")
        }
        val buf = ByteBuffer.wrap(dst, off, len)
        var p = start + pos
        while (buf.hasRemaining()) {
            val n = channel.read(buf, p)
            if (n <= 0) throw IOException("short positional read")
            p += n
        }
    }
    override fun close() = channel.close()
}

/** Stored sample rows decoded on demand. Implementations must support concurrent reads. */
interface RawRows {
    val segmentRows: Int
    /** Maximum decoder scratch retained per worker, excluding the caller's ShortArray. */
    fun scratchBytes(rows: Int): Long
    fun read(y0: Int, y1: Int, dst: ShortArray)
}

/** At most two arrays retained by a load, matching the bounded development workers. */
internal class Pool<T : Any>(private val make: (Int) -> T, private val size: (T) -> Int) {
    private val free = java.util.concurrent.ConcurrentLinkedQueue<T>()
    fun take(n: Int): T = free.poll()?.takeIf { size(it) >= n } ?: make(n)
    fun give(a: T) { if (free.size < RawDeveloper.WORKERS) free.offer(a) }
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

object RawFile {
    /** Opens [file] for [RawLoader]; close it when done. */
    fun open(file: File): ChannelSource = ChannelSource(RandomAccessFile(file, "r").channel)
}
