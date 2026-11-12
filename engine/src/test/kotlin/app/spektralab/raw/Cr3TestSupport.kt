package app.spektralab.raw

import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * Test-only CRX encoder and CR3 container writer.
 *
 * The line coders mirror the decoder's state machines (LibRaw crxDecodeTopLine /
 * crxDecodeLine for "partial" bands, crxDecodeTopLineNoRefPrevLine /
 * crxDecodeLineNoRefPrevLine for the others) and emit exactly the bits that make the
 * decoder reproduce the given values, using run mode where the decoder offers it.
 */
object CrxTestEncoder {
    private val JS = intArrayOf(
        1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 8, 8, 8, 8, 0x10, 0x10, 0x20, 0x20, 0x40, 0x40,
        0x80, 0x80, 0x100, 0x200, 0x400, 0x800, 0x1000, 0x2000, 0x4000, 0x8000)
    private val J = intArrayOf(0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 9, 10, 11, 12, 13, 14, 15)

    class BitOut {
        private val w = MsbBitWriter()
        fun bit(b: Int) = w.put(b, 1)
        fun bits(v: Int, n: Int) = w.put(v, n)
        fun zeros(n: Int) { var r = n; while (r > 0) { val c = minOf(r, 24); w.put(0, c); r -= c } }
        fun bytes(): ByteArray { w.flush(false); return w.bytes() }
    }

    fun zz(v: Int) = if (v >= 0) 2 * v else -2 * v - 1

    fun predictK(prevK: Int, bitCode: Int, maxVal: Int = 0): Int {
        val k = prevK - (if (bitCode < ((1 shl prevK) shr 1)) 1 else 0) +
            (if ((bitCode shr prevK) > 2) 1 else 0) + (if ((bitCode shr prevK) > 5) 1 else 0)
        return if (maxVal == 0 || k < maxVal) k else maxVal
    }

    /** Adaptive Golomb-Rice symbol as read by the decoder's symbol(): escape after [escape] zeros with [escBits] raw bits. */
    fun symbol(o: BitOut, code: Int, k: Int, escape: Int = 41, escBits: Int = 21) {
        require(code >= 0)
        val hi = code ushr k
        if (hi < escape) { o.zeros(hi); o.bit(1); o.bits(code and ((1 shl k) - 1), k) }
        else { require(code < (1 shl escBits)) { "code $code too large" }; o.zeros(escape); o.bit(1); o.bits(code, escBits) }
    }

    /** Base for the line coders: the decoder's buffers and adaptive state. */
    abstract class LineCoder(val width: Int) {
        val d = IntArray(2 * width + 4)
        var l0 = 0; var l1 = 0
        var s = 0; var k = 0
        var curLine = 0

        /** Run of [r] symbols (1 <= r <= length), leading 1 bit included. */
        fun run(o: BitOut, r: Int, length: Int) {
            o.bit(1)
            var n = 1
            while (true) {
                if (n + JS[s] <= r) {
                    o.bit(1); n += JS[s]
                    if (s < 31) ++s
                    if (n == length) return
                } else { o.bit(0); break }
            }
            if (J[s] != 0) o.bits(r - n, J[s]) else check(r == n)
            if (s > 0) --s
        }

        fun line(o: BitOut, v: IntArray) {
            require(v.size >= width)
            val lineLength = width + 2
            if (curLine == 0) { s = 0; k = 0; l0 = 0; l1 = lineLength; top(o, v) }
            else {
                if (curLine and 1 != 0) { l1 = 0; l0 = lineLength } else { l0 = 0; l1 = lineLength }
                rest(o, v)
            }
            curLine++
        }

        abstract fun top(o: BitOut, v: IntArray)
        abstract fun rest(o: BitOut, v: IntArray)
    }

