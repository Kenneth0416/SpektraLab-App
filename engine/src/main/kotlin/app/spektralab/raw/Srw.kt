package app.spektralab.raw

/**
 * Samsung SRW reader (NX series).
 *
 * TIFF container (II, magic 42, make "SAMSUNG"); the raw IFD carries Compression 32769..32773 and a
 * single strip. Supported pixel codecs, selected by the TIFF Compression tag exactly as LibRaw does:
 *
 *  * 32770 with tag 0xa010 present: "SRW1", per-row bit streams with a row offset table
 *    (LibRaw `samsung_load_raw`, rawspeed SamsungV0, rawloader decode_srw1);
 *  * 32772: "SRW2", one Huffman / difference stream over the whole frame
 *    (LibRaw `samsung2_load_raw`, rawspeed SamsungV1);
 *  * 32773: "SRW3" (NX1, NX500), 16-pixel groups with motion modes
 *    (LibRaw `samsung3_load_raw`, rawspeed SamsungV2);
 *  * 32769 / 32770 without 0xa010 and 14 bits: 16-bit little-endian words (LibRaw `unpacked_load_raw`).
 *
 * 12-bit uncompressed ("packed") files, compact cameras (EX1, EX2F, WB...) and every frame size LibRaw
 * has no hard-wired geometry for return null. Geometry (visible area, CFA of some models), black level
 * (maker note 0xa028 minus the key in 0xa020) and the saturation level follow LibRaw 0.22.
 *
 * White balance is the maker note's WB_RGGBLevelsUncorrected (0xa021) minus WB_RGGBLevelsBlack (0xa028),
 * as ExifTool documents the tags and as rawspeed and rawloader use them. LibRaw uses 0xa021 without the
 * subtraction, so both agree exactly when the stored black is zero.
 *
 * Ported from LibRaw (LGPL 2.1 / CDDL 1.0, derived from dcraw by Dave Coffin); the SRW3 layout was cross
 * checked against rawspeed and rawloader.
 */
object SrwReader {
    fun read(bytes: ByteArray): RawImage? = try { build(bytes) } catch (e: Exception) { null }

    internal class Meta(
        val make: String, val model: String,
        val rawWidth: Int, val rawHeight: Int, val bits: Int, val compression: Int,
        val dataOffset: Int, val dataLength: Int, val rowTable: Int,
        val cfa: CfaPattern, val black4: FloatArray, val white: Float,
        val cropX: Int, val cropY: Int, val cropW: Int, val cropH: Int,
        val wb: RawColor.Result, val orientation: Int,
    )

    private fun build(bytes: ByteArray): RawImage? {
        val m = meta(bytes) ?: return null
        val data = decode(bytes, m) ?: return null
        return RawImage(
            m.rawWidth, m.rawHeight, data, m.cfa, blackPerPosition(m.cfa, m.black4), m.white,
            m.cropX, m.cropY, m.cropW, m.cropH, m.wb.wb, m.wb.camToSrgb, m.orientation,
            make = m.make, model = m.model,
        )
    }

    /**
     * Black level per CFA position from the maker note's R, G1, G2, B levels: G1 is the green in the
     * row with red, G2 the one in the row with blue (what LibRaw reports as channels 1 and 3).
     */
    private fun blackPerPosition(cfa: CfaPattern, rggb: FloatArray) = FloatArray(cfa.size) { i ->
        val row = i / cfa.width
        when (cfa.colors[i]) {
            0 -> rggb[0]
            2 -> rggb[3]
            else -> if ((0 until cfa.width).any { cfa.colors[row * cfa.width + it] == 0 }) rggb[1] else rggb[2]
        }
    }

