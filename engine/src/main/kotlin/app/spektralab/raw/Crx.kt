package app.spektralab.raw

/*
 * Canon CRX codec (the image codec inside CR3 files): lossless (no wavelet levels) and
 * C-RAW (lossy: 1..3 levels of a 5/3 integer wavelet with per-band / per-block
 * quantisation), both header versions (0x100 and 0x200 with QP tables), encoding types
 * 0 (plain RGGB planes) and 3 (planes in a decorrelated colour space).
 *
 * Ported from LibRaw 0.22.2 src/decoders/crx.cpp
 *   Copyright (C) 2018-2019 Alexey Danilchenko, (C) 2019 Alex Tutubalin, LibRaw LLC
 *   (LGPL 2.1 / CDDL 1.0, used here under the LGPL's GPL-compatibility)
 * The port keeps LibRaw's control flow line for line (including its quirks at tile
 * edges) so the output is bit-identical to LibRaw's raw_image. Differences:
 * - the four colour planes of a tile are decoded side by side, row by row, instead of
 *   one plane after another, so encoding type 3 needs no full-frame plane buffer
 *   (planes are independent bit streams, so the result is the same);
 * - every read is bounds-checked; a bit stream that runs dry, an inconsistent header
 *   or a plane without data makes [decode] return false instead of reading garbage.
 *   In particular, with header v0x200 and more than one tile row LibRaw's
 *   getSubbandRow yields a negative QP-table row for tiles with neighbours below, so it
 *   reads heap memory before the table: such frames fail here (no defined output).
 * LibRaw quirks reproduced on purpose: the TOP-neighbour branch of the wavelet start-up
 * reads its L2 line through a pointer left advanced (see [idwtInitialize]); the end-of-tile
 * handling for tiles with a neighbour below is LibRaw's as is.
 */

/** Image header from the CMP1 box of a CR3 track (LibRaw crxParseImageHeader). */
class CrxHeader(
    val version: Int,
    /** Full raw frame (all four planes together). */
    val width: Int, val height: Int,
    val tileWidth: Int, val tileHeight: Int,
    val nBits: Int, val nPlanes: Int, val cfaLayout: Int, val encType: Int, val levels: Int,
    val mdatHdrSize: Int, val medianBits: Int,
) {
    override fun toString() = "CrxHeader(v=0x${version.toString(16)}, ${width}x$height, tile ${tileWidth}x$tileHeight, " +
        "bits=$nBits, planes=$nPlanes, cfa=$cfaLayout, enc=$encType, levels=$levels, hdr=$mdatHdrSize, median=$medianBits)"

    companion object {
        /** Parses CMP1 box content (LibRaw validation rules); null when LibRaw would reject it. */
        fun parse(b: ByteArray, off: Int, size: Int): CrxHeader? {
            if (size < 40 || off < 0 || off.toLong() + size > b.size) return null
            val n = minOf(size, 85)
            fun u8(p: Int) = if (p < n) b[off + p].toInt() and 0xff else 0
            fun u16(p: Int) = (u8(p) shl 8) or u8(p + 1)
            fun u32(p: Int) = ((u16(p).toLong() shl 16) or u16(p + 2).toLong())
            val version = u16(4)
            val fw = u32(8); val fh = u32(12); val tw = u32(16); val th = u32(20)
            val nBits = u8(24)
            val nPlanes = u8(25) shr 4
            val cfa = u8(25) and 15
            val enc = u8(26) shr 4
            val levels = u8(26) and 15
            val hasTileCols = u8(27) shr 7
            val hasTileRows = (u8(27) shr 6) and 1
            val hdrSize = u32(28)
            val ext = u8(32) shr 7
            var median = nBits
            val useMedian = if (ext != 0 && n >= 56 && nPlanes == 4) (u8(56) shr 6) and 1 else 0
            if (useMedian != 0 && n >= 84) median = u8(84)
            if ((version != 0x100 && version != 0x200) || hdrSize == 0L) return null
            if (enc == 1) { if (nBits > 15) return null } else { if (enc != 0 && enc != 3) return null; if (nBits > 14) return null }
            if (nPlanes == 1) { if (cfa != 0 || enc != 0 || nBits != 8) return null }
            else if (nPlanes != 4 || (fw and 1) != 0L || (fh and 1) != 0L || (tw and 1) != 0L || (th and 1) != 0L || cfa > 3 || nBits == 8) return null
            if (tw > fw || th > fh) return null
            if (levels > 3 || hasTileCols > 1 || hasTileRows > 1) return null
            if (fw > 0xFFFF || fh > 0xFFFF || hdrSize > Int.MAX_VALUE) return null
            return CrxHeader(version, fw.toInt(), fh.toInt(), tw.toInt(), th.toInt(), nBits, nPlanes, cfa, enc, levels, hdrSize.toInt(), median)
        }
    }
}

object Crx {
    private const val RIGHT = 1
    private const val LEFT = 2
    private const val BOTTOM = 4
    private const val TOP = 8

    private val EX_COEF = intArrayOf(
        1, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0,
        0, 1, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 1, 0, 1, 0,
        0, 0, 1, 2, 2, 1, 0, 0, 1, 1, 1, 1, 0, 0, 1, 1, 1, 1, 0, 0, 1, 0, 1, 0, 0, 0, 1, 2, 2,
        1, 0, 0, 1, 1, 1, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0, 1, 0, 1, 0, 1, 2, 2, 2, 2, 1, 1, 1,
        1, 2, 2, 1, 1, 1, 1, 2, 2, 1, 1, 0, 1, 1, 1, 1, 1, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1)
    private val Q_STEP = intArrayOf(0x28, 0x2D, 0x33, 0x39, 0x40, 0x48, 0, 0)
    private val JS = intArrayOf(
        1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 8, 8, 8, 8, 0x10, 0x10, 0x20, 0x20, 0x40, 0x40,
        0x80, 0x80, 0x100, 0x200, 0x400, 0x800, 0x1000, 0x2000, 0x4000, 0x8000)
    private val J = intArrayOf(0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 9, 10, 11, 12, 13, 14, 15)

    private class Fail : RuntimeException(null, null, false, false)
    private val FAIL = Fail()

    // ------------------------------------------------------------------ bit stream
    /** MSB-first bit reader over [start, start + size); reads past the end yield zeros and are detected. */
    private class Bits(private val buf: ByteArray, private val start: Int, private val size: Int) {
        private var pos = start
        private val end = start + size
        private var cache = 0L          // valid bits left-aligned
        private var count = 0

        private fun fill() {
            while (count <= 56) {
                val v = if (pos < end) buf[pos].toLong() and 0xff else 0L
                pos++
                cache = cache or (v shl (56 - count))
                count += 8
            }
        }

        /** Bits consumed beyond the data. */
        fun overrun(): Boolean = (pos - start).toLong() * 8 - count > size.toLong() * 8

        fun zeros(): Int {
            var z = 0
            while (true) {
                if (count < 32) fill()
                if (cache == 0L) {
                    z += count; cache = 0; count = 0
                    if (pos - end > 64) throw FAIL
                    continue
                }
                val lz = java.lang.Long.numberOfLeadingZeros(cache)
                z += lz
                cache = cache shl (lz + 1)
                count -= lz + 1
                return z
            }
        }