    /** Bands decoded with prediction from the previous line (LL band of a "partial" plane, lossless planes). */
    class PartialCoder(width: Int) : LineCoder(width) {
        override fun top(o: BitOut, v: IntArray) {
            d[l1] = 0
            var length = width
            var x = 0
            while (length > 1) {
                if (d[l1] != 0) d[l1 + 1] = d[l1]
                else {
                    var r = 0
                    while (r < length && v[x + r] == 0) r++
                    if (r > 0) {
                        run(o, r, length)
                        length -= r
                        repeat(r) { d[l1 + 1] = d[l1]; ++l1 }
                        x += r
                        if (length <= 0) break
                    } else o.bit(0)
                    d[l1 + 1] = 0
                }
                val code = zz(v[x] - d[l1 + 1])
                symbol(o, code, k)
                d[l1 + 1] = v[x]
                k = predictK(k, code, 15)
                ++l1; ++x; --length
            }
            if (length == 1) {
                d[l1 + 1] = d[l1]
                val code = zz(v[x] - d[l1 + 1])
                symbol(o, code, k)
                d[l1 + 1] = v[x]
                k = predictK(k, code, 15)
                ++l1
            }
            d[l1 + 1] = d[l1] + 1
        }

        private fun sym(o: BitOut, median: Boolean, notEOL: Boolean, target: Int) {
            if (median) {
                val delta = d[l0 + 1] - d[l0]
                val s2 = d[l1]
                val i = ((if ((d[l0] < d[l1]) xor (delta < 0)) 1 else 0) shl 1) + (if ((d[l1] < d[l0 + 1]) xor (delta < 0)) 1 else 0)
                d[l1 + 1] = when (i) { 0, 1 -> delta + s2; 2 -> s2; else -> d[l0 + 1] }
            } else d[l1 + 1] = d[l0 + 1]
            var code = zz(target - d[l1 + 1])
            symbol(o, code, k)
            d[l1 + 1] = target
            if (notEOL) {
                val nd = (d[l0 + 2] - d[l0 + 1]) shl 1
                code = (code + kotlin.math.abs(nd)) ushr 1
                ++l0
            }
            k = predictK(k, code, 15)
            ++l1
        }

        override fun rest(o: BitOut, v: IntArray) {
            var length = width
            var x = 0
            d[l1] = d[l0 + 1]
            while (length > 1) {
                if (d[l1] != d[l0 + 1] || d[l1] != d[l0 + 2]) { sym(o, true, true, v[x]); x++ }
                else {
                    val rv = d[l1]
                    var r = 0
                    while (r < length && v[x + r] == rv) r++
                    if (r > 0) {
                        run(o, r, length)
                        length -= r
                        l0 += r
                        repeat(r) { d[l1 + 1] = d[l1]; ++l1 }
                        x += r
                    } else o.bit(0)
                    if (length > 0) { sym(o, false, length > 1, v[x]); x++ }
                }
                --length
            }
            if (length == 1) { sym(o, true, false, v[x]); x++ }
            d[l1 + 1] = d[l1] + 1
        }
    }

    /** Bands decoded without the previous line's values (wavelet detail bands, non-partial planes). */
    class NoRefCoder(width: Int) : LineCoder(width) {
        private val n = IntArray(width)

        override fun top(o: BitOut, v: IntArray) {
            var l2 = 0
            d[l0] = 0; d[l1] = 0
            var length = width
            var x = 0
            while (length > 1) {
                if (d[l1] != 0) {
                    val code = zz(v[x]); symbol(o, code, k); d[l1 + 1] = v[x]; k = predictK(k, code, 15)
                } else {
                    var r = 0
                    while (r < length && v[x + r] == 0) r++
                    if (r > 0) run(o, r, length) else o.bit(0)
                    length -= r
                    repeat(r) { n[l2] = 0; d[l1 + 1] = 0; ++l1; ++l2 }
                    x += r
                    if (length <= 0) break
                    val code = zz(v[x]) - 1
                    symbol(o, code, k); d[l1 + 1] = v[x]; k = predictK(k, code, 15)
                }
                n[l2] = k; ++l2; ++l1; ++x; --length
            }
            if (length == 1) {
                val code = zz(v[x]); symbol(o, code, k); d[l1 + 1] = v[x]; k = predictK(k, code, 15); n[l2] = k; ++l1
            }
            d[l1 + 1] = 0
        }

