package app.spektralab.raw

/*
 * Lossless JPEG (ITU-T T.81 process 14, SOF3) as used inside DNG, Canon CR2, Kodak,
 * Leica, Hasselblad, Pentax and other raw files. The predictor and restart-interval
 * handling follow T.81 H.1.2 and the Adobe DNG SDK (dng_lossless_jpeg.cpp); the
 * category-16 rule follows dcraw / LibRaw's ljpeg_diff (LGPL 2.1 / CDDL; ported under GPLv3).
 */

/**
 * A canonical Huffman table as stored in a JPEG DHT segment: [counts] of codes of each
 * length 1..16 and the [symbols] in code order. Also used by maker formats with
 * JPEG-style tables (Nikon, Sony, Pentax, Samsung): build one with [fromJpeg] and read
 * with [decodeSymbol] / [decodeDiff] from an [MsbBitPump].
 */
class HuffmanTable private constructor(private val lut: IntArray) {
    /** Decodes one symbol, or -1 when the bits are not a valid code. */
    fun decodeSymbol(p: MsbBitPump): Int {
        val e = lut[p.peek(16)]
        if (e == 0) return -1
        p.skip(e ushr 8)
        return e and 0xff
    }

    /**
     * Decodes a difference value: a symbol giving the bit length, then that many bits,
     * sign-extended as in JPEG. Length 16 means 32768 without extra bits. Returns
     * [Int.MIN_VALUE] on an invalid code.
     */
    fun decodeDiff(p: MsbBitPump): Int {
        // code (<= 16 bits) and magnitude (<= 15 bits) fit in one 32-bit peek
        val bits32 = p.peek(32)
        val e = lut[bits32 ushr 16]
        if (e == 0) return Int.MIN_VALUE
        val codeLen = e ushr 8
        val len = e and 0xff
        if (len == 0) { p.skip(codeLen); return 0 }
        if (len >= 16) { p.skip(codeLen); return 32768 }
        val total = codeLen + len
        p.skip(total)
        val v = (bits32 ushr (32 - total)) and ((1 shl len) - 1)
        return if (v < (1 shl (len - 1))) v - (1 shl len) + 1 else v
    }

    companion object {
        /**
         * Builds a table from DHT-style data: [counts] (16 entries, codes of length 1..16)
         * and [symbols]. Returns null for an over-subscribed / malformed table.
         */
        fun fromJpeg(counts: IntArray, symbols: IntArray): HuffmanTable? {
            if (counts.size < 16) return null
            val lut = IntArray(1 shl 16)
            var code = 0
            var k = 0
            for (len in 1..16) {
                val n = counts[len - 1]
                if (n < 0 || k + n > symbols.size) return null
                for (i in 0 until n) {
                    if (code >= (1 shl len)) return null
                    val sym = symbols[k++] and 0xff
                    val shift = 16 - len
                    val from = code shl shift
                    val entry = (len shl 8) or sym
                    for (j in from until from + (1 shl shift)) lut[j] = entry
                    code++
                }
                code = code shl 1
            }
            return HuffmanTable(lut)
        }

        /**
         * dcraw-style table: 16 counts followed by the symbols in one array, as in the
         * constant tables of nikon_load_raw / pentax / sony readers.
         */
        fun fromDcraw(source: IntArray): HuffmanTable? =
            if (source.size < 16) null else fromJpeg(source.copyOfRange(0, 16), source.copyOfRange(16, source.size))
    }
}

/** Lossless JPEG decoder. Output is the raw component sample stream; callers own the layout. */
object LosslessJpeg {
    /** Frame and scan parameters of a lossless JPEG. */
    class Header(
        /** Samples per line (per component) and lines, from SOF3. */
        val width: Int,
        val height: Int,
        val components: Int,
        /** Sample precision in bits (2..16). */
        val precision: Int,
        /** Predictor selection value 1..7 (0 is accepted and means no prediction). */
        val predictor: Int,
        val pointTransform: Int,
        /** Restart interval in MCUs (one sample of every component), 0 for none. */
        val restartInterval: Int,
        /** Absolute position of the entropy-coded data. */
        internal val scanStart: Int,
        internal val end: Int,
        internal val tables: Array<HuffmanTable>,
    ) {
        /** Samples in one decoded row: [width] x [components], components interleaved. */
        val rowSamples get() = width * components
    }

