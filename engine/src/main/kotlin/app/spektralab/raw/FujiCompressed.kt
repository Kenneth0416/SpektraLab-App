// Fujifilm lossless-compressed RAF decoder.
//
// Ported from LibRaw 0.22.2 src/decoders/fuji_compressed.cpp (Copyright (C) 2016-2019 Alexey
// Danilchenko, adapted to LibRaw by Alex Tutubalin; LibRaw is LGPL 2.1 / CDDL 1.0, which permits
// use in this GPLv3 program). Only the lossless mode is ported; lossy files return null.
package app.spektralab.raw

import java.util.stream.IntStream

/**
 * Fujifilm's lossless compression ("compressed" RAF, X-Trans and Bayer). The frame is cut
 * into vertical strips of 768 pixels coded independently; each strip is coded 6 rows at a
 * time, per colour, with a gradient-adaptive Golomb-like code ([Block]).
 *
 * [decode] is strict: any out-of-range code or a read past a strip's declared size returns
 * null (LibRaw would only flag a data error for the former).
 */
internal object FujiCompressed {
    class Result(val width: Int, val height: Int, val data: ShortArray)

    /** The 16-byte header at the start of the data (big endian). */
    class Header(
        val lossless: Boolean, val rawType: Int, val bits: Int, val height: Int, val roundedWidth: Int,
        val width: Int, val blockSize: Int, val blocksInRow: Int, val totalLines: Int,
    )

    /** LibRaw `parse_fuji_compressed_header` validation; null when the data is not a compressed stream. */
    fun header(bytes: ByteArray, offset: Int): Header? {
        if (offset < 0 || offset.toLong() + 16 > bytes.size) return null
        fun u8(i: Int) = bytes[offset + i].toInt() and 0xff
        fun u16(i: Int) = Bytes.u16be(bytes, offset + i)
        val signature = u16(0); val lossless = u8(2); val rawType = u8(3); val bits = u8(4)
        val h = u16(5); val roundedW = u16(7); val w = u16(9); val block = u16(11); val blocks = u8(13); val lines = u16(14)
        if (signature != 0x4953 || lossless > 1 || h > 0x4002 || h < 6 || h % 6 != 0 ||
            block < 1 || w > 0x4200 || w < 0x300 || w % 24 != 0 ||
            roundedW > 0x4200 || roundedW < block || roundedW % block != 0 ||
            roundedW - w >= block || block != 0x300 || blocks > 0x10 ||
            blocks == 0 || blocks != roundedW / block || lines > 0xAAB ||
            lines == 0 || lines != h / 6 ||
            (bits != 12 && bits != 14 && bits != 16) || (rawType != 16 && rawType != 0)) return null
        return Header(lossless == 1, rawType, bits, h, roundedW, w, block, blocks, lines)
    }

    /**
     * Decodes the stream at [offset] ([size] bytes). [cfa] is the CFA anchored at raw (0, 0):
     * the 6x6 X-Trans pattern (raw type 16) or the Bayer pattern (raw type 0).
     */
    fun decode(bytes: ByteArray, offset: Int, size: Int, cfa: CfaPattern?, strict: Boolean = true): Result? {
        val h = header(bytes, offset) ?: return null
        if (!h.lossless || cfa == null) return null
        if (h.rawType == 16 && (cfa.width != 6 || cfa.height != 6)) return null
        if (h.rawType == 0 && !cfa.isBayer) return null
        if (h.width.toLong() * h.height > RawDecodeLimits.MAX_SAMPLES) return null
        val end = minOf(bytes.size.toLong(), offset.toLong() + size).toInt()
        val sizesAt = offset + 16
        if (sizesAt + 4L * h.blocksInRow > end) return null
        val blockSizes = LongArray(h.blocksInRow) { Bytes.u32be(bytes, sizesAt + 4 * it) }
        val starts = LongArray(h.blocksInRow)
        starts[0] = sizesAt + ((4L * h.blocksInRow + 0xF) and 0xFL.inv())
        for (b in 1 until h.blocksInRow) starts[b] = starts[b - 1] + blockSizes[b - 1]
        val params = Params(h)
        val colors = IntArray(36) { cfa.color(it / 6, it % 6) }
        // every pixel must come from a coded sample; otherwise this is not the CFA phase the stream was made for
        if (h.rawType == 16 && !allCoded(colors)) return null
        val out = ShortArray(h.width * h.height)
        val ok = BooleanArray(h.blocksInRow)
        IntStream.range(0, h.blocksInRow).parallel().forEach { b ->
            val start = starts[b]
            if (start < 0 || start >= bytes.size) return@forEach
            // LibRaw reads min(file size - start, block size) bytes, then one zero byte, then fails
            val blockEnd = minOf(bytes.size.toLong(), start + blockSizes[b]).toInt()
            val dec = Decoder(params, bytes, start.toInt(), blockEnd)
            val width = if (b + 1 == h.blocksInRow) h.width - h.blockSize * b else h.blockSize
            ok[b] = try {
                for (line in 0 until h.totalLines) {
                    dec.line()
                    if (dec.eof || (strict && dec.errors > 0)) break
                    dec.copyLine(colors, out, h.width, line, b * h.blockSize, width)
                }
                !dec.eof && (!strict || dec.errors == 0)
            } catch (e: RuntimeException) {
                false
            }
        }
        if (!ok.all { it }) return null
        return Result(h.width, h.height, out)
    }