        override fun rest(o: BitOut, v: IntArray) {
            val w = width
            var i = 0
            while (i < w - 1) {
                if ((d[l0 + i + 2] or d[l0 + i + 1] or d[l1 + i]) != 0) {
                    val code = zz(v[i]); symbol(o, code, k); d[l1 + i + 1] = v[i]
                    k = predictK(k, code)
                    if (n[i + 1] - k <= 1) { if (k >= 15) k = 15 } else ++k
                } else {
                    var r = 0
                    while (i + r < w && v[i + r] == 0) r++
                    if (r > 0) run(o, r, w - i) else o.bit(0)
                    if (r > 0) {
                        java.util.Arrays.fill(d, l1 + i + 1, l1 + i + 1 + r, 0)
                        java.util.Arrays.fill(n, i, i + r, 0)
                        i += r
                    }
                    if (i >= w - 1) {
                        if (i == w - 1) {
                            val code = zz(v[i]) - 1; symbol(o, code, k); d[l1 + i + 1] = v[i]; k = predictK(k, code, 15); n[i] = k
                        }
                        i++
                        continue
                    } else {
                        val code = zz(v[i]) - 1; symbol(o, code, k); d[l1 + i + 1] = v[i]
                        k = predictK(k, code)
                        if (n[i + 1] - k <= 1) { if (k >= 15) k = 15 } else ++k
                    }
                }
                n[i] = k
                i++
            }
            if (i == w - 1) {
                val code = zz(v[i]); symbol(o, code, k); d[l1 + i + 1] = v[i]; k = predictK(k, code, 15); n[i] = k
            }
        }
    }

    /**
     * "Rounded" lossless bands (comp header rounded-bits field): no encoder, this replays the
     * decoder's control flow (crxDecodeTopLineRounded / crxDecodeLineRounded) emitting random
     * symbols and never using run mode, so the stream is valid and its decoded values are
     * whatever they are; only decoder-vs-LibRaw agreement is checked with it.
     */
    class RoundedGenerator(width: Int, private val mask: Int, private val rnd: Random) : LineCoder(width) {
        private var roundedBits = 0
        private fun code(o: BitOut): Int { val c = rnd.nextInt(0, 14); symbol(o, c, k); return c }
        private fun unzz(c: Int) = -(c and 1) xor (c ushr 1)

        override fun top(o: BitOut, v: IntArray) {
            roundedBits = 1
            if (mask and 1.inv() != 0) while ((mask shr roundedBits) != 0) ++roundedBits
            d[l1] = 0
            var length = width
            while (length > 1) {
                if (kotlin.math.abs(d[l1]) > mask) d[l1 + 1] = d[l1]
                else { o.bit(0); d[l1 + 1] = 0 }
                val c = code(o); val s = unzz(c)
                d[l1 + 1] += mask * 2 * s + (s shr 31)
                k = predictK(k, c, 15)
                ++l1; --length
            }
            if (length == 1) {
                val c = code(o); val s = unzz(c)
                d[l1 + 1] += mask * 2 * s + (s shr 31)
                k = predictK(k, c, 15)
                ++l1
            }
            d[l1 + 1] = d[l1] + 1
        }