        fun bits(n: Int): Int {
            if (n == 0) return 0
            if (n < 0 || n > 32) throw FAIL
            if (count < n) fill()
            val v = (cache ushr (64 - n)).toInt()
            cache = cache shl n
            count -= n
            return v
        }
    }

    // ------------------------------------------------------------------ structures
    private class BandParam(
        val bits: Bits, val width: Int, val height: Int, val supportsPartial: Boolean, val roundedBitsMask: Int,
    ) {
        val pd = IntArray(2 * width + 4)
        val np: IntArray? = if (supportsPartial) null else IntArray(width)
        var l0 = 0; var l1 = 0; var l2 = 0
        var roundedBits = 0
        var curLine = 0
        var sParam = 0
        var kParam = 0
    }

    private class Subband {
        var param: BandParam? = null
        var buf = IntArray(0)
        var width = 0
        var height = 0
        var qParam = 4
        var kParam = 0
        var qStepBase = 0
        var qStepMult = 0L
        var supportsPartial = false
        var dataSize = 0L
        var dataOffset = 0L
        var rowStartAddOn = 0; var rowEndAddOn = 0; var colStartAddOn = 0; var colEndAddOn = 0
        var levelShift = 0
    }

    private class Wavelet(val width: Int, val height: Int) {
        var sb0 = IntArray(0); var sb1 = IntArray(0); var sb2 = IntArray(0); var sb3 = IntArray(0)
        // 2 ints of slack: one LibRaw path writes one element past the line (into the next buffer there)
        val lb = Array(8) { IntArray(width + 2) }
        var curLine = 0
        var curH = 0
        var tap = 0
    }

    private class Comp(val bands: Array<Subband>) {
        var dataOffset = 0L
        var compSize = 0L
        var supportsPartial = true
        var roundedBitsMask = 0
        var tileFlag = 0
        var wv: Array<Wavelet> = emptyArray()
    }

    /** Per-level quantisation step tables, all levels in one table as in LibRaw. */
    private class QStep(val table: IntArray, val offsets: IntArray, val width: Int)

    private class Tile(val comps: Array<Comp>) {
        var tileFlag = 0
        var width = 0
        var height = 0
        var dataOffset = 0L
        var tileSize = 0L
        var hasQPData = false
        var qpDataSize = 0L
        var extraSize = 0
        var qStep: QStep? = null
    }

    private class Img(
        val bytes: ByteArray, val hdr: CrxHeader, val planeWidth: Int, val planeHeight: Int,
        val tileW: Int, val tileH: Int, val tileCols: Int, val tileRows: Int, val levels: Int, val mdatOffset: Long,
    ) {
        val subbandCount = 3 * levels + 1
        val nPlanes = hdr.nPlanes
        lateinit var tiles: Array<Tile>
    }

    // ------------------------------------------------------------------ entry point
    /**
     * Decodes the CRX frame stored at [sampleOffset] (its [sampleSize] bytes, starting with
     * the [CrxHeader.mdatHdrSize]-byte tile header) into [out], a raw frame of
     * [CrxHeader.width] x [CrxHeader.height] samples. Only 4-plane Bayer data with encoding
     * type 0 or 3 is supported. Returns false on any inconsistency.
     */
    fun decode(bytes: ByteArray, hdr: CrxHeader, sampleOffset: Long, sampleSize: Long, out: ShortArray): Boolean = try {
        decodeInternal(bytes, hdr, sampleOffset, sampleSize, out)
    } catch (e: Fail) { false } catch (e: IndexOutOfBoundsException) { false } catch (e: ArithmeticException) { false }

    private fun decodeInternal(bytes: ByteArray, hdr: CrxHeader, sampleOffset: Long, sampleSize: Long, out: ShortArray): Boolean {
        if (hdr.nPlanes != 4 || (hdr.encType != 0 && hdr.encType != 3)) return false
        if (out.size.toLong() < hdr.width.toLong() * hdr.height) return false
        if (sampleOffset < 0 || sampleSize < hdr.mdatHdrSize || sampleOffset + hdr.mdatHdrSize > bytes.size) return false
        val pw = hdr.width shr 1; val ph = hdr.height shr 1
        val tw = hdr.tileWidth shr 1; val th = hdr.tileHeight shr 1
        if (tw < 0x16 || th < 0x16 || pw > 0x7FFF || ph > 0x7FFF) return false
        val tileCols = (pw + tw - 1) / tw
        val tileRows = (ph + th - 1) / th
        if (tileCols > 0xFF || tileRows > 0xFF || pw - tw * (tileCols - 1) < 0x16 || ph - th * (tileRows - 1) < 0x16) return false
        if (hdr.encType == 3 && (hdr.medianBits !in 1..16)) return false
        val img = Img(bytes, hdr, pw, ph, tw, th, tileCols, tileRows, hdr.levels, sampleOffset + hdr.mdatHdrSize)
        if (!readImageHeaders(img, sampleOffset.toInt(), hdr.mdatHdrSize)) return false

        // output position of each plane inside a 2x2 cell (LibRaw crxSetupImageData)
        val rawW = 2 * pw
        val planeOff = when (hdr.cfaLayout) {
            0 -> intArrayOf(0, 1, rawW, rawW + 1)
            1 -> intArrayOf(1, 0, rawW + 1, rawW)
            2 -> intArrayOf(rawW, rawW + 1, 0, 1)
            else -> intArrayOf(rawW + 1, rawW, 1, 0)
        }
        val lines = arrayOfNulls<IntArray>(4)
        var imageRow = 0
        for (tRow in 0 until img.tileRows) {
            var imageCol = 0
            for (tCol in 0 until img.tileCols) {
                val tile = img.tiles[tRow * img.tileCols + tCol]
                for (p in 0 until 4) {
                    val comp = tile.comps[p]
                    val tileMdat = tile.dataOffset + tile.qpDataSize + tile.extraSize + comp.dataOffset
                    setupSubbandData(img, comp, tile, tileMdat)
                    if (img.levels > 0) { if (!idwtInitialize(comp, img.levels, tile.qStep)) return false }
                    else if (comp.bands[0].dataSize == 0L) return false        // LibRaw leaves such a plane undecoded
                }
                for (i in 0 until tile.height) {
                    for (p in 0 until 4) {
                        val comp = tile.comps[p]
                        lines[p] = if (img.levels > 0) {
                            if (!idwtDecode(comp, img.levels - 1, tile.qStep) || !idwtTransform(comp, img.levels - 1)) return false
                            idwtGetLine(comp, img.levels - 1)
                        } else {
                            val band = comp.bands[0]
                            if (!decodeLine(band.param!!, band.buf)) return false
                            band.buf
                        }
                    }
                    for (p in 0 until 4) if (lines[p]!!.size < tile.width) return false
                    val row = imageRow + i
                    val base = 4 * pw * row + 2 * imageCol
                    if (hdr.encType == 0) {
                        val median = 1 shl (hdr.nBits - 1)
                        val maxVal = (1 shl hdr.nBits) - 1
                        for (p in 0 until 4) {
                            val line = lines[p]!!
                            val o = base + planeOff[p]
                            for (c in 0 until tile.width) out[o + 2 * c] = (median + line[c]).coerceIn(0, maxVal).toShort()
                        }
                    } else {
                        convertEnc3(lines, tile.width, out, base, planeOff, hdr.medianBits)
                    }
                    if (tile.comps.any { c -> c.bands.any { it.param?.bits?.overrun() == true } }) return false
                }
                imageCol += tile.width
            }
            imageRow += img.tiles[tRow * img.tileCols].height
        }
        return true
    }