    /**
     * True when, with X-Trans tile [colors] (raw-anchored, row-major 6x6), every pixel of a
     * strip maps to a line-buffer slot the stream codes, rather than one it interpolates.
     * X-Trans streams are made for one phase of the tile (e.g. the X-T2's compressed files
     * declare the uncompressed files' tile shifted down a row).
     */
    fun allCoded(colors: IntArray): Boolean {
        for (row in 0 until 6) for (col in 0 until 12) {
            val c = colors[row * 6 + col % 6]
            val idx = (((col * 2 / 3) and 0x7FFFFFFE) or ((col % 3) and 1)) + ((col % 3) shr 1)
            if ((idx and 1) == 1) continue                      // odd samples are always coded
            val coded = when (c) {
                0 -> when (row shr 1) { 0 -> false; 1 -> (idx and 3) == 2; else -> (idx and 3) == 0 }   // R2, R3, R4
                2 -> when (row shr 1) { 0 -> false; 1 -> (idx and 3) == 0; else -> (idx and 3) == 2 }   // B2, B3, B4
                else -> row != 2 && row != 5                                                        // G4 and G7 even: interpolated
            }
            if (!coded) return false
        }
        return true
    }

    /** Coding parameters for the lossless mode (LibRaw `init_fuji_compr`, `init_main_qtable`). */
    class Params(h: Header) {
        val xtrans = h.rawType == 16
        val lineWidth = if (xtrans) h.blockSize * 2 / 3 else h.blockSize shr 1
        val maxValue = (1 shl h.bits) - 1
        val minValue = 0x40
        /** Quantisation of a difference d in -maxValue..maxValue: qTable[maxValue + d] in -4..4. */
        val qTable = ByteArray(2 shl h.bits)
        val qGradMult = 9
        val totalValues: Int
        val rawBits: Int
        val maxBits: Int

        init {
            val qBase = 0
            val maxVal = maxValue + 1
            val qp = intArrayOf(qBase, 3 * qBase + 0x12, 5 * qBase + 0x43, 7 * qBase + 0x114, maxValue)
            if (qp[1] >= maxVal || qp[1] < qBase + 1) qp[1] = qBase + 1
            if (qp[2] < qp[1] || qp[2] >= maxVal) qp[2] = qp[1]
            if (qp[3] < qp[2] || qp[3] >= maxVal) qp[3] = qp[2]
            var i = 0
            for (v in -qp[4]..qp[4]) {
                qTable[i++] = when {
                    v <= -qp[3] -> -4; v <= -qp[2] -> -3; v <= -qp[1] -> -2; v < -qp[0] -> -1
                    v <= qp[0] -> 0; v < qp[1] -> 1; v < qp[2] -> 2; v < qp[3] -> 3; else -> 4
                }.toByte()
            }
            totalValues = (qp[4] + 2 * qBase) / (2 * qBase + 1) + 1
            rawBits = log2ceil(totalValues)
            maxBits = 4 * log2ceil(qp[4] + 1)
        }