        private fun sym(o: BitOut, doSym: Boolean, doCode: Boolean) {
            var sym = d[l0 + 1]
            if (doSym) {
                val deltaH = d[l0 + 1] - d[l0]
                val s2 = d[l1]
                val i = ((if ((d[l0] < d[l1]) xor (deltaH < 0)) 1 else 0) shl 1) + (if ((d[l1] < d[l0 + 1]) xor (deltaH < 0)) 1 else 0)
                sym = when (i) { 0, 1 -> deltaH + s2; 2 -> s2; else -> d[l0 + 1] }
            }
            val bitCode = code(o)
            var c = unzz(bitCode)
            d[l1 + 1] = mask * 2 * c + (c shr 31) + sym
            if (doCode) {
                c = if (d[l0 + 2] > d[l0 + 1]) (d[l0 + 2] - d[l0 + 1] + mask - 1) shr roundedBits
                else -((d[l0 + 1] - d[l0 + 2] + mask) shr roundedBits)
                k = predictK(k, (bitCode + 2 * kotlin.math.abs(c)) ushr 1, 15)
            } else k = predictK(k, bitCode, 15)
            ++l1
        }

        override fun rest(o: BitOut, v: IntArray) {
            var reached = false
            d[l0] = d[l0 + 1]
            d[l1] = d[l0 + 1]
            var length = width
            while (length > 1) {
                if (kotlin.math.abs(d[l0 + 2] - d[l0 + 1]) > mask) { sym(o, true, true); ++l0; reached = true }
                else if (reached || kotlin.math.abs(d[l0] - d[l1]) > mask) { sym(o, true, true); ++l0; reached = false }
                else {
                    o.bit(0)
                    if (length > 1) { sym(o, false, true); ++l0; reached = kotlin.math.abs(d[l0 + 1] - d[l0]) > mask }
                    else if (length == 1) sym(o, false, false)
                }
                --length
            }
            if (length == 1) sym(o, true, false)
            d[l1 + 1] = d[l1] + 1
        }
    }

    // ------------------------------------------------------------------ QP table (header v0x200)
    private fun prediction(left: Int, top: Int, deltaH: Int, deltaV: Int): Int {
        val i = ((if ((deltaV < 0) xor (deltaH < 0)) 1 else 0) shl 1) + (if ((left < top) xor (deltaH < 0)) 1 else 0)
        return when (i) { 0, 1 -> left + deltaH; 2 -> left; else -> top }
    }

    /** Encodes QP rows (values as stored before the +4) as LibRaw's crxDecodeGolombTop / Normal read them. */
    fun qpTable(rows: Array<IntArray>, qpWidth: Int): ByteArray {
        val o = BitOut()
        val buf = IntArray(2 * (qpWidth + 2))
        var k = 0
        fun emit(qp: Int) = symbol(o, qp, k, escape = 23, escBits = 8)
        for ((row, values) in rows.withIndex()) {
            val line0 = if (row and 1 != 0) qpWidth + 2 else 0
            val line1 = if (row and 1 != 0) 0 else qpWidth + 2
            if (row == 0) {
                var l = line1
                buf[l] = 0
                for (c in 0 until qpWidth) {
                    val qp = zz(values[c] - buf[l]); emit(qp); buf[l + 1] = values[c]; k = predictK(k, qp, 7); ++l
                }
                buf[l + 1] = buf[l] + 1
            } else {
                var l0 = line0; var l1 = line1
                buf[l1] = buf[l0 + 1]
                var deltaH = buf[l0 + 1] - buf[l0]
                var w = qpWidth
                var c = 0
                while (w-- > 0) {
                    val pred = prediction(buf[l1], buf[l0 + 1], deltaH, buf[l0] - buf[l1])
                    val qp = zz(values[c] - pred); emit(qp); buf[l1 + 1] = values[c]
                    if (w != 0) {
                        deltaH = buf[l0 + 2] - buf[l0 + 1]
                        k = predictK(k, (qp + 2 * kotlin.math.abs(deltaH)) ushr 1, 7)
                        ++l0
                    } else k = predictK(k, qp, 7)
                    ++l1; ++c
                }
                buf[l1 + 1] = buf[l1] + 1
            }
        }
        return o.bytes()
    }

