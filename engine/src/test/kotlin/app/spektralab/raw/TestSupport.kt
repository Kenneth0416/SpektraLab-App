package app.spektralab.raw

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap

/**
 * Test-only writers for TIFF structures, packed bit streams and lossless JPEG, used to
 * build synthetic raw files. Format authors can reuse them for their own tests.
 */
class TiffBuilder(val littleEndian: Boolean = true) {
    sealed class V
    class Num(val type: Int, val values: DoubleArray) : V()
    class Raw(val type: Int, val count: Int, val bytes: ByteArray) : V()
    class Ifds(val ifds: List<Ifd>) : V()
    class Blobs(val blobs: List<ByteArray>) : V()

    class Ifd {
        val tags = TreeMap<Int, V>()
        var next: Ifd? = null
        fun byte(tag: Int, vararg v: Int) = apply { tags[tag] = Num(1, v.map { it.toDouble() }.toDoubleArray()) }
        fun short(tag: Int, vararg v: Int) = apply { tags[tag] = Num(3, v.map { it.toDouble() }.toDoubleArray()) }
        fun long(tag: Int, vararg v: Int) = apply { tags[tag] = Num(4, v.map { it.toDouble() }.toDoubleArray()) }
        fun rational(tag: Int, vararg v: Double) = apply { tags[tag] = Num(5, v) }
        fun srational(tag: Int, vararg v: Double) = apply { tags[tag] = Num(10, v) }
        fun float(tag: Int, vararg v: Double) = apply { tags[tag] = Num(11, v) }
        fun double(tag: Int, vararg v: Double) = apply { tags[tag] = Num(12, v) }
        fun ascii(tag: Int, s: String) = apply { val b = (s + "\u0000").toByteArray(Charsets.ISO_8859_1); tags[tag] = Raw(2, b.size, b) }
        fun undefined(tag: Int, b: ByteArray) = apply { tags[tag] = Raw(7, b.size, b) }
        fun subIfds(tag: Int, vararg ifds: Ifd) = apply { tags[tag] = Ifds(ifds.toList()) }
        /** Offsets tag pointing at [blobs]; also writes the matching byte-count tag. */
        fun blobs(offsetsTag: Int, countsTag: Int, blobs: List<ByteArray>) = apply {
            tags[offsetsTag] = Blobs(blobs)
            tags[countsTag] = Num(4, blobs.map { it.size.toDouble() }.toDoubleArray())
        }
    }

    private fun size(v: V): Int = when (v) {
        is Num -> v.values.size * TiffIfd.typeSize(v.type)
        is Raw -> v.bytes.size
        is Ifds -> v.ifds.size * 4
        is Blobs -> v.blobs.size * 4
    }

    private fun count(v: V) = when (v) { is Num -> v.values.size; is Raw -> v.count; is Ifds -> v.ifds.size; is Blobs -> v.blobs.size }
    private fun type(v: V) = when (v) { is Num -> v.type; is Raw -> v.type; else -> 4 }