    /** Encoding type 3: planes are (P0, P1, P2, P3) in a luma / chroma-like space (LibRaw crxConvertPlaneLine). */
    private fun convertEnc3(lines: Array<IntArray?>, width: Int, out: ShortArray, base: Int, planeOff: IntArray, medianBits: Int) {
        val p0 = lines[0]!!; val p1 = lines[1]!!; val p2 = lines[2]!!; val p3 = lines[3]!!
        val median = (1 shl (medianBits - 1)) shl 10
        val maxVal = (1 shl medianBits) - 1
        for (i in 0 until width) {
            // the intermediate plane buffer in LibRaw is int16
            val a = p0[i].toShort().toInt(); val b = p1[i].toShort().toInt(); val c = p2[i].toShort().toInt(); val d = p3[i].toShort().toInt()
            var gr = median + (a shl 10) - 168 * b - 585 * d
            gr = if (gr < 0) -(((-gr + 512) shr 9) and 1.inv()) else ((gr + 512) shr 9) and 1.inv()
            val o = base + 2 * i
            out[o + planeOff[0]] = ((median + (a shl 10) + 1510 * d + 512) shr 10).coerceIn(0, maxVal).toShort()
            out[o + planeOff[1]] = ((c + gr + 1) shr 1).coerceIn(0, maxVal).toShort()
            out[o + planeOff[2]] = ((gr - c + 1) shr 1).coerceIn(0, maxVal).toShort()
            out[o + planeOff[3]] = ((median + (a shl 10) + 1927 * b + 512) shr 10).coerceIn(0, maxVal).toShort()
        }
    }

    // ------------------------------------------------------------------ headers
    private fun be16(b: ByteArray, p: Int) = ((b[p].toInt() and 0xff) shl 8) or (b[p + 1].toInt() and 0xff)
    private fun be32(b: ByteArray, p: Int) = (be16(b, p).toLong() shl 16) or be16(b, p + 2).toLong()

    private fun readImageHeaders(img: Img, hdrStart: Int, hdrSize: Int): Boolean {
        val b = img.bytes
        val nTiles = img.tileRows * img.tileCols
        val hdr = img.hdr
        img.tiles = Array(nTiles) { curTile ->
            val tile = Tile(Array(img.nPlanes) { Comp(Array(img.subbandCount) { Subband() }) })
            if ((curTile + 1) % img.tileCols != 0) {
                tile.width = img.tileW
                if (img.tileCols > 1) {
                    tile.tileFlag = RIGHT
                    if (curTile % img.tileCols != 0) tile.tileFlag = tile.tileFlag or LEFT
                }
            } else {
                tile.width = img.planeWidth - img.tileW * (img.tileCols - 1)
                if (img.tileCols > 1) tile.tileFlag = LEFT
            }
            if (curTile < nTiles - img.tileCols) {
                tile.height = img.tileH
                if (img.tileRows > 1) {
                    tile.tileFlag = tile.tileFlag or BOTTOM
                    if (curTile >= img.tileCols) tile.tileFlag = tile.tileFlag or TOP
                }
            } else {
                tile.height = img.planeHeight - img.tileH * (img.tileRows - 1)
                if (img.tileRows > 1) tile.tileFlag = tile.tileFlag or TOP
            }
            for (c in tile.comps) c.tileFlag = tile.tileFlag
            tile
        }

        var tileOffset = 0L
        var dataSize = hdrSize
        var p = hdrStart
        val hdrEnd = hdrStart + hdrSize
        if (hdrEnd > b.size) return false
        for (curTile in 0 until nTiles) {
            val tile = img.tiles[curTile]
            if (dataSize < 4) return false
            var sign = be16(b, p)
            var size = be16(b, p + 2)
            if ((sign != 0xFF01 || size != 8) && (sign != 0xFF11 || (size != 8 && size != 16))) return false
            if (dataSize < size + 4) return false
            val tail = be16(b, p + 10)
            if ((size == 8 && tail != 0) || (size == 16 && tail != 0x4000)) return false
            if (be16(b, p + 8) != curTile) return false
            dataSize -= size + 4
            tile.tileSize = be32(b, p + 4)
            tile.dataOffset = tileOffset
            if (size == 16) {
                if (be16(b, p + 18) != 0) return false
                tile.hasQPData = true
                tile.qpDataSize = be32(b, p + 12)
                tile.extraSize = be16(b, p + 16)
            }
            p += size + 4
            tileOffset += tile.tileSize

            var compOffset = 0L
            for (compNum in 0 until img.nPlanes) {
                val comp = tile.comps[compNum]
                if (dataSize < 0xC) return false
                sign = be16(b, p); size = be16(b, p + 2)
                if ((sign != 0xFF02 && sign != 0xFF12) || size != 8) return false
                if (compNum != (b[p + 8].toInt() and 0xff) shr 4) return false
                if (b[p + 9].toInt() != 0 || b[p + 10].toInt() != 0 || b[p + 11].toInt() != 0) return false
                comp.compSize = be32(b, p + 4)
                val roundedBits = ((b[p + 8].toInt() and 0xff) shr 1) and 3
                comp.supportsPartial = (b[p + 8].toInt() and 8) != 0
                comp.dataOffset = compOffset
                compOffset += comp.compSize
                dataSize -= 0xC
                p += 0xC
                comp.roundedBitsMask = 0
                if (roundedBits != 0) {
                    if (img.levels != 0 || !comp.supportsPartial) return false
                    comp.roundedBitsMask = 1 shl (roundedBits - 1)
                }
                // subband headers
                var subbandOffset = 0L
                for (cur in 0 until img.subbandCount) {
                    val band = comp.bands[cur]
                    if (dataSize < 4) return false
                    val hs = be16(b, p); val hz = be16(b, p + 2)
                    if (dataSize < hz + 4) return false
                    if ((hs != 0xFF03 || hz != 8) && (hs != 0xFF13 || hz != 16)) return false
                    val subbandSize = be32(b, p + 4)
                    if (cur != (b[p + 8].toInt() and 0xF0) shr 4) return false
                    band.dataOffset = subbandOffset
                    band.kParam = 0
                    if (hs == 0xFF03) {
                        val bitData = be32(b, p + 8)
                        band.dataSize = subbandSize - (bitData and 0x7FFFF)
                        band.supportsPartial = (bitData and 0x8000000L) != 0L
                        band.qParam = ((bitData shr 19) and 0xFF).toInt()
                        band.qStepBase = 0
                        band.qStepMult = 0
                    } else {
                        if (be16(b, p + 8) and 0xFFF != 0) return false
                        if (be16(b, p + 18) != 0) return false
                        band.supportsPartial = false
                        band.qParam = 0
                        band.dataSize = subbandSize - be16(b, p + 16)
                        band.qStepBase = be32(b, p + 12).toInt()
                        band.qStepMult = be16(b, p + 10).toLong()
                    }
                    if (band.dataSize < 0) return false
                    subbandOffset += subbandSize
                    p += hz + 4
                    dataSize -= hz + 4
                }
                processSubbands(img, tile, comp)
            }
        }
        if (hdr.version != 0x200) return true

        for (tile in img.tiles) {
            if (!tile.hasQPData) continue
            val start = img.mdatOffset + tile.dataOffset
            if (start < 0 || start + tile.qpDataSize > b.size || tile.qpDataSize > Int.MAX_VALUE) return false
            val bits = Bits(b, start.toInt(), tile.qpDataSize.toInt())
            val qpWidth = (tile.width shr 3) + if (tile.width and 7 != 0) 1 else 0
            val qpHeight = (tile.height shr 1) + (tile.height and 1)
            val totalQP = qpHeight * qpWidth
            val qpTable = IntArray(totalQP)
            val lineBuf = IntArray(2 * (qpWidth + 2))
            val k = IntArray(1)
            var cur = 0
            for (qpRow in 0 until qpHeight) {
                val line0 = if (qpRow and 1 != 0) qpWidth + 2 else 0
                val line1 = if (qpRow and 1 != 0) 0 else qpWidth + 2
                if (qpRow != 0) golombNormal(bits, qpWidth, lineBuf, line0, line1, k)
                else golombTop(bits, qpWidth, lineBuf, line1, k)
                for (qpCol in 0 until qpWidth) qpTable[cur++] = lineBuf[line1 + qpCol + 1] + 4
            }
            if (bits.overrun()) return false
            tile.qStep = makeQStep(img, tile, qpTable) ?: return false
        }
        return true
    }

