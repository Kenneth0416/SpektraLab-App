package app.spektralab.raw

/**
 * Byte and bit level readers shared by the format readers.
 *
 * The bit pumps never throw: reading past [end] yields zero bits (as dcraw / LibRaw do
 * for truncated files); check [MsbBitPump.consumedPadding] to detect consuming missing
 * bits, or [MsbBitPump.overrun] to detect look-ahead past the input. The [Bytes] helpers
 * throw IndexOutOfBounds like plain array access; [RawReader] catches that.
 */
object Bytes {
    fun u8(b: ByteArray, p: Int) = b[p].toInt() and 0xff
    fun u16le(b: ByteArray, p: Int) = (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8)
    fun u16be(b: ByteArray, p: Int) = ((b[p].toInt() and 0xff) shl 8) or (b[p + 1].toInt() and 0xff)
    fun u16(b: ByteArray, p: Int, littleEndian: Boolean) = if (littleEndian) u16le(b, p) else u16be(b, p)
    fun u32le(b: ByteArray, p: Int) = (u16le(b, p).toLong() or (u16le(b, p + 2).toLong() shl 16))
    fun u32be(b: ByteArray, p: Int) = ((u16be(b, p).toLong() shl 16) or u16be(b, p + 2).toLong())
    /** Unsigned 32-bit value as a non-negative Long. */
    fun u32(b: ByteArray, p: Int, littleEndian: Boolean) = if (littleEndian) u32le(b, p) else u32be(b, p)
    fun u64(b: ByteArray, p: Int, littleEndian: Boolean): Long =
        if (littleEndian) u32le(b, p) or (u32le(b, p + 4) shl 32) else (u32be(b, p) shl 32) or u32be(b, p + 4)

    /** True when [b] holds the ASCII string [s] at [p]. Never throws. */
    fun matches(b: ByteArray, p: Int, s: String): Boolean {
        if (p < 0 || p.toLong() + s.length > b.size) return false
        for (i in s.indices) if (b[p + i] != s[i].code.toByte()) return false
        return true
    }
}

/**
 * MSB-first bit pump: the first bit of the stream is the most significant bit of the
 * first byte (TIFF packed data, JPEG, Nikon, Canon, Pentax, ...).
 *
 * With [jpegStuffing], a 0xFF byte followed by 0x00 yields 0xFF and the 0x00 is dropped,
 * and a 0xFF followed by anything else is a marker: the pump stops there (feeding zero
 * bits) and records its position in [marker]. This is dcraw's `zero_after_ff`.
 */
class MsbBitPump(private val buf: ByteArray, start: Int = 0, private val end: Int = buf.size, private val jpegStuffing: Boolean = false) {
    private var pos = start.coerceAtLeast(0)
    private var cache = 0L
    private var count = 0
    /** Real buffered bits, excluding zero padding added for Huffman look-ahead. */
    internal var availableBits = 0; private set
    /** True only when a read consumes synthetic bits, not when a peek reaches EOF. */
    var consumedPadding = false; private set
    private var stopped = false
    /** Absolute position of the marker that stopped the pump, or -1. */
    var marker = -1; private set
    /** True once zero bits past [end] (or a marker) have been fed. */
    var overrun = false; private set

    private fun fill() {
        while (count <= 56) {
            var v = 0
            if (!stopped && pos < end) {
                v = buf[pos].toInt() and 0xff
                if (jpegStuffing && v == 0xff) {
                    val next = if (pos + 1 < end) buf[pos + 1].toInt() and 0xff else -1
                    if (next == 0) pos += 2
                    else { stopped = true; marker = pos; v = 0; overrun = true }
                } else pos++
                if (!stopped) availableBits += 8
            } else { overrun = true; stopped = true }
            cache = (cache shl 8) or v.toLong()
            count += 8
        }
    }

    /** Next [n] bits (0..32) without consuming them. */
    fun peek(n: Int): Int {
        if (count < n) fill()
        return ((cache ushr (count - n)) and ((1L shl n) - 1)).toInt()
    }

    fun skip(n: Int) {
        if (count < n) fill()
        if (n > availableBits) consumedPadding = true
        availableBits = (availableBits - n).coerceAtLeast(0)
        count -= n
    }

    /** Reads [n] bits (0..32). */
    fun bits(n: Int): Int { val v = peek(n); skip(n); return v }

    fun bit() = bits(1)

    /** Drops bits up to the next byte boundary. */
    fun align() { skip(count and 7) }