    /**
     * Serialises [ifd0], everything it points to and the blobs. Offsets are relative to
     * the start of the output. [prefix] replaces the 8-byte TIFF header (the IFD then
     * starts right after it), for maker notes with their own headers.
     */
    fun build(ifd0: Ifd, prefix: ByteArray? = null): ByteArray {
        val order = ArrayList<Ifd>()
        fun visit(i: Ifd) {
            if (order.any { it === i }) return
            order += i
            i.tags.values.forEach { if (it is Ifds) it.ifds.forEach(::visit) }
            i.next?.let(::visit)
        }
        visit(ifd0)
        val headerSize = prefix?.size ?: 8
        val ifdPos = HashMap<Ifd, Int>()
        var p = headerSize
        for (i in order) {
            ifdPos[i] = p
            p += 2 + 12 * i.tags.size + 4
            for (v in i.tags.values) size(v).let { if (it > 4) p += it + (it and 1) }
        }
        val blobPos = HashMap<ByteArray, Int>()
        for (i in order) for (v in i.tags.values) if (v is Blobs) for (b in v.blobs) { blobPos[b] = p; p += b.size + (b.size and 1) }
        val out = ByteBuffer.allocate(p).order(if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        if (prefix != null) out.put(prefix) else {
            out.put((if (littleEndian) "II" else "MM").toByteArray()); out.putShort(42); out.putInt(headerSize)
        }
        for (i in order) {
            val start = ifdPos[i]!!
            out.position(start)
            out.putShort(i.tags.size.toShort())
            var data = start + 2 + 12 * i.tags.size + 4
            val deferred = ArrayList<Pair<Int, V>>()
            for ((tag, v) in i.tags) {
                out.putShort(tag.toShort()); out.putShort(type(v).toShort()); out.putInt(count(v))
                val s = size(v)
                if (s > 4) { out.putInt(data); deferred += data to v; data += s + (s and 1) }
                else { val at = out.position(); writeValue(out, v, ifdPos, blobPos); out.position(at + 4) }
            }
            out.putInt(i.next?.let { ifdPos[it]!! } ?: 0)
            for ((at, v) in deferred) { out.position(at); writeValue(out, v, ifdPos, blobPos) }
        }
        for ((b, at) in blobPos) { out.position(at); out.put(b) }
        return out.array()
    }

    private fun writeValue(out: ByteBuffer, v: V, ifdPos: Map<Ifd, Int>, blobPos: Map<ByteArray, Int>) {
        when (v) {
            is Raw -> out.put(v.bytes)
            is Ifds -> v.ifds.forEach { out.putInt(ifdPos[it]!!) }
            is Blobs -> v.blobs.forEach { out.putInt(blobPos[it]!!) }
            is Num -> v.values.forEach { x ->
                when (v.type) {
                    1, 6, 7 -> out.put(x.toInt().toByte())
                    3, 8 -> out.putShort(x.toInt().toShort())
                    4, 9 -> out.putInt(x.toLong().toInt())
                    5, 10 -> if (x == Math.rint(x) && kotlin.math.abs(x) < 2e9) { out.putInt(x.toInt()); out.putInt(1) }
                             else { out.putInt(Math.round(x * 1_000_000).toInt()); out.putInt(1_000_000) }
                    11 -> out.putFloat(x.toFloat())
                    12 -> out.putDouble(x)
                }
            }
        }
    }
}

/** MSB-first bit writer, optionally with JPEG 0xFF00 byte stuffing. */
class MsbBitWriter(private val stuffing: Boolean = false) {
    private val out = ByteArrayOutputStream()
    private var acc = 0L
    private var n = 0
    fun put(value: Int, bits: Int) {
        if (bits == 0) return
        acc = (acc shl bits) or (value.toLong() and ((1L shl bits) - 1)); n += bits
        while (n >= 8) { n -= 8; byte(((acc ushr n) and 0xff).toInt()) }
    }
    private fun byte(b: Int) { out.write(b); if (stuffing && b == 0xff) out.write(0) }
    /** Pads to a byte with [one] bits. */
    fun flush(one: Boolean = true) { if (n > 0) put(if (one) (1 shl (8 - n)) - 1 else 0, 8 - n) }
    fun raw(b: Int) = out.write(b)
    fun bytes(): ByteArray { flush(); return out.toByteArray() }
    /** Flushes and returns the bytes written since the last call. */
    fun take(): ByteArray { flush(); val b = out.toByteArray(); out.reset(); return b }
}

/** Packs samples the way [Unpack] reads them (reference implementation for tests). */
object Pack {
    fun pack(samples: IntArray, bits: Int, order: Unpack.BitOrder, rowWidth: Int, rowBytes: Int): ByteArray {
        val rows = samples.size / rowWidth
        val out = ByteArray(rows * rowBytes + 8)
        for (r in 0 until rows) {
            val bitsList = ArrayList<Int>()          // bit stream for this row, in stream order
            for (c in 0 until rowWidth) {
                val v = samples[r * rowWidth + c]
                for (b in 0 until bits) bitsList += if (order == Unpack.BitOrder.LSB) (v ushr b) and 1 else (v ushr (bits - 1 - b)) and 1
            }
            for ((i, bit) in bitsList.withIndex()) {
                if (bit == 0) continue
                val byteIndex: Int; val bitInByte: Int
                when (order) {
                    Unpack.BitOrder.MSB -> { byteIndex = i / 8; bitInByte = 7 - i % 8 }
                    Unpack.BitOrder.LSB -> { byteIndex = i / 8; bitInByte = i % 8 }
                    else -> {
                        val ws = if (order == Unpack.BitOrder.MSB16) 2 else 4
                        val word = i / (8 * ws); val inWord = 8 * ws - 1 - i % (8 * ws)
                        byteIndex = word * ws + inWord / 8; bitInByte = inWord % 8
                    }
                }
                out[r * rowBytes + byteIndex] = (out[r * rowBytes + byteIndex].toInt() or (1 shl bitInByte)).toByte()
            }
        }
        return out
    }
}

/** Lossless JPEG encoder (SOF3), following T.81 H.1 including restart intervals. */
object LjpegEncoder {
    /** Code length per category 0..16 for two different tables. */
    val LENGTHS = arrayOf(
        intArrayOf(2, 3, 3, 3, 3, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14),
        intArrayOf(4, 3, 2, 3, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
    )

    private class Code(val code: IntArray, val len: IntArray, val counts: IntArray, val symbols: IntArray)

    private fun canonical(lengths: IntArray): Code {
        val syms = lengths.indices.sortedWith(compareBy({ lengths[it] }, { it }))
        val code = IntArray(17); val len = IntArray(17); val counts = IntArray(16)
        var c = 0; var l = 1
        for (s in syms) {
            while (l < lengths[s]) { c = c shl 1; l++ }
            code[s] = c; len[s] = l; counts[l - 1]++; c++
        }
        return Code(code, len, counts, syms.toIntArray())
    }

    /**
     * @param samples `height * width * nc` values, components interleaved.
     * @param tableFor Huffman table index (0 or 1) per component.
     */
    fun encode(samples: IntArray, width: Int, height: Int, nc: Int, precision: Int, predictor: Int,
               pt: Int = 0, restart: Int = 0, tableFor: IntArray = IntArray(nc) { it % 2 }): ByteArray {
        val codes = LENGTHS.map { canonical(it) }
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v ushr 8); out.write(v and 0xff) }
        u16(0xFFD8)
        // DHT with both tables
        u16(0xFFC4); u16(2 + 2 * (17 + 17))
        for (t in 0..1) { out.write(t); codes[t].counts.forEach { out.write(it) }; codes[t].symbols.forEach { out.write(it) } }
        u16(0xFFC3); u16(8 + 3 * nc); out.write(precision); u16(height); u16(width); out.write(nc)
        for (c in 0 until nc) { out.write(c + 1); out.write(0x11); out.write(0) }
        if (restart > 0) { u16(0xFFDD); u16(4); u16(restart) }
        u16(0xFFDA); u16(6 + 2 * nc); out.write(nc)
        for (c in 0 until nc) { out.write(c + 1); out.write(tableFor[c] shl 4) }
        out.write(predictor); out.write(0); out.write(pt)