    internal fun meta(bytes: ByteArray): Meta? {
        if (bytes.size < 64 || bytes[0] != 'I'.code.toByte() || bytes[1] != 'I'.code.toByte()) return null
        val tiff = Tiff.parse(bytes, 0, setOf(42)) ?: return null
        val ifd0 = tiff.ifd0
        val make = ifd0.string(TiffTags.MAKE) ?: return null
        val model = ifd0.string(TiffTags.MODEL) ?: return null
        if (!make.startsWith("SAMSUNG", ignoreCase = true)) return null
        if (TiffTags.DNG_VERSION in ifd0) return null                    // Galaxy phone / NX DNGs are DngReader's

        // the raw IFD: a Samsung compression value, one strip
        val raw = tiff.allIfds().filter { it.int(TiffTags.COMPRESSION) in setOf(32769, 32770, 32772, 32773) }
            .maxByOrNull { (it.int(TiffTags.IMAGE_WIDTH) ?: 0).toLong() * (it.int(TiffTags.IMAGE_LENGTH) ?: 0) } ?: return null
        val compression = raw.int(TiffTags.COMPRESSION)!!
        val width = raw.int(TiffTags.IMAGE_WIDTH) ?: return null
        val height = raw.int(TiffTags.IMAGE_LENGTH) ?: return null
        val bits = raw.int(TiffTags.BITS_PER_SAMPLE) ?: return null
        if (width < 16 || height < 16 || width.toLong() * height > RawDecodeLimits.MAX_SAMPLES) return null
        if (bits != 12 && bits != 14) return null
        if (raw.int(TiffTags.SAMPLES_PER_PIXEL, 1) != 1) return null
        val offsets = raw[TiffTags.STRIP_OFFSETS] ?: return null
        val counts = raw[TiffTags.STRIP_BYTE_COUNTS] ?: return null
        if (offsets.count != 1 || counts.count != 1) return null
        val dataOffset = raw.longs(TiffTags.STRIP_OFFSETS, 1)!![0]
        val dataLength = raw.longs(TiffTags.STRIP_BYTE_COUNTS, 1)!![0]
        if (dataOffset <= 0 || dataOffset >= bytes.size || dataLength <= 0 || dataOffset + dataLength > bytes.size) return null

        val rowTableEntry = raw[0xa010]
        val rowTable = if (rowTableEntry != null && rowTableEntry.type == 4 && rowTableEntry.count == 1) raw.value(rowTableEntry, 0).toLong().toInt() else -1
        // codec choice (LibRaw: tag 0xa010 selects the SRW1 reader for 32770)
        val codec = when {
            compression == 32770 && rowTable >= 0 -> 1
            compression == 32772 -> 2
            compression == 32773 -> 3
            (compression == 32769 || compression == 32770) && bits == 14 && rowTable < 0 -> 0
            else -> return null
        }
        if (codec == 1 && (width % 16 != 0 || rowTable.toLong() + 4L * height > bytes.size)) return null
        if (codec == 3 && (width % 16 != 0 || width < 646 || height < 436)) return null
        if (codec == 0 && dataLength < 2L * width * height) return null

        // geometry and CFA (LibRaw identify.cpp, Samsung branch)
        var cfaBytes = raw.bytes(TiffTags.CFA_PATTERN) ?: ifd0.bytes(TiffTags.CFA_PATTERN) ?: return null
        if (cfaBytes.size != 4) return null
        var cfa: CfaPattern? = null
        val top: Int; val left: Int; val w: Int; val h: Int
        when {
            width == 4704 -> { top = 8; left = 8; w = width - 2 * left; h = height - top }
            model == "NX3000" -> { top = 38; left = 92; w = 5456; h = 3634; cfa = CfaPattern.GRBG }
            height == 3714 -> {
                w = 5536; h = height - 18
                if (width == 5600) { top = 18; left = width - w } else { top = 0; left = 0 }
                cfa = CfaPattern.GRBG
            }
            width == 5632 -> { top = 2; h = 3694; left = 32 + bits; w = 5574 - left }
            width == 5664 -> { top = 17; h = height - top; left = 96; w = 5544; cfa = CfaPattern.GBRG }
            width == 6496 -> { top = 0; left = 0; w = width; h = height; cfa = CfaPattern.GRBG }
            else -> return null
        }
        if (cfa == null) {
            val c = IntArray(4) { cfaBytes[it].toInt() and 0xff }
            if (c.any { it > 2 }) return null
            cfa = CfaPattern(2, 2, c)
        }
        if (w < 16 || h < 16 || left < 0 || top < 0 || left + w > width || top + h > height) return null
        // LibRaw's CFA is relative to the visible area; re-anchor it at the raw origin
        cfa = cfa.anchoredAt(top, left)
        // ... and it moves odd margins inwards by one pixel
        var cropX = left; var cropY = top; var cropW = w; var cropH = h
        if (cropY % 2 != 0) { cropY++; cropH-- }
        if (cropX % 2 != 0) { cropX++; cropW-- }

        // maker note: key (0xa020), WB levels (0xa021), black levels (0xa028)
        val note = samsungNote(tiff)
        val key = note?.let { n -> n.ints(0xa020)?.takeIf { it.size >= 11 } }
        val levels = note?.let { n -> n.ifd.longs(0xa021)?.takeIf { it.size == 4 } }
        val blacks = note?.let { n -> n.ifd.longs(0xa028)?.takeIf { it.size == 4 } }
        // stored order is R, G1, G2, B
        fun dec(v: LongArray?, base: Int): IntArray? =
            if (v == null || key == null) null else IntArray(4) { (v[it] - key[base + it]).toInt() }
        val trueBlack = dec(blacks, 0)
        val trueLevels = dec(levels, 0)
        val blackRgb = FloatArray(4) // R, G1, G2, B
        if (trueBlack != null && trueBlack.any { it != 0 }) {
            for (i in 0 until 4) blackRgb[i] = trueBlack[i].toFloat()
        } else if (width == 6496) {
            blackRgb.fill((1 shl (bits - 7)).toFloat())
        }
        val entry = CameraMatrices.find(make, model)
        val xyz = entry?.xyzToCamera
        if (entry == null || xyz == null || entry.colors != 3) return null
        val white = (if (entry.white > 0) entry.white else (1 shl bits) - 1).toFloat()
        if (white <= blackRgb.max() || blackRgb.any { it < 0f }) return null

        val asShot: DoubleArray? = if (trueLevels != null) {
            val b = trueBlack ?: IntArray(4)
            val r = trueLevels[0] - b[0]; val g = trueLevels[1] - b[1]; val bl = trueLevels[3] - b[3]
            if (r > 0 && g > 0 && bl > 0) doubleArrayOf(r.toDouble(), g.toDouble(), bl.toDouble()) else null
        } else null
        val colour = RawColor.fromXyzToCamera(DoubleArray(9) { xyz[it / 3][it % 3] }, asShot) ?: return null
        val orientation = (ifd0.int(TiffTags.ORIENTATION) ?: 1).let { if (it in 1..8) it else 1 }
        return Meta(make, model, width, height, bits, compression, dataOffset.toInt(), dataLength.toInt(), rowTable,
            cfa, blackRgb, white, cropX, cropY, cropW, cropH, colour, orientation)
    }

