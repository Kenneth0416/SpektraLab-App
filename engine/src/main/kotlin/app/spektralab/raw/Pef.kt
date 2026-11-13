package app.spektralab.raw

/*
 * Pentax PEF reader.
 *
 * Port of the Pentax paths of LibRaw 0.22.2 / dcraw.c (Dave Coffin; LibRaw LLC; LGPL 2.1 / CDDL,
 * ported under GPLv3):
 *  - pentax_load_raw()      (src/decoders/decoders_dcraw.cpp): the compressed PEF codec, a
 *                            12-bit prefix table from maker-note tag 0x0220 and a two-column
 *                            horizontal / vertical predictor;
 *  - packed_load_raw() / unpacked_load_raw() (src/decoders/generic.cpp): uncompressed PEF;
 *  - parsePentax() (src/metadata/pentax.cpp): black point 0x0200, as-shot white point 0x0201;
 *  - identify_finetune_pentax() (src/metadata/identify.cpp): per-model visible area and
 *    colour-filter overrides; the output crop is the maker-note inset crop (0x0038 / 0x0039,
 *    LibRaw raw_inset_crops[0]) when present, else that visible area.
 */

/**
 * Pentax (and Ricoh-branded Pentax) PEF files. Supported pixel layouts:
 *  - Pentax compressed (TIFF compression 65535), 12..16 bit;
 *  - uncompressed 12-bit packed (1.5 bytes per pixel, big-endian bit order);
 *  - uncompressed 14/16-bit stored as 16-bit words in the file's byte order.
 * Returns null for anything else (Samsung-branded files, monochrome K-3 III, multi-strip data,
 * DNG, cameras without a colour matrix in [CameraMatrices], undecodable Huffman tables).
 */
object PefReader {
    fun read(bytes: ByteArray): RawImage? = runCatching { parse(bytes) }.getOrNull()

    private const val PENTAX_COMPRESSION = 65535

    // PentaxModelID values (LibRaw internal/libraw_cameraids.h) that need a geometry override
    private const val ID_K7 = 0x12db8L
    private const val ID_KX = 0x12dfeL
    private const val ID_645D = 0x12e08L
    private const val ID_KR = 0x12e6cL
    private const val ID_K5 = 0x12e76L
    private const val ID_K5II = 0x12f70L
    private const val ID_K5IIS = 0x12f71L
    private const val ID_K3 = 0x12fc0L
    private const val ID_K3II = 0x1309cL
    private const val ID_K70 = 0x13222L
    private const val ID_KP = 0x1322cL
    private const val ID_K3III = 0x13254L
    private const val ID_KF = 0x132b8L
    private const val ID_K3III_MONO = 0x132d6L