    // ------------------------------------------------------------------ frames
    /**
     * A synthetic CRX frame. Planes are given per tile and subband as coefficient lines
     * (`coeffs(tile, plane, band, row)`), the encoder chooses the coder per band.
     */
    class Spec(
        val version: Int, val width: Int, val height: Int, val tileWidth: Int, val tileHeight: Int,
        val nBits: Int, val cfaLayout: Int, val encType: Int, val levels: Int,
        val medianBits: Int = nBits,
        /** comp header "supports partial" flag per plane. */
        val planePartial: BooleanArray = booleanArrayOf(true, true, true, true),
        /** v0x100 subband header partial flag (per-line qParam updates), by band index. */
        val bandQUpdate: (Int) -> Boolean = { false },
        val bandQParam: (Int) -> Int = { 4 },
        /** band empty (no data, decoded as zeros). */
        val bandEmpty: (tile: Int, plane: Int, band: Int) -> Boolean = { _, _, _ -> false },
        val qStepBase: Int = 0, val qStepMult: Int = 8,
        val qp: (tile: Int, row: Int, col: Int) -> Int = { _, _, _ -> 4 },
        val extraSize: Int = 0,
        /** comp header rounded-bits field (lossless, partial planes only): band 0 from [RoundedGenerator]. */
        val roundedBits: Int = 0,
        val coeffs: (tile: Int, plane: Int, band: Int, row: Int, width: Int) -> IntArray,
    )

    class Tile(val width: Int, val height: Int, val flag: Int)

    fun tiles(s: Spec): List<Tile> {
        val pw = s.width / 2; val ph = s.height / 2; val tw = s.tileWidth / 2; val th = s.tileHeight / 2
        val cols = (pw + tw - 1) / tw; val rows = (ph + th - 1) / th
        val out = ArrayList<Tile>()
        for (t in 0 until cols * rows) {
            var flag = 0
            val w: Int; val h: Int
            if ((t + 1) % cols != 0) { w = tw; if (cols > 1) { flag = 1; if (t % cols != 0) flag = flag or 2 } }
            else { w = pw - tw * (cols - 1); if (cols > 1) flag = 2 }
            if (t < cols * rows - cols) { h = th; if (rows > 1) { flag = flag or 4; if (t >= cols) flag = flag or 8 } }
            else { h = ph - th * (rows - 1); if (rows > 1) flag = flag or 8 }
            out += Tile(w, h, flag)
        }
        return out
    }

    private fun be16(o: ByteArrayOutputStream, v: Int) { o.write(v ushr 8 and 0xff); o.write(v and 0xff) }
    private fun be32(o: ByteArrayOutputStream, v: Long) { be16(o, (v ushr 16).toInt() and 0xffff); be16(o, v.toInt() and 0xffff) }