    /** Receives each decoded row: `samples[0 until header.rowSamples]`, components interleaved, values 0..65535. */
    fun interface RowSink { fun row(y: Int, samples: IntArray) }

    /** Parses the markers up to the start of scan. Null when this is not a decodable lossless JPEG. */
    fun readHeader(src: ByteArray, offset: Int = 0, length: Int = src.size - offset): Header? = runCatching { header(src, offset, length) }.getOrNull()

    /**
     * Decodes the whole image, calling [sink] once per row in order.
     * @return the header on success, null when the data is not a supported lossless JPEG
     *   or the entropy-coded data is corrupt (rows already delivered stay delivered).
     */
    fun decode(src: ByteArray, offset: Int, length: Int, sink: RowSink): Header? = runCatching {
        val h = header(src, offset, length) ?: return null
        if (decodeScan(src, h, sink)) h else null
    }.getOrNull()

    /** Decoded frame as one array of `height * width * components` samples (row-major, interleaved). */
    class Frame(val header: Header, val samples: ShortArray)

    fun decode(src: ByteArray, offset: Int = 0, length: Int = src.size - offset): Frame? {
        val h = readHeader(src, offset, length) ?: return null
        val rs = h.rowSamples
        val out = ShortArray(rs * h.height)
        return decode(src, offset, length) { y, s -> for (i in 0 until rs) out[y * rs + i] = s[i].toShort() }?.let { Frame(h, out) }
    }

    private fun header(src: ByteArray, offset: Int, length: Int): Header? {
        if (offset < 0 || length < 4 || offset.toLong() + length > src.size) return null
        val end = offset + length
        if (Bytes.u16be(src, offset) != 0xFFD8) return null
        var p = offset + 2
        val tables = arrayOfNulls<HuffmanTable>(4)
        var width = 0; var height = 0; var precision = 0; var comps = 0
        val compIds = IntArray(4)
        var restart = 0
        while (p + 4 <= end) {
            if (src[p] != 0xFF.toByte()) { p++; continue }
            val m = src[p + 1].toInt() and 0xff
            if (m == 0xFF) { p++; continue }
            if (m == 0xD8 || m in 0xD0..0xD7 || m == 0x01) { p += 2; continue }
            if (m == 0xD9) return null
            val len = Bytes.u16be(src, p + 2)
            val seg = p + 4
            val segEnd = p + 2 + len
            if (len < 2 || segEnd > end) return null
            when (m) {
                0xC4 -> {
                    var q = seg
                    while (q + 17 <= segEnd) {
                        val id = src[q].toInt() and 0x0f
                        val counts = IntArray(16) { src[q + 1 + it].toInt() and 0xff }
                        val n = counts.sum()
                        if (q + 17 + n > segEnd || id > 3) return null
                        tables[id] = HuffmanTable.fromJpeg(counts, IntArray(n) { src[q + 17 + it].toInt() and 0xff }) ?: return null
                        q += 17 + n
                    }
                }
                0xC3 -> {
                    if (len < 8) return null
                    precision = src[seg].toInt() and 0xff
                    height = Bytes.u16be(src, seg + 1)
                    width = Bytes.u16be(src, seg + 3)
                    comps = src[seg + 5].toInt() and 0xff
                    if (comps !in 1..4 || len < 8 + 3 * comps) return null
                    for (c in 0 until comps) {
                        compIds[c] = src[seg + 6 + 3 * c].toInt() and 0xff
                        // subsampled components (Canon sRAW) are not handled here
                        if (src[seg + 7 + 3 * c].toInt() and 0xff != 0x11) return null
                    }
                }
                in 0xC0..0xCF -> if (m != 0xC4 && m != 0xC8 && m != 0xCC) return null   // other SOFs: not lossless
                0xDD -> restart = Bytes.u16be(src, seg)
                0xDA -> {
                    if (comps == 0 || precision !in 2..16 || width == 0 || height == 0) return null
                    val ns = src[seg].toInt() and 0xff
                    if (ns != comps || len < 6 + 2 * ns) return null
                    val order = Array(ns) { i ->
                        val id = src[seg + 1 + 2 * i].toInt() and 0xff
                        val td = (src[seg + 2 + 2 * i].toInt() and 0xff) ushr 4
                        if (td > 3) return null
                        // scan components must follow the frame order for an interleaved output
                        if (compIds.indexOf(id).let { it in 0 until comps && it != i }) return null
                        tables[td] ?: return null
                    }
                    val q = seg + 1 + 2 * ns
                    val predictor = src[q].toInt() and 0xff
                    val pt = src[q + 2].toInt() and 0x0f
                    if (predictor > 7 || pt >= precision) return null
                    // every sample costs at least one bit: reject sizes the data cannot hold
                    val samples = width.toLong() * height * comps
                    if (samples > RawDecodeLimits.MAX_SAMPLES || samples > (end - segEnd).toLong() * 8) return null
                    return Header(width, height, comps, precision, predictor, pt, restart, segEnd, end, order)
                }
            }
            p = segEnd
        }
        return null
    }