    /** The maker note IFD plus a typed accessor; offsets are tried relative to the note, then absolute. */
    private class Note(val ifd: TiffIfd) {
        fun ints(tag: Int) = ifd.longs(tag, 64)?.let { v -> IntArray(v.size) { v[it].toInt() } }
    }

    private fun samsungNote(tiff: Tiff): Note? {
        val exif = tiff.exif ?: return null
        val e = exif[TiffTags.MAKER_NOTE] ?: return null
        val start = e.dataOffset
        val len = e.byteCount
        if (len < 14 || start < 0 || start.toLong() + len > tiff.bytes.size) return null
        val le = tiff.littleEndian
        // Samsung is inconsistent: LibRaw reads offsets relative to the note, some files use absolute ones.
        for (base in intArrayOf(start, tiff.base)) {
            val ifd = tiff.ifdAt(start, base, le) ?: continue
            if (ifd[0xa020] != null && ifd[0xa020]!!.count >= 11 && ifd[0xa020]!!.dataOffset.toLong() in start.toLong()..(start.toLong() + len)) return Note(ifd)
        }
        return null
    }

    // ------------------------------------------------------------------ codecs

    private fun decode(b: ByteArray, m: Meta): ShortArray? {
        val w = m.rawWidth; val h = m.rawHeight
        val out = ShortArray(w * h)
        val end = m.dataOffset + m.dataLength
        return when {
            m.compression == 32773 -> if (decodeV3(b, m, out, end)) out else null
            m.compression == 32772 -> { decodeV2(b, m, out, end); out }
            m.rowTable >= 0 -> if (decodeV1(b, m, out, end)) out else null
            else -> { // 14-bit little-endian words
                for (i in out.indices) out[i] = (Bytes.u16le(b, m.dataOffset + 2 * i) and 0x3fff).toShort()
                out
            }
        }
    }