    private fun processSubbands(img: Img, tile: Tile, comp: Comp) {
        val bands = comp.bands
        var bi = img.subbandCount - 1                     // last band
        var bandHeight = tile.height
        var bandWidth = tile.width
        var bandWidthExCoef = 0
        var bandHeightExCoef = 0
        val v2 = img.hdr.version == 0x200
        fun idx(band: Subband, level: Int, colStart: Int, widthEx: Int, rowStart: Int, heightEx: Int) {
            if (v2) {
                band.rowStartAddOn = rowStart; band.rowEndAddOn = heightEx
                band.colStartAddOn = colStart; band.colEndAddOn = widthEx
                band.levelShift = 3 - level
            } else {
                band.rowStartAddOn = 0; band.rowEndAddOn = 0; band.colStartAddOn = 0; band.colEndAddOn = 0; band.levelShift = 0
            }
        }
        if (img.levels > 0) {
            val rowEx = 0x30 * (img.levels - 1) + 6 * (tile.width and 7)
            val colEx = 0x30 * (img.levels - 1) + 6 * (tile.height and 7)
            for (level in 0 until img.levels) {
                val widthOdd = bandWidth and 1
                val heightOdd = bandHeight and 1
                bandWidth = (widthOdd + bandWidth) shr 1
                bandHeight = (heightOdd + bandHeight) shr 1
                var wEx0 = 0; var wEx1 = 0; var hEx0 = 0; var hEx1 = 0
                var colStart = 0; var rowStart = 0
                if (tile.tileFlag and RIGHT != 0) { wEx0 = EX_COEF[rowEx + 2 * level]; wEx1 = EX_COEF[rowEx + 2 * level + 1] }
                if (tile.tileFlag and LEFT != 0) { ++wEx0; colStart = 1 }
                if (tile.tileFlag and BOTTOM != 0) { hEx0 = EX_COEF[colEx + 2 * level]; hEx1 = EX_COEF[colEx + 2 * level + 1] }
                if (tile.tileFlag and TOP != 0) { ++hEx0; rowStart = 1 }
                val b0 = bands[bi]; val b1 = bands[bi - 1]; val b2 = bands[bi - 2]
                b0.width = bandWidth + wEx0 - widthOdd
                b0.height = bandHeight + hEx0 - heightOdd
                idx(b0, level + 1, colStart, wEx0 - colStart, rowStart, hEx0 - rowStart)
                b1.width = bandWidth + wEx1
                b1.height = bandHeight + hEx0 - heightOdd
                idx(b1, level + 1, 0, wEx1, rowStart, hEx0 - rowStart)
                b2.width = bandWidth + wEx0 - widthOdd
                b2.height = bandHeight + hEx1
                idx(b2, level + 1, colStart, wEx0 - colStart, 0, hEx1)
                bi -= 3
            }
            if (tile.tileFlag and RIGHT != 0) bandWidthExCoef = EX_COEF[rowEx + 2 * img.levels - 1]
            if (tile.tileFlag and BOTTOM != 0) bandHeightExCoef = EX_COEF[colEx + 2 * img.levels - 1]
        }
        val band = bands[bi]
        band.width = bandWidthExCoef + bandWidth
        band.height = bandHeightExCoef + bandHeight
        if (img.levels > 0) idx(band, img.levels, 0, bandWidthExCoef, 0, bandHeightExCoef)
    }

    /**
     * Subband sizes (width, height per band, LL first) of one plane of a tile, for test
     * encoders. [tileFlag] bits: 1 tile on the right, 2 left, 4 below, 8 above.
     */
    internal fun bandSizes(version: Int, levels: Int, tileWidth: Int, tileHeight: Int, tileFlag: Int): Array<IntArray> {
        val hdr = CrxHeader(version, 2 * tileWidth, 2 * tileHeight, 2 * tileWidth, 2 * tileHeight, 14, 4, 0, 0, levels, 1, 14)
        val img = Img(ByteArray(0), hdr, tileWidth, tileHeight, tileWidth, tileHeight, 1, 1, levels, 0)
        val tile = Tile(arrayOf(Comp(Array(img.subbandCount) { Subband() })))
        tile.width = tileWidth; tile.height = tileHeight; tile.tileFlag = tileFlag
        processSubbands(img, tile, tile.comps[0])
        return Array(img.subbandCount) { intArrayOf(tile.comps[0].bands[it].width, tile.comps[0].bands[it].height) }
    }

    private fun setupSubbandData(img: Img, comp: Comp, tile: Tile, tileMdat: Long) {
        val bands = comp.bands
        for (b in bands) { if (b.width < 0 || b.height < 0) throw FAIL; b.buf = IntArray(b.width); b.param = null }
        if (img.levels > 0) {
            comp.wv = Array(img.levels) { level ->
                val band = 3 * level + 1
                val w = if (level >= img.levels - 1) Wavelet(tile.width, tile.height)
                else Wavelet(bands[band + 4].width, bands[band + 3].height)
                w.sb1 = bands[band].buf; w.sb2 = bands[band + 1].buf; w.sb3 = bands[band + 2].buf
                w
            }
            comp.wv[0].sb0 = bands[0].buf
        }
        val base = img.mdatOffset + tileMdat
        for ((n, b) in bands.withIndex()) {
            if (b.dataSize == 0L) continue
            val start = base + b.dataOffset
            if (start < 0 || start + b.dataSize > img.bytes.size) throw FAIL
            val partial = comp.supportsPartial && n == 0
            b.param = BandParam(Bits(img.bytes, start.toInt(), b.dataSize.toInt()), b.width, b.height, partial,
                if (partial) comp.roundedBitsMask else 0)
        }
    }