    /** Absolute position of the first byte not yet (even partly) consumed, ignoring stuffing. */
    val bytePosition get() = if (stopped && marker >= 0) marker else pos - count / 8

    /** Resynchronises at absolute position [p] (after a restart marker, for instance). */
    fun reset(p: Int) {
        pos = p; cache = 0; count = 0; availableBits = 0
        stopped = false; marker = -1; consumedPadding = false; overrun = false
    }
}

/**
 * LSB-first bit pump: the first bit is the least significant bit of the first byte
 * (some Nikon / Sony / Panasonic layouts, and little-endian 12-bit packing).
 */
class LsbBitPump(private val buf: ByteArray, start: Int = 0, private val end: Int = buf.size) {
    private var pos = start.coerceAtLeast(0)
    private var cache = 0L
    private var count = 0
    var overrun = false; private set

    private fun fill() {
        while (count <= 56) {
            val v = if (pos < end) buf[pos].toInt() and 0xff else { overrun = true; 0 }
            pos++
            cache = cache or (v.toLong() shl count)
            count += 8
        }
    }

    fun peek(n: Int): Int { if (count < n) fill(); return (cache and ((1L shl n) - 1)).toInt() }
    fun skip(n: Int) { if (count < n) fill(); cache = cache ushr n; count -= n }
    fun bits(n: Int): Int { val v = peek(n); skip(n); return v }
    fun align() { skip(count and 7) }
    val bytePosition get() = pos - count / 8
}

/**
 * MSB-first pump over little-endian words of [wordBytes] (2 or 4) bytes: each word is
 * loaded as a little-endian integer and its bits are consumed from the top (rawspeed's
 * BitPumpMSB16 / BitPumpMSB32; Samsung, some Sony and Olympus layouts).
 */
class MsbWordBitPump(private val buf: ByteArray, start: Int = 0, private val end: Int = buf.size, private val wordBytes: Int = 4) {
    init { require(wordBytes == 2 || wordBytes == 4) }
    private var pos = start.coerceAtLeast(0)
    private var cache = 0L
    private var count = 0
    var overrun = false; private set

    private fun fill() {
        val wordBits = wordBytes * 8
        while (count <= 64 - wordBits) {
            var w = 0L
            for (i in 0 until wordBytes) {
                val v = if (pos + i < end) buf[pos + i].toLong() and 0xff else { overrun = true; 0L }
                w = w or (v shl (8 * i))
            }
            pos += wordBytes
            cache = (cache shl wordBits) or w
            count += wordBits
        }
    }

    fun peek(n: Int): Int { if (count < n) fill(); return ((cache ushr (count - n)) and ((1L shl n) - 1)).toInt() }
    fun skip(n: Int) { if (count < n) fill(); count -= n }
    fun bits(n: Int): Int { val v = peek(n); count -= n; return v }
}

/** Unpackers for uncompressed sensor rows into a 16-bit [RawImage.data]-style array. */
object Unpack {
    enum class BitOrder {
        /** First pixel in the high bits of the first byte (TIFF / DNG packing, "big endian" 12 bit). */
        MSB,
        /** First pixel in the low bits of the first byte ("little endian" 12 bit: p0 = b0 | (b1 & 15) << 8). */
        LSB,
        /** [MsbWordBitPump] with 16-bit little-endian words. */
        MSB16,
        /** [MsbWordBitPump] with 32-bit little-endian words. */
        MSB32,
    }

    /** Bytes in a packed row of [width] samples of [bits], rounded up to a byte. */
    fun rowBytes(width: Int, bits: Int): Long = (width.toLong() * bits + 7) / 8