        private fun log2ceil(value: Int): Int {
            var v = value; var r = 0
            if (v-- != 0) do { ++r; v = v shr 1 } while (v != 0)
            return r
        }
    }

    /**
     * The per-strip coding state and the order in which samples are coded (LibRaw
     * `xtrans_decode_block`, `fuji_bayer_decode_block`, `fuji_decode_strip`). Subclasses supply
     * the code of each coded sample: [Decoder] reads it from the stream; a test encoder writes it.
     */
    abstract class Block(val p: Params) {
        val lw = p.lineWidth
        val stride = lw + 2
        /** The 18 line buffers (R0..R4, G0..G7, B0..B4), each with one guard sample on both sides. */
        val lines = IntArray(L_TOTAL * stride)
        private val gradEven = IntArray(3 * 41 * 2)
        private val gradOdd = IntArray(3 * 41 * 2)
        var errors = 0

        init {
            val maxDiff = maxOf(2, (p.totalValues + 0x20) shr 6)
            for (i in 0 until 3 * 41) { gradEven[2 * i] = maxDiff; gradEven[2 * i + 1] = 1; gradOdd[2 * i] = maxDiff; gradOdd[2 * i + 1] = 1 }
        }

        /**
         * The unsigned code of the sample at [slot] (index into [lines]) whose prediction is
         * [pred]; [negative] when the gradient is negative (the code's sign is flipped) and
         * [decBits] the number of low bits the context codes verbatim.
         */
        protected abstract fun nextCode(slot: Int, pred: Int, negative: Boolean, decBits: Int): Int

        /** Start of line buffer [l] plus the guard sample: LibRaw `linebuf[l] + 1`. */
        fun at(l: Int) = l * stride + 1

        private fun bitDiff(v1: Int, v2: Int): Int {
            var d = 0
            if (v2 < v1) while (d <= 14 && (v2 shl ++d) < v1) { /* count */ }
            return d
        }

        private fun quantGradient(v1: Int, v2: Int) =
            p.qGradMult * p.qTable[p.maxValue + v1] + p.qTable[p.maxValue + v2]

        private fun finish(slot: Int, pred: Int, grad: Int, code: Int, grads: IntArray, gi: Int) {
            if (code < 0 || code >= p.totalValues) errors++
            val c = if ((code and 1) != 0) -1 - code / 2 else code / 2
            grads[gi] += if (c < 0) -c else c
            if (grads[gi + 1] == p.minValue) { grads[gi] = grads[gi] shr 1; grads[gi + 1] = grads[gi + 1] shr 1 }
            grads[gi + 1]++
            var v = if (grad < 0) pred - c else pred + c
            if (v < 0) v += p.totalValues
            else if (v > p.maxValue) v -= p.totalValues
            lines[slot] = if (v >= 0) minOf(v, p.maxValue) else 0
        }

        private fun sampleEven(base: Int, pos: Int, set: Int) {
            val s = base + pos
            val rb = lines[s - 2 - lw]; val rc = lines[s - 3 - lw]; val rd = lines[s - 1 - lw]; val rf = lines[s - 4 - 2 * lw]
            val dcb = abs(rc - rb); val dfb = abs(rf - rb); val ddb = abs(rd - rb)
            val grad = quantGradient(rb - rf, rc - rb)
            val gi = (set * 41 + abs(grad)) * 2
            val interp = when {
                dcb > dfb && dcb > ddb -> rf + rd + 2 * rb
                ddb > dcb && ddb > dfb -> rf + rc + 2 * rb
                else -> rd + rc + 2 * rb
            }
            val pred = interp shr 2
            val code = nextCode(s, pred, grad < 0, bitDiff(gradEven[gi], gradEven[gi + 1]))
            finish(s, pred, grad, code, gradEven, gi)
        }