    // ------------------------------------------------------------------ QP tables (header version 0x200)
    private fun readQP(bits: Bits, k: Int): Int {
        var qp = bits.zeros()
        if (qp >= 23) qp = bits.bits(8) else if (k != 0) qp = bits.bits(k) or (qp shl k)
        return qp
    }

    private fun golombTop(bits: Bits, width: Int, buf: IntArray, line: Int, k: IntArray) {
        var l = line
        buf[l] = 0
        repeat(width) {
            buf[l + 1] = buf[l]
            val qp = readQP(bits, k[0])
            buf[l + 1] += -(qp and 1) xor (qp ushr 1)
            k[0] = predictK(k[0], qp, 7)
            ++l
        }
        buf[l + 1] = buf[l] + 1
    }

    private fun golombNormal(bits: Bits, width: Int, buf: IntArray, line0: Int, line1: Int, k: IntArray) {
        var l0 = line0; var l1 = line1
        buf[l1] = buf[l0 + 1]
        var deltaH = buf[l0 + 1] - buf[l0]
        var w = width
        while (w-- > 0) {
            buf[l1 + 1] = prediction(buf[l1], buf[l0 + 1], deltaH, buf[l0] - buf[l1])
            val qp = readQP(bits, k[0])
            buf[l1 + 1] += -(qp and 1) xor (qp ushr 1)
            if (w != 0) {
                deltaH = buf[l0 + 2] - buf[l0 + 1]
                k[0] = predictK(k[0], (qp + 2 * abs(deltaH)) ushr 1, 7)
                ++l0
            } else k[0] = predictK(k[0], qp, 7)
            ++l1
        }
        buf[l1 + 1] = buf[l1] + 1
    }

    private fun qStepValue(q: Int): Int =
        if (q / 6 >= 6) Q_STEP[q % 6] shl ((q / 6 - 6) and 0x1f) else Q_STEP[q % 6] ushr (6 - q / 6)

    private fun makeQStep(img: Img, tile: Tile, qp: IntArray): QStep? {
        if (img.levels > 3 || img.levels < 1) return null
        val qpWidth = (tile.width shr 3) + if (tile.width and 7 != 0) 1 else 0
        val qpHeight = (tile.height shr 1) + (tile.height and 1)
        val qpHeight4 = (tile.height shr 2) + if (tile.height and 3 != 0) 1 else 0
        val qpHeight8 = (tile.height shr 3) + if (tile.height and 7 != 0) 1 else 0
        var total = qpHeight
        if (img.levels > 1) total += qpHeight4
        if (img.levels > 2) total += qpHeight8
        val table = IntArray(total * qpWidth)
        val offsets = IntArray(img.levels)
        var t = 0
        var level = 0
        if (img.levels == 3) {
            offsets[level++] = t
            for (qpRow in 0 until qpHeight8) {
                var r0 = qpWidth * minOf(4 * qpRow, qpHeight - 1)
                var r1 = qpWidth * minOf(4 * qpRow + 1, qpHeight - 1)
                var r2 = qpWidth * minOf(4 * qpRow + 2, qpHeight - 1)
                var r3 = qpWidth * minOf(4 * qpRow + 3, qpHeight - 1)
                for (qpCol in 0 until qpWidth) {
                    var q = qp[r0++] + qp[r1++] + qp[r2++] + qp[r3++]
                    q = ((if (q < 0) 3 else 0) + q) shr 2
                    table[t++] = qStepValue(q)
                }
            }
        }
        if (img.levels >= 2) {
            offsets[level++] = t
            for (qpRow in 0 until qpHeight4) {
                var r0 = qpWidth * minOf(2 * qpRow, qpHeight - 1)
                var r1 = qpWidth * minOf(2 * qpRow + 1, qpHeight - 1)
                for (qpCol in 0 until qpWidth) table[t++] = qStepValue((qp[r0++] + qp[r1++]) / 2)
            }
        }
        offsets[level] = t
        for (i in 0 until qpHeight * qpWidth) table[t++] = qStepValue(qp[i])
        return QStep(table, offsets, qpWidth)
    }

    // ------------------------------------------------------------------ entropy decoding of band lines
    private fun abs(x: Int) = if (x < 0) -x else x

    private fun predictK(prevK: Int, bitCode: Int, maxVal: Int = 0): Int {
        val k = prevK - (if (bitCode < ((1 shl prevK) shr 1)) 1 else 0) +
            (if ((bitCode shr prevK) > 2) 1 else 0) + (if ((bitCode shr prevK) > 5) 1 else 0)
        return if (maxVal == 0 || k < maxVal) k else maxVal
    }

    private fun prediction(left: Int, top: Int, deltaH: Int, deltaV: Int): Int {
        val i = ((if ((deltaV < 0) xor (deltaH < 0)) 1 else 0) shl 1) + (if ((left < top) xor (deltaH < 0)) 1 else 0)
        return when (i) { 0, 1 -> left + deltaH; 2 -> left; else -> top }
    }

    private fun symbol(p: BandParam): Int {
        val bits = p.bits
        var code = bits.zeros()
        if (code >= 41) code = bits.bits(21) else if (p.kParam != 0) code = bits.bits(p.kParam) or (code shl p.kParam)
        return code
    }

    /** Run length of repeated / zero symbols (shared by all line decoders); -1 on error. */
    private fun runLength(p: BandParam, length: Int, checkAfter: Boolean): Int {
        val bits = p.bits
        var nSyms = 1
        while (bits.bits(1) != 0) {
            nSyms += JS[p.sParam]
            if (nSyms > length) { nSyms = length; break }
            if (p.sParam < 31) ++p.sParam
            if (nSyms == length) break
        }
        if (nSyms < length) {
            if (J[p.sParam] != 0) nSyms += bits.bits(J[p.sParam])
            if (p.sParam > 0) --p.sParam
            if (checkAfter && nSyms > length) return -1
        }
        return nSyms
    }

    private fun symbolL1(p: BandParam, median: Boolean, notEOL: Boolean) {
        val d = p.pd
        if (median) {
            val delta = d[p.l0 + 1] - d[p.l0]
            val s2 = d[p.l1]
            val i = ((if ((d[p.l0] < d[p.l1]) xor (delta < 0)) 1 else 0) shl 1) + (if ((d[p.l1] < d[p.l0 + 1]) xor (delta < 0)) 1 else 0)
            d[p.l1 + 1] = when (i) { 0, 1 -> delta + s2; 2 -> s2; else -> d[p.l0 + 1] }
        } else d[p.l1 + 1] = d[p.l0 + 1]
        var code = symbol(p)
        d[p.l1 + 1] += -(code and 1) xor (code ushr 1)
        if (notEOL) {
            val nextDelta = (d[p.l0 + 2] - d[p.l0 + 1]) shl 1
            code = (code + abs(nextDelta)) ushr 1
            ++p.l0
        }
        p.kParam = predictK(p.kParam, code, 15)
        ++p.l1
    }