    /** SRW1: row offset table at [Meta.rowTable], each row an independent MSB-first 32-bit-word bit stream. */
    private fun decodeV1(b: ByteArray, m: Meta, out: ShortArray, end: Int): Boolean {
        val w = m.rawWidth; val h = m.rawHeight
        val len = IntArray(4)
        for (row in 0 until h) {
            val rowStart = m.dataOffset.toLong() + Bytes.u32le(b, m.rowTable + 4 * row)
            if (rowStart >= end) return false
            val bits = MsbWordBitPump(b, rowStart.toInt(), end, 4)
            len.fill(if (row < 2) 7 else 4)
            var col = 0
            while (col < w) {
                val dir = bits.bits(1)
                val ops = IntArray(4) { bits.bits(2) }
                for (c in 0 until 4) when (ops[c]) {
                    3 -> len[c] = bits.bits(4)
                    2 -> len[c]--
                    1 -> len[c]++
                }
                var c = 0
                while (c < 16) {
                    val i = len[((c and 1) shl 1) or (c shr 3)]
                    if (i > 24) return false
                    val up = if (c and 1 == 0) row - 1 else row - 2
                    val pred: Int
                    if (dir != 0) {
                        if (up < 0) return false
                        pred = out[up * w + col + c].toInt() and 0xffff
                    } else pred = if (col != 0) out[row * w + col - (if (c and 1 == 0) 2 else 1)].toInt() and 0xffff else 128
                    val diff = if (i > 0) (bits.bits(i) shl (32 - i)) shr (32 - i) else 0
                    out[row * w + col + c] = (diff + pred).toShort()
                    c += 2
                    if (c == 16) c = 1 else if (c == 17) c = 16
                }
                col += 16
            }
        }
        // red and blue are swapped in the stream
        var row = 0
        while (row < h - 1) {
            var col = 0
            while (col < w - 1) {
                val a = row * w + col + 1; val d = (row + 1) * w + col
                val t = out[a]; out[a] = out[d]; out[d] = t
                col += 2
            }
            row += 2
        }
        return true
    }

    /** SRW2: Huffman coded differences, one stream. */
    private fun decodeV2(b: ByteArray, m: Meta, out: ShortArray, end: Int) {
        val w = m.rawWidth; val h = m.rawHeight
        // 10-bit lookup: (code length, symbol)
        val table = intArrayOf(0x304, 0x307, 0x206, 0x205, 0x403, 0x600, 0x709, 0x80a, 0x90b, 0xa0c, 0xa0d, 0x501, 0x408, 0x402)
        val lut = IntArray(1024)
        var n = 0
        for (t in table) { val cnt = 1024 shr (t shr 8); for (k in 0 until cnt) lut[n++] = t }
        val bits = MsbBitPump(b, m.dataOffset, end)
        val vpred = IntArray(4)
        val hpred = IntArray(2)
        for (row in 0 until h) {
            for (col in 0 until w) {
                val e = lut[bits.peek(10)]
                bits.skip(e shr 8)
                val len = e and 0xff
                var diff = if (len == 0) 0 else bits.bits(len)
                if (len > 0 && (diff and (1 shl (len - 1))) == 0) diff -= (1 shl len) - 1
                if (col < 2) {
                    val k = (row and 1) * 2 + col
                    vpred[k] = (vpred[k] + diff) and 0xffff
                    hpred[col] = vpred[k]
                } else hpred[col and 1] = (hpred[col and 1] + diff) and 0xffff
                out[row * w + col] = hpred[col and 1].toShort()
            }
        }
    }