        private fun sampleOdd(base: Int, pos: Int, set: Int) {
            val s = base + pos
            val ra = lines[s - 1]; val rb = lines[s - 2 - lw]; val rc = lines[s - 3 - lw]; val rd = lines[s - 1 - lw]; val rg = lines[s + 1]
            val grad = quantGradient(rb - rc, rc - ra)
            val gi = (set * 41 + abs(grad)) * 2
            val pred = if ((rb > rc && rb > rd) || (rb < rc && rb < rd)) (rg + ra + 2 * rb) shr 2 else (ra + rg) shr 1
            val code = nextCode(s, pred, grad < 0, bitDiff(gradOdd[gi], gradOdd[gi + 1]))
            finish(s, pred, grad, code, gradOdd, gi)
        }

        private fun interpolateEven(base: Int, pos: Int) {
            val s = base + pos
            val rb = lines[s - 2 - lw]; val rc = lines[s - 3 - lw]; val rd = lines[s - 1 - lw]; val rf = lines[s - 4 - 2 * lw]
            val dcb = abs(rc - rb); val dfb = abs(rf - rb); val ddb = abs(rd - rb)
            lines[s] = when {
                dcb > dfb && dcb > ddb -> (rf + rd + 2 * rb) shr 2
                ddb > dcb && ddb > dfb -> (rf + rc + 2 * rb) shr 2
                else -> (rd + rc + 2 * rb) shr 2
            }
        }

        private fun extend(from: Int, to: Int) {
            for (i in from..to) {
                lines[i * stride] = lines[(i - 1) * stride + 1]
                lines[i * stride + lw + 1] = lines[(i - 1) * stride + lw]
            }
        }

        /**
         * One pass over two lines coded together: even samples of A then B, and once 10 even
         * samples are known, the odd ones (which also use their right neighbour). [evenA] and
         * [evenB] tell which even positions are coded; the others are interpolated.
         */
        private fun interleave(lineA: Int, lineB: Int, setA: Int, setB: Int, evenA: (Int) -> Boolean, evenB: (Int) -> Boolean) {
            val a = at(lineA); val b = at(lineB)
            var aEven = 0; var aOdd = 1; var bEven = 0; var bOdd = 1
            while (bEven < lw || bOdd < lw) {
                if (bEven < lw) {
                    if (evenA(aEven)) sampleEven(a, aEven, setA) else interpolateEven(a, aEven)
                    aEven += 2
                    if (evenB(bEven)) sampleEven(b, bEven, setB) else interpolateEven(b, bEven)
                    bEven += 2
                }
                if (bEven > 8) {
                    sampleOdd(a, aOdd, setA); aOdd += 2
                    sampleOdd(b, bOdd, setB); bOdd += 2
                }
            }
        }

        /** Codes the next 6 rows of the strip (one call of LibRaw's *_decode_block plus the line rotation of fuji_decode_strip). */
        fun line() {
            val all: (Int) -> Boolean = { true }
            val none: (Int) -> Boolean = { false }
            if (p.xtrans) {
                // each pass names its lines in the order LibRaw codes them within the loop
                interleave(R2, G2, 0, 0, none, all)
                extend(R2, R4); extend(G2, G7)
                interleave(G3, B2, 1, 1, all, none)
                extend(G2, G7); extend(B2, B4)
                interleave(R3, G4, 2, 2, { (it and 3) != 0 }, none)
                extend(R2, R4); extend(G2, G7)
                interleave(G5, B3, 0, 0, all, { (it and 3) != 2 })
                extend(G2, G7); extend(B2, B4)
                interleave(R4, G6, 1, 1, { (it and 3) != 2 }, all)
                extend(R2, R4); extend(G2, G7)
                interleave(G7, B4, 2, 2, none, { (it and 3) != 0 })
                extend(G2, G7); extend(B2, B4)
            } else {
                interleave(R2, G2, 0, 0, all, all)
                extend(R2, R4); extend(G2, G7)
                interleave(G3, B2, 1, 1, all, all)
                extend(G2, G7); extend(B2, B4)
                interleave(R3, G4, 2, 2, all, all)
                extend(R2, R4); extend(G2, G7)
                interleave(G5, B3, 0, 0, all, all)
                extend(G2, G7); extend(B2, B4)
                interleave(R4, G6, 1, 1, all, all)
                extend(R2, R4); extend(G2, G7)
                interleave(G7, B4, 2, 2, all, all)
                extend(G2, G7); extend(B2, B4)
            }
        }