    private fun lineL1(p: BandParam): Boolean {
        val d = p.pd
        var length = p.width
        d[p.l1] = d[p.l0 + 1]
        while (length > 1) {
            if (d[p.l1] != d[p.l0 + 1] || d[p.l1] != d[p.l0 + 2]) symbolL1(p, true, true)
            else {
                if (p.bits.bits(1) != 0) {
                    var nSyms = runLength(p, length, true)
                    if (nSyms < 0) return false
                    length -= nSyms
                    p.l0 += nSyms
                    while (nSyms-- > 0) { d[p.l1 + 1] = d[p.l1]; ++p.l1 }
                }
                if (length > 0) symbolL1(p, false, length > 1)
            }
            --length
        }
        if (length == 1) symbolL1(p, true, false)
        d[p.l1 + 1] = d[p.l1] + 1
        return true
    }

    private fun symbolL1Rounded(p: BandParam, doSym: Boolean = true, doCode: Boolean = true) {
        val d = p.pd
        var sym = d[p.l0 + 1]
        if (doSym) {
            val deltaH = d[p.l0 + 1] - d[p.l0]
            val s2 = d[p.l1]
            val i = ((if ((d[p.l0] < d[p.l1]) xor (deltaH < 0)) 1 else 0) shl 1) + (if ((d[p.l1] < d[p.l0 + 1]) xor (deltaH < 0)) 1 else 0)
            sym = when (i) { 0, 1 -> deltaH + s2; 2 -> s2; else -> d[p.l0 + 1] }
        }
        val bitCode = symbol(p)
        var code = -(bitCode and 1) xor (bitCode ushr 1)
        d[p.l1 + 1] = p.roundedBitsMask * 2 * code + (code shr 31) + sym
        if (doCode) {
            code = if (d[p.l0 + 2] > d[p.l0 + 1]) (d[p.l0 + 2] - d[p.l0 + 1] + p.roundedBitsMask - 1) shr p.roundedBits
            else -((d[p.l0 + 1] - d[p.l0 + 2] + p.roundedBitsMask) shr p.roundedBits)
            p.kParam = predictK(p.kParam, (bitCode + 2 * abs(code)) ushr 1, 15)
        } else p.kParam = predictK(p.kParam, bitCode, 15)
        ++p.l1
    }

    private fun lineRounded(p: BandParam): Boolean {
        val d = p.pd
        var valueReached = false
        d[p.l0] = d[p.l0 + 1]
        d[p.l1] = d[p.l0 + 1]
        var length = p.width
        while (length > 1) {
            if (abs(d[p.l0 + 2] - d[p.l0 + 1]) > p.roundedBitsMask) {
                symbolL1Rounded(p); ++p.l0; valueReached = true
            } else if (valueReached || abs(d[p.l0] - d[p.l1]) > p.roundedBitsMask) {
                symbolL1Rounded(p); ++p.l0; valueReached = false
            } else {
                var nSyms = 0
                if (p.bits.bits(1) != 0) {
                    nSyms = runLength(p, length, false)
                    if (nSyms > length) return false
                }
                length -= nSyms
                p.l0 += nSyms
                while (nSyms-- > 0) { d[p.l1 + 1] = d[p.l1]; ++p.l1 }
                if (length > 1) {
                    symbolL1Rounded(p, false)
                    ++p.l0
                    valueReached = abs(d[p.l0 + 1] - d[p.l0]) > p.roundedBitsMask
                } else if (length == 1) symbolL1Rounded(p, false, false)
            }
            --length
        }
        if (length == 1) symbolL1Rounded(p, true, false)
        d[p.l1 + 1] = d[p.l1] + 1
        return true
    }

    private fun lineNoRef(p: BandParam): Boolean {
        val d = p.pd
        val n = p.np!!
        val l0 = p.l0; val l1 = p.l1
        val w = p.width
        var i = 0
        while (i < w - 1) {
            if ((d[l0 + i + 2] or d[l0 + i + 1] or d[l1 + i]) != 0) {
                val code = symbol(p)
                d[l1 + i + 1] = -(code and 1) xor (code ushr 1)
                p.kParam = predictK(p.kParam, code)
                if (n[i + 1] - p.kParam <= 1) { if (p.kParam >= 15) p.kParam = 15 } else ++p.kParam
            } else {
                var nSyms = 0
                if (p.bits.bits(1) != 0) {
                    nSyms = 1
                    if (i != w - 1) {
                        while (p.bits.bits(1) != 0) {
                            nSyms += JS[p.sParam]
                            if (i + nSyms > w) { nSyms = w - i; break }
                            if (p.sParam < 31) ++p.sParam
                            if (i + nSyms == w) break
                        }
                        if (i + nSyms < w) {
                            if (J[p.sParam] != 0) nSyms += p.bits.bits(J[p.sParam])
                            if (p.sParam > 0) --p.sParam
                        }
                        if (i + nSyms > w) return false
                    }
                } else if (i > w) return false
                if (nSyms > 0) {
                    java.util.Arrays.fill(d, l1 + i + 1, l1 + i + 1 + nSyms, 0)
                    java.util.Arrays.fill(n, i, i + nSyms, 0)
                    i += nSyms
                }
                if (i >= w - 1) {
                    if (i == w - 1) {
                        val code = symbol(p)
                        d[l1 + i + 1] = -((code + 1) and 1) xor ((code + 1) ushr 1)
                        p.kParam = predictK(p.kParam, code, 15)
                        n[i] = p.kParam
                    }
                    i++                                  // the C loop's `continue` still runs i++
                    continue
                } else {
                    val code = symbol(p)
                    d[l1 + i + 1] = -((code + 1) and 1) xor ((code + 1) ushr 1)
                    p.kParam = predictK(p.kParam, code)
                    if (n[i + 1] - p.kParam <= 1) { if (p.kParam >= 15) p.kParam = 15 } else ++p.kParam
                }
            }
            n[i] = p.kParam
            i++
        }
        if (i == w - 1) {
            val code = symbol(p)
            d[l1 + i + 1] = -(code and 1) xor (code ushr 1)
            p.kParam = predictK(p.kParam, code, 15)
            n[i] = p.kParam
        }
        return true
    }

    private fun topLine(p: BandParam): Boolean {
        val d = p.pd
        d[p.l1] = 0
        var length = p.width
        while (length > 1) {
            if (d[p.l1] != 0) d[p.l1 + 1] = d[p.l1]
            else {
                if (p.bits.bits(1) != 0) {
                    var nSyms = runLength(p, length, true)
                    if (nSyms < 0) return false
                    length -= nSyms
                    while (nSyms-- > 0) { d[p.l1 + 1] = d[p.l1]; ++p.l1 }
                    if (length <= 0) break
                }
                d[p.l1 + 1] = 0
            }
            val code = symbol(p)
            d[p.l1 + 1] += -(code and 1) xor (code ushr 1)
            p.kParam = predictK(p.kParam, code, 15)
            ++p.l1
            --length
        }
        if (length == 1) {
            d[p.l1 + 1] = d[p.l1]
            val code = symbol(p)
            d[p.l1 + 1] += -(code and 1) xor (code ushr 1)
            p.kParam = predictK(p.kParam, code, 15)
            ++p.l1
        }
        d[p.l1 + 1] = d[p.l1] + 1
        return true
    }