    private fun parse(bytes: ByteArray): RawImage? {
        if (bytes.size < 64) return null
        val tiff = Tiff.parse(bytes, 0, setOf(42), maxIfds = 4) ?: return null
        val ifd0 = tiff.ifd0
        if (TiffTags.DNG_VERSION in ifd0) return null
        val make = ifd0.string(TiffTags.MAKE) ?: return null
        val model = ifd0.string(TiffTags.MODEL) ?: return null
        val pentaxMake = make.startsWith("PENTAX", ignoreCase = true) ||
            (make.startsWith("RICOH", ignoreCase = true) && model.startsWith("PENTAX", ignoreCase = true))
        if (!pentaxMake) return null

        val width = ifd0.int(TiffTags.IMAGE_WIDTH) ?: return null
        val height = ifd0.int(TiffTags.IMAGE_LENGTH) ?: return null
        if (width < 8 || height < 8 || width.toLong() * height > RawDecodeLimits.MAX_SAMPLES) return null
        if (ifd0.int(TiffTags.SAMPLES_PER_PIXEL, 1) != 1) return null
        val bps = ifd0.int(TiffTags.BITS_PER_SAMPLE, 0)
        if (bps !in 12..16) return null
        val compression = ifd0.int(TiffTags.COMPRESSION, 1)
        val offsets = ifd0.longs(TiffTags.STRIP_OFFSETS, 4) ?: return null
        val counts = ifd0.longs(TiffTags.STRIP_BYTE_COUNTS, 4) ?: return null
        if (offsets.size != 1 || counts.size != 1) return null
        val dataOffset = offsets[0]; val dataBytes = counts[0]
        if (dataOffset <= 0 || dataBytes <= 0 || dataOffset >= bytes.size || dataBytes > bytes.size - dataOffset) return null

        val mn = tiff.makerNote() ?: return null
        if (mn.kind != "pentax") return null
        val modelId = mn.ifd.longs(0x0005, 1)?.firstOrNull() ?: 0L

        val geo = geometry(width, height, modelId) ?: return null
        val declared = (if (geo.filters != null) CfaPattern.fromDcrawFilters(geo.filters) else readCfa(tiff, ifd0)) ?: return null
        if (!declared.isBayer) return null
        val cfa = declared.anchoredAt(geo.originTop, geo.originLeft)               // patterns are relative to the visible origin

        val w = width.toLong(); val h = height.toLong()
        val pixels: ShortArray = when {
            compression == PENTAX_COMPRESSION -> decodeCompressed(bytes, dataOffset.toInt(), dataBytes.toInt(), width, height, bps, mn.ifd) ?: return null
            compression == 1 && bps == 12 && dataBytes >= w * h * 3 / 2 -> {
                val out = ShortArray(width * height)
                if (!Unpack.packed(bytes, dataOffset.toInt(), 0, width, height, 12, Unpack.BitOrder.MSB, out)) return null
                out
            }
            compression == 1 && (bps == 14 || bps == 16) && dataBytes >= w * h * 2 -> {
                val out = ShortArray(width * height)
                if (!Unpack.u16(bytes, dataOffset.toInt(), 0, width, height, tiff.littleEndian, out)) return null
                out
            }
            else -> return null
        }

        // levels
        val entry = CameraMatrices.find(make, model) ?: return null
        val matrix = entry.xyzToCamera?.takeIf { entry.colors == 3 } ?: return null
        val tableBlack = entry.blackLevel.toFloat()
        val fileBlack = mn.ifd.ints(0x0200)?.takeIf { it.size >= 4 }
        val black = FloatArray(4) { i ->
            val row = i / 2; val col = i % 2
            // file order is R, Gr (green in the red row), Gb (green in the blue row), B
            val slot = when (cfa.color(row, col)) {
                0 -> 0
                2 -> 3
                else -> if (cfa.color(row, 1 - col) == 0) 1 else 2
            }
            val file = fileBlack?.get(slot)?.toFloat() ?: (if (entry.black < 0) tableBlack else 0f)
            file + (if (entry.black > 0) tableBlack else 0f)
        }
        val white = (if (entry.white > 0) entry.white else (1 shl bps) - 1).coerceAtMost(0xffff).toFloat()
        if (white <= black.max()) return null

        // as-shot white point: R, Gr, Gb, B (8192 = 1.0)
        val wp = mn.ifd.ints(0x0201)?.takeIf { it.size >= 4 && it[0] > 0 && it[1] > 0 && it[3] > 0 }
        val shot = wp?.let { doubleArrayOf(it[0].toDouble(), it[1].toDouble(), it[3].toDouble()) }
        val flat = DoubleArray(9) { matrix[it / 3][it % 3] }
        val colour = RawColor.fromXyzToCamera(flat, shot) ?: return null
        val orientation = ifd0.int(TiffTags.ORIENTATION)?.takeIf { it in 1..8 } ?: 1

        val crop = insetCrop(mn.ifd, width, height) ?: intArrayOf(geo.left, geo.top, geo.width, geo.height)
        return RawImage(width, height, pixels, cfa, black, white, crop[0], crop[1], crop[2], crop[3],
            colour.wb, colour.camToSrgb, orientation, make = make, model = model)
    }