        val w = MsbBitWriter(stuffing = true)
        val v = IntArray(samples.size) { samples[it] ushr pt }
        fun at(x: Int, y: Int, c: Int) = v[(y * width + x) * nc + c]
        var mcu = 0
        var rst = 0
        var resetRow = 0; var resetCol = 0
        for (y in 0 until height) for (x in 0 until width) {
            if (restart > 0 && mcu == restart) {
                out.write(w.take()); out.write(0xFF); out.write(0xD0 + (rst++ and 7))
                mcu = 0; resetRow = y; resetCol = x
            }
            mcu++
            for (c in 0 until nc) {
                val pred = when {
                    y == resetRow && x == resetCol -> 1 shl (precision - pt - 1)
                    y == resetRow -> at(x - 1, y, c)
                    x == 0 -> at(x, y - 1, c)
                    else -> {
                        val ra = at(x - 1, y, c); val rb = at(x, y - 1, c); val rc = at(x - 1, y - 1, c)
                        when (predictor) { 1 -> ra; 2 -> rb; 3 -> rc; 4 -> ra + rb - rc; 5 -> ra + ((rb - rc) shr 1); 6 -> rb + ((ra - rc) shr 1); else -> (ra + rb) ushr 1 }
                    }
                }
                var d = (at(x, y, c) - pred) and 0xffff
                if (d > 32768) d -= 65536
                val code = codes[tableFor[c]]
                if (d == 32768) { w.put(code.code[16], code.len[16]); continue }
                val mag = kotlin.math.abs(d)
                val cat = if (mag == 0) 0 else 32 - Integer.numberOfLeadingZeros(mag)
                w.put(code.code[cat], code.len[cat])
                if (cat > 0) w.put(if (d > 0) d else d + (1 shl cat) - 1, cat)
            }
        }
        out.write(w.take())
        u16(0xFFD9)
        return out.toByteArray()
    }
}