    /**
     * Unpacks [height] rows of [width] samples of [bits] (1..16) each from [src].
     * Row r starts at `srcOffset + r * srcRowBytes`; with [srcRowBytes] <= 0 the rows
     * follow each other in one continuous bit stream. Bits = 16 with [BitOrder.MSB] reads
     * big-endian words and with [BitOrder.LSB] little-endian words (see also [u16]).
     * Missing data is left as zero.
     * @return true when the source held all the requested rows.
     */
    fun packed(
        src: ByteArray, srcOffset: Int, srcRowBytes: Int, width: Int, height: Int, bits: Int, order: BitOrder,
        dst: ShortArray, dstOffset: Int = 0, dstStride: Int = width,
    ): Boolean {
        require(bits in 1..16 && width >= 0 && height >= 0)
        if (width == 0 || height == 0) return true
        if (srcRowBytes <= 0) {
            val need = rowBytes(width, bits) * height
            val complete = srcOffset >= 0 && srcOffset + need <= src.size
            fun put(r: Int, c: Int, v: Int) { dst[dstOffset + r * dstStride + c] = v.toShort() }
            when (order) {
                BitOrder.MSB -> { val p = MsbBitPump(src, srcOffset); for (r in 0 until height) for (c in 0 until width) put(r, c, p.bits(bits)) }
                BitOrder.LSB -> { val p = LsbBitPump(src, srcOffset); for (r in 0 until height) for (c in 0 until width) put(r, c, p.bits(bits)) }
                else -> { val p = MsbWordBitPump(src, srcOffset, wordBytes = if (order == BitOrder.MSB16) 2 else 4); for (r in 0 until height) for (c in 0 until width) put(r, c, p.bits(bits)) }
            }
            return complete
        }
        val need = rowBytes(width, bits)
        if (srcOffset < 0) return false
        val rows = if (srcOffset + need > src.size) 0 else minOf(height.toLong(), (src.size - srcOffset - need) / srcRowBytes + 1).toInt()
        for (r in 0 until rows) {
            val s = srcOffset + r * srcRowBytes
            val d = dstOffset + r * dstStride
            when {
                bits == 8 && (order == BitOrder.MSB || order == BitOrder.LSB) -> for (c in 0 until width) dst[d + c] = (src[s + c].toInt() and 0xff).toShort()
                bits == 16 && (order == BitOrder.MSB || order == BitOrder.LSB) ->
                    java.nio.ByteBuffer.wrap(src, s, width * 2)
                        .order(if (order == BitOrder.LSB) java.nio.ByteOrder.LITTLE_ENDIAN else java.nio.ByteOrder.BIG_ENDIAN)
                        .asShortBuffer().get(dst, d, width)
                bits == 12 && order == BitOrder.MSB -> unpack12(src, s, dst, d, width, msb = true)
                bits == 12 && order == BitOrder.LSB -> unpack12(src, s, dst, d, width, msb = false)
                order == BitOrder.MSB -> { val p = MsbBitPump(src, s, s + need.toInt()); for (c in 0 until width) dst[d + c] = p.bits(bits).toShort() }
                order == BitOrder.LSB -> { val p = LsbBitPump(src, s, s + need.toInt()); for (c in 0 until width) dst[d + c] = p.bits(bits).toShort() }
                else -> {
                    val p = MsbWordBitPump(src, s, minOf(src.size, s + srcRowBytes), if (order == BitOrder.MSB16) 2 else 4)
                    for (c in 0 until width) dst[d + c] = p.bits(bits).toShort()
                }
            }
        }
        return rows == height
    }

    private fun unpack12(src: ByteArray, s: Int, dst: ShortArray, d: Int, width: Int, msb: Boolean) {
        var i = s
        var c = 0
        while (c + 1 < width) {
            val b0 = src[i].toInt() and 0xff; val b1 = src[i + 1].toInt() and 0xff; val b2 = src[i + 2].toInt() and 0xff
            if (msb) { dst[d + c] = ((b0 shl 4) or (b1 ushr 4)).toShort(); dst[d + c + 1] = (((b1 and 15) shl 8) or b2).toShort() }
            else { dst[d + c] = (b0 or ((b1 and 15) shl 8)).toShort(); dst[d + c + 1] = ((b1 ushr 4) or (b2 shl 4)).toShort() }
            i += 3; c += 2
        }
        if (c < width) {
            val b0 = src[i].toInt() and 0xff; val b1 = src[i + 1].toInt() and 0xff
            dst[d + c] = (if (msb) (b0 shl 4) or (b1 ushr 4) else b0 or ((b1 and 15) shl 8)).toShort()
        }
    }

    /** 16-bit samples (any bit depth stored in 16-bit containers). Same contract as [packed]. */
    fun u16(src: ByteArray, srcOffset: Int, srcRowBytes: Int, width: Int, height: Int, littleEndian: Boolean,
            dst: ShortArray, dstOffset: Int = 0, dstStride: Int = width): Boolean =
        packed(src, srcOffset, if (srcRowBytes <= 0) width * 2 else srcRowBytes, width, height, 16,
            if (littleEndian) BitOrder.LSB else BitOrder.MSB, dst, dstOffset, dstStride)
}