    /** The CRX sample (tile headers + data) and its header size. */
    fun frame(s: Spec): Pair<ByteArray, Int> {
        val hdr = ByteArrayOutputStream()
        val data = ByteArrayOutputStream()
        val v2 = s.version == 0x200
        val nb = 3 * s.levels + 1
        for ((ti, tile) in tiles(s).withIndex()) {
            val sizes = Crx.bandSizes(s.version, s.levels, tile.width, tile.height, tile.flag)
            // QP data first
            val qpBytes = if (v2 && s.levels > 0) {
                val qpW = (tile.width shr 3) + if (tile.width and 7 != 0) 1 else 0
                val qpH = (tile.height shr 1) + (tile.height and 1)
                qpTable(Array(qpH) { r -> IntArray(qpW) { c -> s.qp(ti, r, c) } }, qpW)
            } else ByteArray(0)
            val compHdrs = ByteArrayOutputStream()
            val compData = ByteArrayOutputStream()
            for (p in 0 until 4) {
                val bandHdrs = ByteArrayOutputStream()
                val bandData = ByteArrayOutputStream()
                for (b in 0 until nb) {
                    val (bw, bh) = sizes[b].let { it[0] to it[1] }
                    val empty = s.bandEmpty(ti, p, b)
                    val qUpdate = !v2 && s.bandQUpdate(b)
                    val bytes = if (empty) ByteArray(0) else {
                        val o = BitOut()
                        val coder: LineCoder = when {
                            b == 0 && s.planePartial[p] && s.roundedBits > 0 -> RoundedGenerator(bw, 1 shl (s.roundedBits - 1), Random(ti * 4 + p + 99))
                            b == 0 && s.planePartial[p] -> PartialCoder(bw)
                            else -> NoRefCoder(bw)
                        }
                        for (row in 0 until bh) {
                            if (qUpdate) o.bit(1)                    // qParam delta 0 with k = 0
                            coder.line(o, s.coeffs(ti, p, b, row, bw))
                        }
                        o.bytes()
                    }
                    val pad = (b + p) % 3
                    val size = bytes.size + pad.toLong()
                    if (v2) {
                        be16(bandHdrs, 0xFF13); be16(bandHdrs, 16); be32(bandHdrs, size)
                        be16(bandHdrs, b shl 12); be16(bandHdrs, s.qStepMult); be32(bandHdrs, s.qStepBase.toLong())
                        be16(bandHdrs, pad); be16(bandHdrs, 0)
                    } else {
                        be16(bandHdrs, 0xFF03); be16(bandHdrs, 8); be32(bandHdrs, size)
                        val bits = (b.toLong() shl 28) or (if (qUpdate) 0x8000000L else 0L) or ((s.bandQParam(b).toLong() and 0xff) shl 19) or pad.toLong()
                        be32(bandHdrs, bits)
                    }
                    bandData.write(bytes); repeat(pad) { bandData.write(0x5a) }
                }
                val compBytes = bandData.toByteArray()
                be16(compHdrs, if (v2) 0xFF12 else 0xFF02); be16(compHdrs, 8); be32(compHdrs, compBytes.size.toLong())
                compHdrs.write((p shl 4) or (if (s.planePartial[p]) 8 or (s.roundedBits shl 1) else 0)); compHdrs.write(0); compHdrs.write(0); compHdrs.write(0)
                compHdrs.write(bandHdrs.toByteArray())
                compData.write(compBytes)
            }
            val extra = ByteArray(if (v2 && s.levels > 0) s.extraSize else 0) { 0x33 }
            val tileSize = qpBytes.size + extra.size + compData.size().toLong()
            if (v2 && s.levels > 0) {
                be16(hdr, 0xFF11); be16(hdr, 16); be32(hdr, tileSize); be16(hdr, ti); be16(hdr, 0x4000)
                be32(hdr, qpBytes.size.toLong()); be16(hdr, extra.size); be16(hdr, 0)
            } else {
                be16(hdr, if (v2) 0xFF11 else 0xFF01); be16(hdr, 8); be32(hdr, tileSize); be16(hdr, ti); be16(hdr, 0)
            }
            hdr.write(compHdrs.toByteArray())
            data.write(qpBytes); data.write(extra); data.write(compData.toByteArray())
        }
        val h = hdr.toByteArray()
        return (h + data.toByteArray()) to h.size
    }

    /** CMP1 box content. */
    fun cmp1(s: Spec, mdatHdrSize: Int): ByteArray {
        val b = ByteArray(96)
        fun p16(at: Int, v: Int) { b[at] = (v ushr 8).toByte(); b[at + 1] = v.toByte() }
        fun p32(at: Int, v: Int) { p16(at, v ushr 16); p16(at + 2, v and 0xffff) }
        p16(0, 0xFF10); p16(2, 0x30)
        p16(4, s.version)
        p32(8, s.width); p32(12, s.height); p32(16, s.tileWidth); p32(20, s.tileHeight)
        b[24] = s.nBits.toByte()
        b[25] = ((4 shl 4) or s.cfaLayout).toByte()
        b[26] = ((s.encType shl 4) or s.levels).toByte()
        val tilesAcross = (s.width + s.tileWidth - 1) / s.tileWidth
        val tilesDown = (s.height + s.tileHeight - 1) / s.tileHeight
        b[27] = ((if (tilesAcross > 1) 0x80 else 0) or (if (tilesDown > 1) 0x40 else 0)).toByte()
        p32(28, mdatHdrSize)
        if (s.medianBits != s.nBits) { b[32] = 0x80.toByte(); b[56] = 0x40; b[84] = s.medianBits.toByte() }
        return b
    }
}