    /** SRW3: 16-pixel groups, each predicted from the left or from the two rows above, plus small differences. */
    private fun decodeV3(b: ByteArray, m: Meta, out: ShortArray, end: Int): Boolean {
        val w = m.rawWidth; val h = m.rawHeight
        val r = WordBits(b, m.dataOffset, end)
        r.bits(16); r.bits(4)
        val bitDepth = r.bits(4) + 1
        val dataMax = (1 shl bitDepth) - 1
        r.bits(8); r.bits(16); r.bits(16); r.bits(16); r.bits(4)
        val optFlags = r.bits(4)
        r.bits(16); r.bits(8); r.bits(2)
        val initVal = r.bits(14)
        val motionOffset = intArrayOf(-4, -2, -2, 0, 0, 2, 4)
        val motionAverage = intArrayOf(0, 0, 1, 0, 1, 0, 0)
        val diffBitsMode = IntArray(6)
        val diffBits = IntArray(4)
        val flags = IntArray(4)
        for (row in 0 until h) {
            // each row starts at the next 16-byte boundary after the words read so far
            r.alignRow(m.dataOffset)
            var motion = 7
            var scale = 0
            val rowStart = row * w
            for (i in 0 until 6) diffBitsMode[i] = if (row < 2) 7 else 4
            var col = 0
            while (col < w - 15) {
                if (optFlags and 4 == 0 && (col and 63) == 0) {
                    val i = r.bits(2)
                    if (i < 3) scale += intArrayOf(0, -2, 2)[i] else scale = r.bits(12)
                }
                if (optFlags and 2 != 0) motion = if (r.bits(1) != 0) 3 else 7
                else if (r.bits(1) == 0) motion = r.bits(3)
                if (motion == 7) {
                    for (i in 0 until 16) out[rowStart + col + i] = if (col == 0) initVal.toShort() else out[rowStart + col + i - 2]
                } else {
                    if (row < 2 || motion > 6) return false
                    for (i in 0 until 16) {
                        val refRow = if ((row + i) and 1 != 0) row - 2 else row - 1
                        val refCol = col + i + motionOffset[motion] + (1 - ((row + i) and 1)) * (1 - 2 * (i and 1))
                        val ref = refRow * w + refCol // flat index, as LibRaw: the edge may reach into a neighbouring row
                        if (ref < 0 || ref + 2 >= out.size) return false
                        val a = out[ref].toInt() and 0xffff
                        out[rowStart + col + i] = (if (motionAverage[motion] != 0) (a + (out[ref + 2].toInt() and 0xffff) + 1) shr 1 else a).toShort()
                    }
                }
                diffBits.fill(0)
                if (optFlags and 1 != 0 || r.bits(1) == 0) {
                    for (i in 0 until 4) flags[i] = r.bits(2)
                    for (i in 0 until 4) {
                        val colorNum = if (row % 2 != 0) i shr 1 else ((i shr 1) + 2) % 3
                        diffBits[i] = when (flags[i]) {
                            0 -> diffBitsMode[colorNum * 2]
                            1 -> diffBitsMode[colorNum * 2] + 1
                            2 -> diffBitsMode[colorNum * 2] - 1
                            else -> r.bits(4)
                        }
                        diffBitsMode[colorNum * 2] = diffBitsMode[colorNum * 2 + 1]
                        diffBitsMode[colorNum * 2 + 1] = diffBits[i]
                        if (diffBits[i] > bitDepth + 1 || diffBits[i] < 0) return false
                    }
                }
                for (i in 0 until 16) {
                    val len = diffBits[i shr 2]
                    val q = if (len == 0) 0 else { val v = r.bits(len); val s = 1 shl (len - 1); (v xor s) - s }
                    val diff = q * (scale * 2 + 1) + scale
                    val rcol = if (row % 2 != 0) col + ((i and 7) shl 1) + 1 - (i shr 3) else col + ((i and 7) shl 1) + (i shr 3)
                    val v = (out[rowStart + rcol].toInt() and 0xffff) + diff
                    out[rowStart + rcol] = (if (v < 0) 0 else if (v > dataMax) dataMax else v).toShort()
                }
                col += 16
            }
            if (r.overrun) return false
        }
        return true
    }

    /**
     * MSB-first reader over little-endian 32-bit words that loads a word only when it needs one, so
     * [bytePosition] is the position LibRaw's `ph1_bithuff` has reached.
     */
    private class WordBits(val b: ByteArray, val start: Int, val end: Int) {
        private var pos = start
        private var cache = 0L
        private var count = 0
        var overrun = false; private set

        fun bits(n: Int): Int {
            if (n == 0) return 0
            if (count < n) {
                var v = 0L
                for (i in 0 until 4) {
                    val p = pos + i
                    if (p < end) v = v or ((b[p].toLong() and 0xff) shl (8 * i)) else overrun = true
                }
                pos += 4
                cache = (cache shl 32) or v
                count += 32
            }
            count -= n
            return ((cache ushr count) and ((1L shl n) - 1)).toInt()
        }

        /** Drops buffered bits and skips to the next position that is 16-byte aligned relative to [dataOffset]. */
        fun alignRow(dataOffset: Int) {
            pos += (dataOffset - pos) and 15
            cache = 0; count = 0
        }
    }
}