    private fun decodeScan(src: ByteArray, h: Header, sink: RowSink): Boolean {
        val nc = h.components
        val rs = h.rowSamples
        var prev = IntArray(rs)
        var cur = IntArray(rs)
        val out = if (h.pointTransform != 0) IntArray(rs) else null
        val tables = h.tables
        val pump = MsbBitPump(src, h.scanStart, h.end, jpegStuffing = true)
        val initial = 1 shl (h.precision - h.pointTransform - 1)
        val psv = h.predictor
        val ri = h.restartInterval
        var mcusLeft = if (ri > 0) ri else -1
        var restartIndex = 0
        var firstLineRow = 0      // row of the most recent (re)start: that line uses predictor 1
        var resetAt = 0           // column of the sample right after a (re)start, -1 when none pending
        var resetRow = 0
        for (y in 0 until h.height) {
            for (x in 0 until h.width) {
                if (mcusLeft == 0) {
                    if (!consumeMarker(src, pump, h.end, 0xD0 + (restartIndex++ and 7))) return false
                    mcusLeft = ri
                    firstLineRow = y; resetRow = y; resetAt = x
                }
                if (mcusLeft > 0) mcusLeft--
                val base = x * nc
                for (c in 0 until nc) {
                    val diff = tables[c].decodeDiff(pump)
                    if (diff == Int.MIN_VALUE || pump.consumedPadding) return false
                    val i = base + c
                    val pred = when {
                        y == resetRow && x == resetAt -> initial
                        y == firstLineRow -> cur[i - nc]
                        x == 0 -> prev[i]
                        else -> {
                            val ra = cur[i - nc]; val rb = prev[i]; val rc = prev[i - nc]
                            when (psv) {
                                1 -> ra
                                2 -> rb
                                3 -> rc
                                4 -> ra + rb - rc
                                5 -> ra + ((rb - rc) shr 1)
                                6 -> rb + ((ra - rc) shr 1)
                                7 -> (ra + rb) ushr 1
                                else -> 0
                            }
                        }
                    }
                    cur[i] = (pred + diff) and 0xffff
                }
            }
            if (out != null) {
                for (i in 0 until rs) out[i] = (cur[i] shl h.pointTransform) and 0xffff
                sink.row(y, out)
            } else sink.row(y, cur)
            val t = prev; prev = cur; cur = t
        }
        return consumeMarker(src, pump, h.end, 0xD9)
    }

    /** Only byte-alignment padding may precede the expected RSTn or EOI marker. */
    private fun consumeMarker(src: ByteArray, pump: MsbBitPump, end: Int, expected: Int): Boolean {
        // Look ahead far enough to encounter a marker after up to seven pad bits.
        // Unlike consumedPadding, overrun may legitimately be true here.
        pump.peek(32)
        if (pump.consumedPadding || pump.availableBits > 7) return false
        var p = pump.marker
        if (p < 0) return false
        while (p + 1 < end && src[p + 1] == 0xFF.toByte()) p++ // JPEG marker fill bytes
        if (p + 1 >= end || src[p + 1].toInt() and 0xff != expected) return false
        pump.reset(p + 2)
        return true
    }
}