/** Writes minimal CR3 files: ftyp, moov (Canon uuid with CMT1 / CMT3, one CRAW track), mdat. */
object Cr3TestBuilder {
    val CANON_UUID = byteArrayOf(
        0x85.toByte(), 0xc0.toByte(), 0xb6.toByte(), 0x87.toByte(), 0x82.toByte(), 0x0f, 0x11, 0xe0.toByte(),
        0x81.toByte(), 0x11, 0xf4.toByte(), 0xce.toByte(), 0x46, 0x2b, 0x6a, 0x48)

    fun box(name: String, vararg parts: ByteArray): ByteArray {
        val size = 8 + parts.sumOf { it.size }
        val o = ByteArrayOutputStream()
        o.write(byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()))
        o.write(name.toByteArray(Charsets.ISO_8859_1))
        parts.forEach { o.write(it) }
        return o.toByteArray()
    }

    private fun u32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun u64(v: Long) = u32(v ushr 32) + u32(v and 0xffffffffL)

    class Meta(
        val model: String = "Canon EOS R6",
        val orientation: Int = 1,
        /** as-shot WB R, G, G, B */
        val wb: IntArray = intArrayOf(2000, 1024, 1024, 1500),
        val black: IntArray = intArrayOf(512, 512, 512, 512),
        val specularWhite: Int = 14000,
        /** sensor info: left, top, right, bottom (inclusive) of the visible area; null = none */
        val area: IntArray? = null,
        /** put the colour data in a CTMD track (as current bodies do) instead of CMT3 */
        val colourInCtmd: Boolean = false,
    )

    fun colourData(m: Meta): ByteArray {
        val cd = IntArray(3656)
        cd[0] = 33
        for (i in 0 until 4) { cd[0x55 + i] = m.wb[i]; cd[0x55 + 0x2d1 + i] = m.black[i] }
        cd[0x55 + 0x2d5] = 12000; cd[0x55 + 0x2d6] = m.specularWhite
        val b = ByteArray(cd.size * 2)
        for (i in cd.indices) { b[2 * i] = cd[i].toByte(); b[2 * i + 1] = (cd[i] shr 8).toByte() }
        return b
    }

    fun build(cmp1: ByteArray, sample: ByteArray, width: Int, height: Int, meta: Meta = Meta()): ByteArray {
        val cmt1 = TiffBuilder().build(TiffBuilder.Ifd()
            .ascii(TiffTags.MAKE, "Canon").ascii(TiffTags.MODEL, meta.model)
            .short(TiffTags.IMAGE_WIDTH, width).short(TiffTags.IMAGE_LENGTH, height)
            .short(TiffTags.ORIENTATION, meta.orientation))
        val note = TiffBuilder.Ifd()
        meta.area?.let { a -> note.short(0x00e0, 34, width, height, 1, 1, a[0], a[1], a[2], a[3], 0, 0, 0, 0, 0, 0, 0, 0) }
        val cdBytes = colourData(meta)
        val cdIfd = TiffBuilder.Ifd().apply { tags[0x4001] = TiffBuilder.Raw(3, cdBytes.size / 2, cdBytes) }
        if (!meta.colourInCtmd) note.tags[0x4001] = TiffBuilder.Raw(3, cdBytes.size / 2, cdBytes)
        note.short(0x0001, 0, 0, 0)
        val cmt3 = TiffBuilder().build(note)

        val ftyp = box("ftyp", "crx ".toByteArray(), u32(1), "crx ".toByteArray(), "isom".toByteArray())
        val ctmdSample = if (meta.colourInCtmd) {
            val mn = TiffBuilder().build(cdIfd)
            val sub = u32le(8L + mn.size) + u32le(0x927c) + mn
            val rec = u32le(12L + sub.size) + byteArrayOf(8, 0) + ByteArray(6) + sub
            u32le(24) + byteArrayOf(1, 0) + ByteArray(18) + rec
        } else null

        fun moov(rawOffset: Long, ctmdOffset: Long): ByteArray {
            val uuid = box("uuid", CANON_UUID, box("CMT1", cmt1), box("CMT3", cmt3))
            val crawHead = ByteArray(82).also { it[24] = (width ushr 8).toByte(); it[25] = width.toByte(); it[26] = (height ushr 8).toByte(); it[27] = height.toByte() }
            val craw = box("CRAW", crawHead, box("CMP1", cmp1))
            val stsd = box("stsd", u32(0), u32(1), craw)
            val stsz = box("stsz", u32(0), u32(sample.size.toLong()), u32(1))
            val co64 = box("co64", u32(0), u32(1), u64(rawOffset))
            val stsc = box("stsc", u32(0), u32(1), u32(1), u32(1), u32(1))
            val hdlr = box("hdlr", u32(0), u32(0), "vide".toByteArray(), ByteArray(13))
            val trak = box("trak", box("mdia", hdlr, box("minf", box("stbl", stsd, box("stts", u32(0), u32(0)), stsc, stsz, co64))))
            val tracks = mutableListOf(trak)
            if (ctmdSample != null) {
                val entry = box("CTMD", ByteArray(8))
                val t = box("trak", box("mdia", box("hdlr", u32(0), u32(0), "meta".toByteArray(), ByteArray(13)),
                    box("minf", box("stbl", box("stsd", u32(0), u32(1), entry), stsc,
                        box("stsz", u32(0), u32(ctmdSample.size.toLong()), u32(1)), box("co64", u32(0), u32(1), u64(ctmdOffset))))))
                tracks += t
            }
            return box("moov", uuid, *tracks.toTypedArray())
        }
        val moovSize = moov(0, 0).size
        val rawOffset = (ftyp.size + moovSize + 8).toLong()
        val ctmdOffset = rawOffset + sample.size
        val mdat = box("mdat", sample, ctmdSample ?: ByteArray(0))
        return ftyp + moov(rawOffset, ctmdOffset) + mdat
    }

    private fun u32le(v: Long) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    fun file(spec: CrxTestEncoder.Spec, meta: Meta = Meta()): ByteArray {
        val (sample, hdrSize) = CrxTestEncoder.frame(spec)
        return build(CrxTestEncoder.cmp1(spec, hdrSize), sample, spec.width, spec.height, meta)
    }
}