    /**
     * The camera JPEG area as in the other readers (LibRaw raw_inset_crops[0]): maker-note ImageAreaOffset
     * (0x0038: left, top) and RawImageSize (0x0039: width, height), with an odd origin moved to the next
     * even pixel as LibRaw's open_datastream does; null when absent or outside the frame.
     */
    internal fun insetCrop(mn: TiffIfd, rawWidth: Int, rawHeight: Int): IntArray? {
        val o = mn.ints(0x0038)?.takeIf { it.size >= 2 } ?: return null
        val s = mn.ints(0x0039)?.takeIf { it.size >= 2 } ?: return null
        var left = o[0]; var top = o[1]; var w = s[0]; var h = s[1]
        if (left < 0 || top < 0 || w < 8 || h < 8 || left + w > rawWidth || top + h > rawHeight) return null
        if (left % 2 != 0) { left++; w-- }
        if (top % 2 != 0) { top++; h-- }
        return intArrayOf(left, top, w, h)
    }

    /** 2x2 CFA from the EXIF CFAPattern tag (colours 0 R, 1 G, 2 B). */
    private fun readCfa(tiff: Tiff, ifd0: TiffIfd): CfaPattern? {
        val e = tiff.exif?.get(TiffTags.EXIF_CFA_PATTERN)
        if (e != null && e.count >= 8) {
            val b = tiff.bytes; val p = e.dataOffset; val le = tiff.littleEndian
            if (Bytes.u16(b, p, le) == 2 && Bytes.u16(b, p + 2, le) == 2) {
                val c = IntArray(4) { b[p + 4 + it].toInt() and 0xff }
                if (c.all { it in 0..2 }) return CfaPattern(2, 2, c)
            }
            return null
        }
        val dim = ifd0.ints(TiffTags.CFA_REPEAT_PATTERN_DIM)
        val pat = ifd0.ints(TiffTags.CFA_PATTERN)
        if (dim != null && pat != null && dim.size == 2 && dim[0] == 2 && dim[1] == 2 && pat.size >= 4 && pat.take(4).all { it in 0..2 })
            return CfaPattern(2, 2, pat.copyOf(4))
        return null
    }

    /**
     * Output [left]/[top]/[width]/[height] (odd margins moved to the next even pixel, as LibRaw does) and the
     * origin ([originLeft], [originTop]) the colour filter pattern is relative to.
     */
    internal class Geometry(val left: Int, val top: Int, val width: Int, val height: Int, val filters: Int?, val originLeft: Int, val originTop: Int)

    /** LibRaw identify_finetune_pentax(): visible area and CFA overrides; null for the monochrome K-3 III. */
    internal fun geometry(rawWidth: Int, rawHeight: Int, id: Long): Geometry? {
        var width = rawWidth; var height = rawHeight
        var left = 0; var top = 0
        var filters: Int? = null
        if (height == 2624 && width == 3936) { height = 2616; width = 3896 }                 // K10D, GX10
        if (height == 3136 && width == 4864) { height = 3124; width = 4688; filters = 0x16161616 } // K20D, GX20
        if (width == 4352 && (id == ID_KR || id == ID_KX)) { width = 4309; filters = 0x16161616 }
        if (width >= 4960 && (id == ID_K5 || id == ID_K5II || id == ID_K5IIS)) { left = 10; width = 4950; filters = 0x16161616 }
        if (width == 6080 && id == ID_K70) { height = 4016; top = 32; width = 6020; left = 60 }
        if (width == 4736 && id == ID_K7) { height = 3122; width = 4684; filters = 0x16161616; top = 2 }
        if (width == 6080 && id == ID_KF) { top = 28; height = 4024; left = 56; width = 6024 }
        if (width == 6080 && id == ID_K3II) { left = 4; width = 6040 }
        if (width == 6304 && id == ID_K3III) { left = 26; width = 6224; top = 34; height = 4160 }
        if (id == ID_K3III_MONO) return null
        if (width == 6112 && id == ID_KP) { left = 54; top = 28; width = 6028; height = rawHeight - top }
        if (width == 6080 && id == ID_K3) { left = 4; width = 6040 }
        if (width == 7424 && id == ID_645D) { height = 5502; width = 7328; filters = 0x61616161; top = 29; left = 48 }
        if (left < 0 || top < 0 || width < 8 || height < 8 || left + width > rawWidth || top + height > rawHeight) return null
        val originLeft = left; val originTop = top
        if (top and 1 == 1) { top++; height-- }
        if (left and 1 == 1) { left++; width-- }
        if (width < 8 || height < 8) return null
        return Geometry(left, top, width, height, filters, originLeft, originTop)
    }