    private fun topLineRounded(p: BandParam): Boolean {
        val d = p.pd
        d[p.l1] = 0
        var length = p.width
        while (length > 1) {
            if (abs(d[p.l1]) > p.roundedBitsMask) d[p.l1 + 1] = d[p.l1]
            else {
                var nSyms = 0
                if (p.bits.bits(1) != 0) {
                    nSyms = runLength(p, length, true)
                    if (nSyms < 0) return false
                }
                length -= nSyms
                while (nSyms-- > 0) { d[p.l1 + 1] = d[p.l1]; ++p.l1 }
                if (length <= 0) break
                d[p.l1 + 1] = 0
            }
            val code = symbol(p)
            val s = -(code and 1) xor (code ushr 1)
            d[p.l1 + 1] += p.roundedBitsMask * 2 * s + (s shr 31)
            p.kParam = predictK(p.kParam, code, 15)
            ++p.l1
            --length
        }
        if (length == 1) {
            val code = symbol(p)
            val s = -(code and 1) xor (code ushr 1)
            d[p.l1 + 1] += p.roundedBitsMask * 2 * s + (s shr 31)
            p.kParam = predictK(p.kParam, code, 15)
            ++p.l1
        }
        d[p.l1 + 1] = d[p.l1] + 1
        return true
    }

    private fun topLineNoRef(p: BandParam): Boolean {
        val d = p.pd
        val n = p.np!!
        d[p.l0] = 0
        d[p.l1] = 0
        var length = p.width
        while (length > 1) {
            if (d[p.l1] != 0) {
                val code = symbol(p)
                d[p.l1 + 1] = -(code and 1) xor (code ushr 1)
                p.kParam = predictK(p.kParam, code, 15)
            } else {
                var nSyms = 0
                if (p.bits.bits(1) != 0) {
                    nSyms = runLength(p, length, true)
                    if (nSyms < 0) return false
                }
                length -= nSyms
                while (nSyms-- > 0) { n[p.l2] = 0; d[p.l1 + 1] = 0; ++p.l1; ++p.l2 }
                if (length <= 0) break
                val code = symbol(p)
                d[p.l1 + 1] = -((code + 1) and 1) xor ((code + 1) ushr 1)
                p.kParam = predictK(p.kParam, code, 15)
            }
            n[p.l2] = p.kParam
            ++p.l2
            ++p.l1
            --length
        }
        if (length == 1) {
            val code = symbol(p)
            d[p.l1 + 1] = -(code and 1) xor (code ushr 1)
            p.kParam = predictK(p.kParam, code, 15)
            n[p.l2] = p.kParam
            ++p.l1
        }
        d[p.l1 + 1] = 0
        return true
    }

    /** One line of a band into [out] (LibRaw crxDecodeLine(param, bandBuf)). */
    private fun decodeLine(p: BandParam, out: IntArray): Boolean {
        if (p.curLine >= p.height) return false
        val lineLength = p.width + 2
        val outStart: Int
        if (p.curLine == 0) {
            p.sParam = 0
            p.kParam = 0
            p.l0 = 0
            p.l1 = lineLength
            p.l2 = 0
            outStart = p.l1 + 1
            val ok = if (p.supportsPartial) {
                if (p.roundedBitsMask <= 0) topLine(p)
                else {
                    p.roundedBits = 1
                    if (p.roundedBitsMask and 1.inv() != 0) while ((p.roundedBitsMask shr p.roundedBits) != 0) ++p.roundedBits
                    topLineRounded(p)
                }
            } else topLineNoRef(p)
            if (!ok) return false
        } else {
            p.l2 = 0
            if (p.curLine and 1 != 0) { p.l1 = 0; p.l0 = lineLength } else { p.l0 = 0; p.l1 = lineLength }
            outStart = p.l1 + 1
            val ok = when {
                !p.supportsPartial -> lineNoRef(p)
                p.roundedBitsMask <= 0 -> lineL1(p)
                else -> lineRounded(p)
            }
            if (!ok) return false
        }
        System.arraycopy(p.pd, outStart, out, 0, p.width)
        ++p.curLine
        return true
    }

    // ------------------------------------------------------------------ dequantisation
    private fun updateQparam(band: Subband): Boolean {
        val bits = band.param!!.bits
        var code = bits.zeros()
        if (code >= 23) code = bits.bits(8) else if (band.kParam != 0) code = bits.bits(band.kParam) or (code shl band.kParam)
        band.qParam += -(code and 1) xor (code ushr 1)
        band.kParam = predictK(band.kParam, code)
        return band.kParam <= 7
    }

    private fun subbandRow(band: Subband, row: Int): Int =
        if (row < band.rowStartAddOn) 0
        else if (row < band.height - band.rowEndAddOn) row - band.rowEndAddOn
        else band.height - band.rowEndAddOn - band.rowStartAddOn - 1

    private fun decodeLineIQ(band: Subband, q: QStep?, level: Int): Boolean {
        if (band.dataSize == 0L) { java.util.Arrays.fill(band.buf, 0); return true }
        val p = band.param ?: return false
        if (band.supportsPartial && q == null && !updateQparam(band)) return false
        if (!decodeLine(p, band.buf)) return false
        if (band.width <= 0) return true
        val buf = band.buf
        if (q != null) {
            val tbl = q.table
            val base = q.offsets[level] + q.width * subbandRow(band, p.curLine - 1)
            val mult = band.qStepMult
            fun quant(t: Int): Int {
                val v = band.qStepBase + ((((tbl[t].toLong() and 0xffffffffL) * mult) and 0xffffffffL) ushr 3).toInt()
                return v.coerceIn(1, 0x168000)
            }
            for (i in 0 until band.colStartAddOn) buf[i] *= quant(base)
            for (i in band.colStartAddOn until band.width - band.colEndAddOn)
                buf[i] *= quant(base + ((i - band.colStartAddOn) shr band.levelShift))
            val lastIdx = (band.width - band.colEndAddOn - band.colStartAddOn - 1) shr band.levelShift
            for (i in band.width - band.colEndAddOn until band.width) buf[i] *= quant(base + lastIdx)
        } else {
            var qScale = Q_STEP[band.qParam % 6] shr (6 - band.qParam / 6)
            if (band.qParam / 6 >= 6) qScale = Q_STEP[band.qParam % 6] * (1 shl (band.qParam / 6 + 26))
            if (qScale != 1) for (i in 0 until band.width) buf[i] *= qScale
        }
        return true
    }

    // ------------------------------------------------------------------ inverse 5/3 wavelet
    /** One row of the horizontal inverse transform: [out] from low band [lo] and high band [hi]. */
    private fun horizontal(out: IntArray, lo: IntArray, hi: IntArray, width: Int, flag: Int, alwaysLast: Boolean = false) {
        if (width <= 1) { out[0] = lo[0]; return }
        var a = 0; var b = 0; var o = 0
        if (flag and LEFT != 0) { out[0] = lo[0] - ((hi[0] + hi[1] + 2) shr 2); ++b }
        else out[0] = lo[0] - ((hi[0] + 1) shr 1)
        ++a
        var i = 0
        while (i < width - 3) {
            val delta = lo[a] - ((hi[b] + hi[b + 1] + 2) shr 2)
            out[o + 1] = hi[b] + ((delta + out[o]) shr 1)
            out[o + 2] = delta
            ++a; ++b; o += 2; i += 2
        }
        if (flag and RIGHT != 0) {
            val delta = lo[a] - ((hi[b] + hi[b + 1] + 2) shr 2)
            out[o + 1] = hi[b] + ((delta + out[o]) shr 1)
            if (alwaysLast || width and 1 != 0) out[o + 2] = delta
        } else if (width and 1 != 0) {
            val delta = lo[a] - ((hi[b] + 1) shr 1)
            out[o + 1] = hi[b] + ((delta + out[o]) shr 1)
            out[o + 2] = delta
        } else out[o + 1] = hi[b] + out[o]
    }