/** Deterministic test images / coefficient generators. */
object Cr3TestData {
    /** A raw plane image with gradients, noise and flat patches (for lossless). */
    fun plane(seed: Int, w: Int, h: Int, max: Int): Array<IntArray> {
        val r = Random(seed)
        return Array(h) { y ->
            IntArray(w) { x ->
                when {
                    x in 10..25 && y in 5..15 -> max / 3                               // flat patch: run mode
                    y % 17 == 3 -> 0                                                     // black line
                    x > w - 6 -> max                                                     // clipped edge
                    else -> ((x * 37 + y * 23) % (max / 2) + r.nextInt(0, 64)).coerceIn(0, max)
                }
            }
        }
    }

    /** Random wavelet coefficients: LL smooth-ish with repeats, detail bands sparse. */
    fun coeffs(seed: Int, band: Int, row: Int, width: Int): IntArray {
        val r = Random(seed * 7919 + band * 104729 + row)
        if (band == 0) {
            val v = IntArray(width)
            var prev = r.nextInt(-300, 300)
            for (i in 0 until width) {
                prev = if (r.nextInt(4) == 0) prev else prev + r.nextInt(-40, 41)
                v[i] = prev
            }
            return v
        }
        return IntArray(width) { if (r.nextInt(10) < 6) 0 else r.nextInt(-25, 26) }
    }
}
