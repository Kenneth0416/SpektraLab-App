package app.spektralab.raw

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Random-access bytes of a raw file. Maker readers on this foundation still need the whole
 * file; [RawLoader] refuses to materialise it when it would blow the heap budget.
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
        if (pos < 0 || len < 0 || off < 0 || pos + len > bytes.size || off + len > dst.size) {
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
class ChannelSource(private val channel: FileChannel) : ByteSource, java.io.Closeable {
    override val size: Long = channel.size()
    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int) {
        if (pos < 0 || len < 0 || off < 0 || pos + len > size || off + len > dst.size) {
            throw IOException("read $len at $pos past $size")
        }
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
