package app.spektralab.data

import android.content.Context
import android.net.Uri
import android.util.Half
import android.util.LruCache
import app.spektralab.raw.LinearImage
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Small first renditions of photos, so the darkroom shows a picture while the
 * preview-resolution original is still decoding.
 *
 *  - ordinary photos: a subsampled decode, which is many times quicker than the preview decode;
 *  - raw: a developed proxy kept on disk. Opening a raw means reading and unpacking the
 *    whole file, so the first open cannot be shortened, but every later one can.
 *
 * The last few are also kept in memory, for going back and forth between frames.
 */
class QuickSources(private val context: Context) {
    private val mem = object : LruCache<String, SourceImage>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: SourceImage) = value.width * value.height * if (value.linear != null) 8 else 4
    }
    private val dir = File(context.cacheDir, "quick").apply { mkdirs() }

    private fun key(item: LibraryItem) = "${item.id}_${Integer.toHexString(item.uri.hashCode())}"
    private fun proxy(item: LibraryItem) = File(dir, "${key(item)}.lin")

    /** Is a quick rendition worth showing first, or is the preview decode already quick? */
    fun worth(item: LibraryItem): Boolean = !item.isVideo && (item.isRaw || maxOf(item.width, item.height) >= 3 * EDGE)

    /** Blocking; call off the main thread. Null when there is none (a raw not opened before). */
    @Synchronized fun load(item: LibraryItem): SourceImage? {
        val k = key(item)
        mem.get(k)?.let { return it }
        val src = runCatching {
            if (item.isRaw) readProxy(proxy(item)) else SourceImage(Media.decodeImage(context, Uri.parse(item.uri), EDGE), null)
        }.getOrNull() ?: return null
        mem.put(k, src)
        return src
    }

    /**
     * Keep a proxy of a raw that has just been developed for the preview. Blocking.
     * Synchronized like [load]: two opens of the same raw must not shrink and write
     * the same proxy file twice at once.
     */
    @Synchronized fun keep(item: LibraryItem, developed: SourceImage) {
        val linear = developed.linear ?: return
        val f = proxy(item)
        if (f.exists()) return
        runCatching {
            val small = shrink(linear, EDGE)
            mem.put(key(item), SourceImage(null, small))
            // written beside and renamed, so a killed app never leaves a half proxy behind
            val tmp = File(dir, f.name + ".tmp")
            RandomAccessFile(tmp, "rw").use { out ->
                out.writeInt(MAGIC); out.writeInt(small.width); out.writeInt(small.height)
                small.halfRgba.position(0)
                out.channel.write(small.halfRgba)
                small.halfRgba.position(0)
            }
            if (!tmp.renameTo(f)) tmp.delete()
        }
    }

    /** Drop the renditions of removed items. */
    fun forget(ids: Collection<Long>) {
        val prefixes = ids.map { "${it}_" }
        mem.snapshot().keys.filter { k -> prefixes.any { k.startsWith(it) } }.forEach { mem.remove(it) }
        dir.listFiles()?.filter { f -> prefixes.any { f.name.startsWith(it) } }?.forEach { it.delete() }
    }

    private fun readProxy(f: File): SourceImage? {
        if (!f.exists()) return null
        RandomAccessFile(f, "r").use { file ->
            if (file.readInt() != MAGIC) return null
            val w = file.readInt()
            val h = file.readInt()
            if (w !in 1..4 * EDGE || h !in 1..4 * EDGE || file.length() != HEADER + w.toLong() * h * 8) return null
            val buf = ByteBuffer.allocateDirect(w * h * 8).order(ByteOrder.nativeOrder())
            val ch: FileChannel = file.channel
            while (buf.hasRemaining()) if (ch.read(buf) < 0) return null
            buf.position(0)
            return SourceImage(null, LinearImage(w, h, buf))
        }
    }

    companion object {
        /** Long edge of a quick rendition, px: enough to judge the picture for a moment. */
        const val EDGE = 800
        private const val MAGIC = 0x53504B51 // "SPKQ"
        private const val HEADER = 12L

        /** Box-average a scene-linear image down to at most [maxLongEdge] (integer factor). */
        fun shrink(img: LinearImage, maxLongEdge: Int): LinearImage {
            val long = maxOf(img.width, img.height)
            val k = (long + maxLongEdge - 1) / maxLongEdge
            if (k <= 1) return img
            val w = img.width / k
            val h = img.height / k
            val src = img.halfRgba
            val out = ByteBuffer.allocateDirect(w * h * 8).order(ByteOrder.nativeOrder())
            val one = Half.toHalf(1f)
            val row = FloatArray(w * 3)
            val norm = 1f / (k * k)
            for (y in 0 until h) {
                row.fill(0f)
                for (dy in 0 until k) {
                    var p = ((y * k + dy) * img.width) * 8
                    for (x in 0 until w) {
                        val i = x * 3
                        for (dx in 0 until k) {
                            row[i] += Half.toFloat(src.getShort(p))
                            row[i + 1] += Half.toFloat(src.getShort(p + 2))
                            row[i + 2] += Half.toFloat(src.getShort(p + 4))
                            p += 8
                        }
                    }
                }
                var o = y * w * 8
                for (x in 0 until w) {
                    out.putShort(o, Half.toHalf(row[x * 3] * norm))
                    out.putShort(o + 2, Half.toHalf(row[x * 3 + 1] * norm))
                    out.putShort(o + 4, Half.toHalf(row[x * 3 + 2] * norm))
                    out.putShort(o + 6, one)
                    o += 8
                }
            }
            return LinearImage(w, h, out)
        }
    }
}