    // ---------------------------------------------------------------- compressed pixels

    /** dcraw pentax_load_raw(): table from maker-note tag 0x0220, then predictive Huffman decoding. */
    private fun decodeCompressed(bytes: ByteArray, offset: Int, length: Int, width: Int, height: Int, bps: Int, mn: TiffIfd): ShortArray? {
        val lut = readTable(bytes, mn) ?: return null
        val pump = MsbBitPump(bytes, offset, offset + length)
        val out = ShortArray(width * height)
        val vpred = IntArray(4)                                   // [row & 1][col], col < 2
        val hpred = IntArray(2)
        for (row in 0 until height) {
            val base = row * width
            val vrow = (row and 1) * 2
            for (col in 0 until width) {
                val bits = pump.peek(32)
                val e = lut[bits ushr 20]
                if (e < 0) return null
                val len = e ushr 8
                val sym = e and 0xff
                val total = len + sym
                var diff = if (sym == 0) 0 else (bits ushr (32 - total)) and ((1 shl sym) - 1)
                pump.skip(total)
                if (sym != 0 && (diff and (1 shl (sym - 1))) == 0) diff -= (1 shl sym) - 1
                if (col < 2) {
                    vpred[vrow + col] = (vpred[vrow + col] + diff) and 0xffff
                    hpred[col] = vpred[vrow + col]
                } else {
                    hpred[col and 1] = (hpred[col and 1] + diff) and 0xffff
                }
                out[base + col] = hpred[col and 1].toShort()
            }
            if (pump.consumedPadding) return null
        }
        return out
    }

    /** Lookup indexed by the next 12 bits: (code length shl 8) or symbol, -1 for unassigned prefixes. */
    private fun readTable(bytes: ByteArray, mn: TiffIfd): IntArray? {
        val e = mn[0x0220] ?: return null
        val p = e.dataOffset
        val le = mn.littleEndian
        if (e.byteCount < 14) return null
        val dep = (Bytes.u16(bytes, p, le) + 12) and 15
        if (dep == 0 || e.byteCount < 14 + 3 * dep) return null
        val lut = IntArray(4096) { -1 }
        for (c in 0 until dep) {
            val start = Bytes.u16(bytes, p + 14 + 2 * c, le)
            val len = bytes[p + 14 + 2 * dep + c].toInt() and 0xff
            fillHuffman(lut, start, len, c)
        }
        return lut
    }

    /**
     * Fills the 12-bit prefix table for one symbol. [start] + count can cross 4096
     * (for example start 4090 and 16 codes of length 8): those entries continue at 0.
     */
    internal fun fillHuffman(lut: IntArray, start: Int, len: Int, symbol: Int) {
        if (len !in 0..12 || lut.size != 4096) return
        val count = 4096 shr len
        val from = start and 4095
        val end = (from + count - 1) and 4095
        val packed = (len shl 8) or symbol
        if (from <= end) {
            for (x in from..end) lut[x] = packed
        } else {
            for (x in from until 4096) lut[x] = packed
            for (x in 0..end) lut[x] = packed
        }
    }
}