        /** After [line]: rotate the buffers so the next 6 rows see these as context (fuji_decode_strip). */
        fun rotate() {
            for ((a, b) in MTABLE) System.arraycopy(lines, b * stride, lines, a * stride, stride)
        }

        /** Clears the current-row buffers for the next 6 rows (after [copyLine]). */
        fun clear() {
            for ((a, n) in ZTABLE) {
                java.util.Arrays.fill(lines, a * stride, (a + n) * stride, 0)
                lines[a * stride] = lines[(a - 1) * stride + 1]
                lines[a * stride + lw + 1] = lines[(a - 1) * stride + lw]
            }
        }

        /**
         * Line-buffer slot of pixel [col] of row [row] (0..5) of the current 6-row group
         * (LibRaw copy_line_to_xtrans / copy_line_to_bayer), for colour [color].
         */
        fun slot(row: Int, col: Int, color: Int): Int {
            val l = when (color) { 0 -> R2 + (row shr 1); 2 -> B2 + (row shr 1); else -> G2 + row }
            val index = if (p.xtrans) (((col * 2 / 3) and 0x7FFFFFFE) or ((col % 3) and 1)) + ((col % 3) shr 1) else col shr 1
            return at(l) + index
        }

        /** Rotates, writes the 6 decoded rows of this strip into [out] (raw coordinates) and clears. */
        fun copyLine(colors: IntArray, out: ShortArray, rawWidth: Int, line: Int, x0: Int, width: Int) {
            rotate()
            for (row in 0 until 6) {
                val o = (6 * line + row) * rawWidth + x0
                for (col in 0 until width) {
                    // the CFA phase of a strip column: strips are 768 wide, a multiple of 6 and of 2
                    val c = colors[row * 6 + col % 6]
                    out[o + col] = lines[slot(row, col, c)].toShort()
                }
            }
            clear()
        }

        private fun abs(v: Int) = if (v < 0) -v else v

        companion object {
            const val R0 = 0; const val R1 = 1; const val R2 = 2; const val R3 = 3; const val R4 = 4
            const val G0 = 5; const val G1 = 6; const val G2 = 7; const val G3 = 8; const val G4 = 9
            const val G5 = 10; const val G6 = 11; const val G7 = 12
            const val B0 = 13; const val B1 = 14; const val B2 = 15; const val B3 = 16; const val B4 = 17
            const val L_TOTAL = 18
            private val MTABLE = arrayOf(R0 to R3, R1 to R4, G0 to G6, G1 to G7, B0 to B3, B1 to B4)
            private val ZTABLE = arrayOf(R2 to 3, G2 to 6, B2 to 3)
        }
    }

    /** Reads codes MSB first from bytes [start, end) of one strip, with LibRaw's end-of-data rule. */
    private class Decoder(p: Params, private val buf: ByteArray, private var pos: Int, private val end: Int) : Block(p) {
        private var bit = 0
        var eof = false

        /** LibRaw reads one zero byte past the end, then stops with an error. */
        private fun byte(): Int = when {
            pos < end -> buf[pos].toInt() and 0xff
            pos == end -> 0
            else -> { eof = true; 0 }
        }

        private fun advance() { pos++; if (pos > end) eof = true }

        private fun zeroBits(): Int {
            var count = 0
            while (!eof) {
                val one = (byte() shr (7 - bit)) and 1
                bit = (bit + 1) and 7
                if (bit == 0) advance()
                if (one != 0) break
                count++
            }
            return count
        }

        private fun readBits(n: Int): Int {
            var v = 0
            for (i in 0 until n) {
                v = (v shl 1) or ((byte() shr (7 - bit)) and 1)
                bit = (bit + 1) and 7
                if (bit == 0) advance()
            }
            return v
        }

        override fun nextCode(slot: Int, pred: Int, negative: Boolean, decBits: Int): Int {
            val zeros = zeroBits()
            if (eof) return 0
            return if (zeros < p.maxBits - p.rawBits - 1) (zeros shl decBits) + readBits(decBits) else readBits(p.rawBits) + 1
        }
    }
}