    private fun idwtGetLine(comp: Comp, level: Int): IntArray {
        val w = comp.wv[level]
        val r = w.lb[(w.tap - w.curH + 5) % 5 + 3]
        w.curH--
        return r
    }

    private fun idwtDecode(comp: Comp, level: Int, q: QStep?): Boolean {
        val w = comp.wv[level]
        if (w.curH != 0) return true
        val sb = 3 * level
        val bands = comp.bands
        if (w.height - 3 <= w.curLine && comp.tileFlag and BOTTOM == 0) {
            if (w.height and 1 != 0) {
                if (level > 0) { if (!idwtDecode(comp, level - 1, q)) return false }
                else if (!decodeLineIQ(bands[sb], q, level)) return false
                if (!decodeLineIQ(bands[sb + 1], q, level)) return false
            }
        } else {
            if (level > 0) { if (!idwtDecode(comp, level - 1, q)) return false }
            else if (!decodeLineIQ(bands[sb], q, level)) return false
            if (!decodeLineIQ(bands[sb + 1], q, level) || !decodeLineIQ(bands[sb + 2], q, level) || !decodeLineIQ(bands[sb + 3], q, level)) return false
        }
        return true
    }

    private fun idwtTransform(comp: Comp, level: Int): Boolean {
        val w = comp.wv[level]
        if (w.curH != 0) return true
        val lb = w.lb
        if (w.curLine >= w.height - 3) {
            if (comp.tileFlag and BOTTOM == 0) {
                if (w.height and 1 != 0) {
                    if (level > 0) {
                        if (comp.wv[level - 1].curH == 0) if (!idwtTransform(comp, level - 1)) return false
                        w.sb0 = idwtGetLine(comp, level - 1)
                    }
                    val h0 = lb[w.tap + 3]; val h1 = lb[(w.tap + 1) % 5 + 3]; val h2 = lb[(w.tap + 2) % 5 + 3]
                    val l0 = lb[0]; val l1old = lb[1]
                    lb[1] = lb[2]; lb[2] = l1old
                    horizontal(l0, w.sb0, w.sb1, w.width, comp.tileFlag)
                    val l1 = lb[1]
                    for (i in 0 until w.width) {
                        val delta = l0[i] - ((l1[i] + 1) shr 1)
                        h1[i] = l1[i] + ((delta + h0[i]) shr 1)
                        h2[i] = delta
                    }
                    w.curH += 3; w.curLine += 3; w.tap = (w.tap + 3) % 5
                } else {
                    val l2 = lb[2]
                    val h0 = lb[w.tap + 3]; val h1 = lb[(w.tap + 1) % 5 + 3]
                    lb[1] = l2
                    lb[2] = lb[1]
                    for (i in 0 until w.width) h1[i] = h0[i] + l2[i]
                    w.curH += 2; w.curLine += 2; w.tap = (w.tap + 2) % 5
                }
            }
        } else {
            if (level > 0) {
                if (comp.wv[level - 1].curH == 0 && !idwtTransform(comp, level - 1)) return false
                w.sb0 = idwtGetLine(comp, level - 1)
            }
            val l0 = lb[0]; val l1 = lb[1]
            val h0 = lb[w.tap + 3]; val h1 = lb[(w.tap + 1) % 5 + 3]; val h2 = lb[(w.tap + 2) % 5 + 3]
            lb[1] = lb[2]; lb[2] = l1
            horizontal(l0, w.sb0, w.sb1, w.width, comp.tileFlag)
            horizontal(l1, w.sb2, w.sb3, w.width, comp.tileFlag)
            val m0 = lb[0]; val m1 = lb[1]; val m2 = lb[2]
            for (i in 0 until w.width) {
                val delta = m0[i] - ((m2[i] + m1[i] + 2) shr 2)
                h1[i] = m1[i] + ((delta + h0[i]) shr 1)
                h2[i] = delta
            }
            if (w.curLine >= w.height - 3 && w.height and 1 != 0) {
                w.curH += 3; w.curLine += 3; w.tap = (w.tap + 3) % 5
            } else {
                w.curH += 2; w.curLine += 2; w.tap = (w.tap + 2) % 5
            }
        }
        return true
    }

    private fun idwtInitialize(comp: Comp, level: Int, q: QStep?): Boolean {
        if (level == 0) return true
        val bands = comp.bands
        for (curLevel in 0 until level) {
            val curBand = 3 * curLevel
            val w = comp.wv[curLevel]
            if (curLevel > 0) w.sb0 = idwtGetLine(comp, curLevel - 1)
            else if (!decodeLineIQ(bands[curBand], q, curLevel)) return false
            val h0 = w.lb[w.tap + 3]
            if (w.height > 1) {
                if (!decodeLineIQ(bands[curBand + 1], q, curLevel) || !decodeLineIQ(bands[curBand + 2], q, curLevel) ||
                    !decodeLineIQ(bands[curBand + 3], q, curLevel)) return false
                val l0 = w.lb[0]; val l1 = w.lb[1]; val l2 = w.lb[2]
                if (comp.tileFlag and TOP != 0) {
                    horizontal(l0, w.sb0, w.sb1, w.width, comp.tileFlag)
                    horizontal(w.lb[1], w.sb2, w.sb3, w.width, comp.tileFlag)
                    if (!decodeLineIQ(bands[curBand + 3], q, curLevel) || !decodeLineIQ(bands[curBand + 2], q, curLevel)) return false
                    horizontal(l2, w.sb2, w.sb3, w.width, comp.tileFlag)
                    // LibRaw quirk, reproduced for identical output: its L2 pointer is left advanced by the
                    // horizontal pass, so this reads L2 shifted by `off` and, past its end, the next line
                    // buffer in memory, which at this point is H0 itself (lineBuf[3])
                    val width = w.width
                    val off = if (width > 3) 2 * ((width - 2) / 2) else 0
                    for (i in 0 until width) {
                        val j = i + off
                        val l2v = if (j < width) l2[j] else h0[j - width]
                        h0[i] = l0[i] - ((l1[i] + l2v + 2) shr 2)
                    }
                } else {
                    horizontal(l0, w.sb0, w.sb1, w.width, comp.tileFlag)
                    horizontal(w.lb[2], w.sb2, w.sb3, w.width, comp.tileFlag)
                    for (i in 0 until w.width) h0[i] = l0[i] - ((l2[i] + 1) shr 1)
                }
                if (!idwtDecode(comp, curLevel, q) || !idwtTransform(comp, curLevel)) return false
            } else {
                if (!decodeLineIQ(bands[curBand + 1], q, curLevel)) return false
                horizontal(h0, w.sb0, w.sb1, w.width, comp.tileFlag, alwaysLast = true)
                ++w.curLine
                ++w.curH
                w.tap = (w.tap + 1) % 5
            }
        }
        return true
    }
}
