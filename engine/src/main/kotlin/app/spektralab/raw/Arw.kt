package app.spektralab.raw

/*
 * Sony ARW / SR2 reader.
 *
 * Ported from LibRaw 0.22.2 (LGPL 2.1 / CDDL, derived from dcraw.c by Dave Coffin; used here
 * under GPLv3): src/metadata/tiff.cpp (compression dispatch, tags 0x7010 / 0x7303 / 0x7310 /
 * 0x7313 / 0x74c7 / 0x74c8 / 0xc61f / 0xc620 / 0xc634, the SR2Private IFD), src/metadata/sony.cpp
 * (sony_decrypt, parseSonySR2, maker-note tag 0xb02b), src/metadata/identify.cpp and
 * src/utils/open.cpp (Sony visible-area table and level defaults), src/decoders/decoders_dcraw.cpp
 * (sony_arw2_load_raw), src/decoders/decoders_libraw_dcrdefs.cpp (sony_ljpeg_load_raw) and
 * src/decoders/generic.cpp (unpacked_load_raw, packed_load_raw). Cross-checked against rawler
 * 0.8.0 src/decoders/arw.rs (LGPL 2.1).
 */

/**
 * Sony ARW (and the older SR2 container, which is the same TIFF layout) raw files.
 *
 * Supported pixel layouts (all produce exactly LibRaw's `raw_image`):
 * - uncompressed 16-bit little-endian words holding 14-bit data (Compression 1 or 32767 with
 *   `bytes == w * h * 2`); big-endian for the DSC-R1 (SR2);
 * - uncompressed 12-bit little-endian packed (Compression 32767, BitsPerSample 12, A700-era);
 * - "ARW2" lossy compression: 16-byte blocks of 16 pixels with 11-bit max/min and 7-bit
 *   deltas, expanded through the tone curve of tag 0x7010 (Compression 32767, `bytes == w * h`);
 * - lossless compressed (Compression 7, ILCE-1 / ILCE-7* / ILCE-9*): tiles of 4-component
 *   lossless JPEG, each component one position of a 2x2 Bayer cell.
 *
 * Returns null for ARW1 (DSLR-A100 era), SRF (DSC-F828 / DSC-V3, encrypted pixel data),
 * the YCbCr reduced-size raws, the wavelet "ARW6" (Compression 32766), DNG, and anything
 * malformed or truncated.
 *
 * Levels, white balance and crop follow LibRaw: black / WB from tags 0x7300 / 0x7303 /
 * 0x7310 / 0x7313 in the raw IFD and in the encrypted SR2SubIFD (found through DNGPrivateData
 * -> SR2Private 0x7200 / 0x7201 / 0x7221), LibRaw's defaults and its adobe_coeff table; white
 * from WhiteLevel or the bit depth; the output crop is LibRaw's raw inset crop (DefaultCrop or
 * 0x74c7 / 0x74c8 in the raw IFD, else maker-note tag 0xb02b) clipped to LibRaw's visible area.
 */
object ArwReader {
    fun read(bytes: ByteArray): RawImage? = try { parse(bytes) } catch (e: Exception) { null }

    private enum class Kind { UNPACKED16, PACKED12, ARW2, LJPEG }

    private fun parse(bytes: ByteArray): RawImage? {
        if (bytes.size < 16) return null
        val tiff = Tiff.parse(bytes, 0, setOf(42)) ?: return null
        val ifd0 = tiff.ifd0
        val make = ifd0.string(TiffTags.MAKE) ?: return null
        if (!make.startsWith("SONY", ignoreCase = true)) return null
        if (TiffTags.DNG_VERSION in ifd0) return null
        val model = ifd0.string(TiffTags.MODEL) ?: ""
        // SRF (encrypted pixels at fixed offsets) and the A100's ARW1 layout are not supported
        if (model.equals("DSC-F828", true) || model.equals("DSC-V3", true) || model.equals("DSLR-A100", true)) return null

        val raw = tiff.allIfds()
            .filter { (TiffTags.STRIP_OFFSETS in it || TiffTags.TILE_OFFSETS in it) && it.int(TiffTags.SAMPLES_PER_PIXEL, 1) == 1 &&
                it.int(TiffTags.COMPRESSION, 1) in setOf(1, 7, 32767) && (it.int(TiffTags.BITS_PER_SAMPLE) ?: 0) >= 8 }
            .maxByOrNull { (it.int(TiffTags.IMAGE_WIDTH) ?: 0).toLong() * (it.int(TiffTags.IMAGE_LENGTH) ?: 0) } ?: return null
        val rawW = raw.int(TiffTags.IMAGE_WIDTH) ?: return null
        val rawH = raw.int(TiffTags.IMAGE_LENGTH) ?: return null
        if (rawW < 22 || rawH < 22 || rawW > 64000 || rawH > 64000) return null
        val pixels = rawW.toLong() * rawH
        if (pixels > RawDecodeLimits.MAX_SAMPLES) return null
        val compression = raw.int(TiffTags.COMPRESSION, 1)
        val tagBps = raw.int(TiffTags.BITS_PER_SAMPLE) ?: return null

        val tiled = TiffTags.TILE_OFFSETS in raw
        val stripOffset: Long
        val stripBytes: Long
        if (!tiled) {
            val offs = raw.longs(TiffTags.STRIP_OFFSETS, 2) ?: return null
            val counts = raw.longs(TiffTags.STRIP_BYTE_COUNTS, 2) ?: return null
            if (offs.size != 1 || counts.size != 1) return null
            stripOffset = offs[0]; stripBytes = counts[0]
            if (stripOffset <= 0 || stripOffset >= bytes.size) return null
        } else { stripOffset = 0; stripBytes = 0 }

        val kind = when (compression) {
            1 -> if (!tiled && stripBytes == pixels * 2) Kind.UNPACKED16 else return null
            32767 -> when {
                tiled -> return null
                stripBytes == pixels -> Kind.ARW2
                stripBytes == pixels * 2 -> Kind.UNPACKED16
                stripBytes * 8 != pixels * tagBps -> return null                  // ARW1
                tagBps == 12 && rawW % 2 == 0 -> Kind.PACKED12
                else -> return null
            }
            7 -> {
                // LibRaw only routes these models to sony_ljpeg_load_raw
                val gated = model.equals("ILCE-1", true) || model.startsWith("ILCE-7", true) || model.startsWith("ILCE-9", true)
                if (!gated || tagBps != 14) return null
                Kind.LJPEG
            }
            else -> return null
        }
        val bps = when (kind) { Kind.PACKED12 -> 12; else -> 14 }

        // ---------------------------------------------------------------- geometry
        val (visW, visH, forceBlackZero) = visibleArea(model, rawW, rawH)
        if (visW <= 0 || visH <= 0 || visW > rawW || visH > rawH) return null

        // ---------------------------------------------------------------- pixels
        val data = when (kind) {
            Kind.UNPACKED16 -> {
                if (stripOffset + pixels * 2 > bytes.size) return null
                val d = ShortArray(pixels.toInt())
                if (!Unpack.u16(bytes, stripOffset.toInt(), rawW * 2, rawW, rawH, littleEndian = if (rawW == 3984) false else tiff.littleEndian, dst = d)) return null
                d
            }
            Kind.PACKED12 -> {
                if (stripOffset + pixels * 3 / 2 > bytes.size) return null
                val d = ShortArray(pixels.toInt())
                if (!Unpack.packed(bytes, stripOffset.toInt(), rawW * 3 / 2, rawW, rawH, 12, Unpack.BitOrder.LSB, d)) return null
                d
            }
            Kind.ARW2 -> {
                if (stripOffset + pixels > bytes.size) return null
                decodeArw2(bytes, stripOffset.toInt(), rawW, rawH, visH, toneCurve(raw))
            }
            Kind.LJPEG -> decodeLjpeg(bytes, raw, rawW, rawH, tiled, tiff.littleEndian) ?: return null
        }

        // ---------------------------------------------------------------- levels and WB
        var black = 0
        val cblack = IntArray(4)                       // LibRaw order R, G, B, G2
        var camMul: IntArray? = null                   // R, G, B, G2
        fun levelTags(ifd: TiffIfd) {
            ifd.ints(0x7300, 4)?.takeIf { it.size == 4 }?.let { v -> for (c in 0..3) cblack[c] = v[c] and 0xffff }
            ifd.ints(0x7303, 4)?.takeIf { it.size == 4 }?.let { v -> camMul = IntArray(4).also { m -> for (c in 0..3) m[grbgToRgbg(c)] = v[c] and 0xffff } }
            ifd.ints(0x7310, 4)?.takeIf { it.size == 4 }?.let { v ->
                for (c in 0..3) cblack[rggbToRgbg(c)] = v[c] and 0xffff
                val m = minOf(minOf(cblack[0], cblack[1]), minOf(cblack[2], cblack[3]))
                for (c in 0..3) cblack[c] -= m
                black = m
            }
            ifd.ints(0x7313, 4)?.takeIf { it.size == 4 }?.let { v -> camMul = IntArray(4).also { m -> for (c in 0..3) m[rggbToRgbg(c)] = v[c] and 0xffff } }
        }
        levelTags(raw)
        sr2SubIfd(tiff)?.let { levelTags(it) }          // parsed after the SubIFDs, so it wins

        var maximum = raw.int(TiffTags.WHITE_LEVEL) ?: 0
        if (forceBlackZero) black = 0
        if (rawW > 3888 && black == 0 && cblack[0] == 0) black = if (kind == Kind.ARW2) 512 else 128 shl (bps - 12)
        val entry = CameraMatrices.find(make, model) ?: return null
        val matrix = entry.xyzToCamera ?: return null
        if (entry.colors != 3) return null
        if (entry.black > 0) { black = entry.black; cblack.fill(0) }
        else if (entry.black < 0 && black + cblack.take(4).sum() / 4 == 0) { black = -entry.black; cblack.fill(0) }
        if (entry.white > 0) maximum = entry.white
        if (maximum == 0) maximum = (1 shl bps) - 1         // the ARW2 curve is the identity at 2^14 - 1
        if (maximum > 0xffff) maximum = 0xffff
        if (kind == Kind.PACKED12) {
            if (maximum > 4095) maximum = 4095
            if (black > 256 || cblack[0] > 256) { black /= 4; for (c in 0..3) cblack[c] /= 4 }
        }

        // ---------------------------------------------------------------- CFA
        val cfa = cfaPattern(raw) ?: return null
        val blackPos = FloatArray(4) { i ->
            val c = cfa.colors[i]
            val c4 = if (c == 1 && (0..1).any { cfa.colors[(i / 2) * 2 + it] == 2 }) 3 else c
            (black + cblack[c4]).toFloat()
        }
        if (maximum <= blackPos.max()) return null

        // ---------------------------------------------------------------- crop
        var cx = 0; var cy = 0; var cw = visW; var ch = visH
        insetCrop(tiff, raw)?.let { (l, t, w, h) ->
            if (w > 0 && h > 0 && l >= 0 && t >= 0 && l < visW && t < visH) {
                cx = l; cy = t; cw = minOf(w, visW - l); ch = minOf(h, visH - t)
            }
        }
        if (cw < 4 || ch < 4) return null

        // ---------------------------------------------------------------- colour
        val mul = camMul?.takeIf { m -> m[0] > 0 && m[1] > 0 && m[2] > 0 }
            ?.let { m -> doubleArrayOf(m[0].toDouble(), m[1].toDouble(), m[2].toDouble()) }
        val colour = RawColor.fromXyzToCamera(DoubleArray(9) { matrix[it / 3][it % 3] }, mul) ?: return null
        val orientation = (ifd0.int(TiffTags.ORIENTATION) ?: 1).let { if (it in 1..8) it else 1 }

        return RawImage(rawW, rawH, data, cfa, blackPos, maximum.toFloat(), cx, cy, cw, ch,
            colour.wb, colour.camToSrgb, orientation, make = make, model = model)
    }

    private fun rggbToRgbg(q: Int) = q xor (q shr 1)
    private fun grbgToRgbg(q: Int) = q xor (q shr 1) xor 1

    /** CFAPattern of the raw IFD (2x2), RGGB when absent. */
    private fun cfaPattern(raw: TiffIfd): CfaPattern? {
        val dim = raw.ints(TiffTags.CFA_REPEAT_PATTERN_DIM, 2)
        val pat = raw.ints(TiffTags.CFA_PATTERN, 4) ?: return CfaPattern.RGGB
        if (dim != null && (dim.size != 2 || dim[0] != 2 || dim[1] != 2)) return null
        if (pat.size != 4 || pat.any { it !in 0..2 }) return null
        return CfaPattern(2, 2, pat).takeIf { it.isBayer }
    }

    // ------------------------------------------------------------------------- geometry

    private data class Visible(val width: Int, val height: Int, val forceBlackZero: Boolean)

    /** LibRaw's visible width / height for Sony (identify.cpp, open.cpp); margins are always 0. */
    private fun visibleArea(model: String, rawW: Int, rawH: Int): Visible {
        var w = rawW; var h = rawH; var black0 = false
        fun m(s: String) = model.equals(s, ignoreCase = true)
        when {
            m("ILCE-7RM5") || m("ILCE-7CR") || m("ILX-LR1") -> when (rawW) {
                6304 -> {}
                6656 -> { w = 6272; h = 4180 }
                9728 -> { w = 9566; h = 6374 }
                5120 -> { w = 4776; h = 3180 }
                9600 -> w = rawW - 36
                else -> w = rawW - 32
            }
            rawW == 3984 -> w = 3925
            rawW == 4288 -> w -= 32
            rawW == 4600 -> { if (m("DSLR-A350")) h -= 4; black0 = true }
            rawW == 4928 -> if (h < 3280) w -= 8
            rawW == 5504 -> w -= if (h > 3664) 8 else 32
            m("ILCE-9M3") -> when (rawW) {
                6048 -> w -= 36
                6144 -> { w = 6012; h = 4020 }
                4096 -> { w = 3944; h = 2644 }
                3072 -> { w = 3016; h = 2008 }
                3968 -> w = 3948
                else -> w = rawW - 32
            }
            rawW == 6048 -> { w -= 24; if (model.contains("RX1", true) || model.contains("A99", true)) w -= 6 }
            rawW == 7392 -> w -= 30
            rawW == 8000 -> w -= 32
            rawW == 9600 -> w -= 32
            m("ZV-E1") -> if (rawW == 4608 && rawH == 3072) { w = 4256; h = 2846 }
            m("ILCE-1") -> when {
                rawW == 8704 && rawH == 6144 -> { w = 8660; h = 5784 }
                rawW == 8672 -> w -= 12
                rawW == 6144 && rawH == 4096 -> { w = 5636; h = 3768 }
                rawW == 5664 -> w -= 28
                rawW == 5632 -> { w -= 4; h = 3756 }
                rawW == 4608 -> { w = 4332; h = 2892 }
            }
            m("ILCE-7M4") || m("ILCE-7CM2") || m("ILME-FX2") -> when {
                rawW == 7168 && rawH == 5120 -> { w = 7028; h = 4688 }
                rawW == 4736 -> w = 4692
                rawW == 5120 -> { w = 4624; h = 3080 }
                rawW == 3584 -> { w = 3516; h = 2344 }
                rawW == 7040 -> w -= 12
            }
            m("ILCE-6700") || m("ZV-E10M2") || m("ILME-FX30") -> if (rawW == 6656) { w = 6240; h = 4168 } else w = rawW - 32
        }
        // open.cpp: APS-C crop modes of full-frame bodies
        if (((model.startsWith("ILCE-7RM", true) || m("ILCA-99M2")) && (rawW == 5216 || rawW == 6304)) ||
            (m("ILCE-7R") && rawW in 4580 until 5020) ||
            (m("ILCE-7") && rawW == 3968) ||
            ((model.startsWith("ILCE-7M", true) || m("ILCE-9") || model.startsWith("SLT-A99", true)) && rawW in 3751 until 4120) ||
            (model.startsWith("ILCE-7S", true) && rawW == 2816)) w = rawW - 32
        return Visible(w, h, black0)
    }

    /** LibRaw raw_inset_crops[0]: DefaultCrop / 0x74c7-8 in the raw IFD, else maker note 0xb02b. */
    private fun insetCrop(tiff: Tiff, raw: TiffIfd): IntArray? {
        var l = 0; var t = 0; var w = 0; var h = 0
        raw.ints(0x74c7, 2)?.takeIf { it.size == 2 }?.let { l = it[0]; t = it[1] }
        raw.ints(0x74c8, 2)?.takeIf { it.size == 2 }?.let { w = it[0]; h = it[1] }
        raw.ints(TiffTags.DEFAULT_CROP_ORIGIN, 2)?.takeIf { it.size == 2 }?.let { l = it[0]; t = it[1] }
        raw.ints(TiffTags.DEFAULT_CROP_SIZE, 2)?.takeIf { it.size == 2 }?.let { w = it[0]; h = it[1] }
        if (w == 0) {
            val mn = runCatching { tiff.makerNote() }.getOrNull()
            mn?.ifd?.ints(0xb02b, 2)?.takeIf { it.size == 2 }?.let { h = it[0]; w = it[1] }
        }
        return if (w > 0 && h > 0) intArrayOf(l, t, w, h) else null
    }

    // ------------------------------------------------------------------------- SR2 private data

    /**
     * The decrypted SR2SubIFD: IFD0's DNGPrivateData holds the offset of the SR2Private IFD,
     * whose tags 0x7200 / 0x7201 / 0x7221 give the offset, length and key of an encrypted IFD.
     */
    private fun sr2SubIfd(tiff: Tiff): TiffIfd? {
        val e = tiff.ifd0[0xc634] ?: return null
        if (e.byteCount < 4) return null
        val b = tiff.bytes
        val privOff = tiff.base + Bytes.u32(b, e.dataOffset, tiff.littleEndian)
        if (privOff <= 0 || privOff >= b.size) return null
        val priv = TiffIfd.parse(b, privOff.toInt(), tiff.base, tiff.littleEndian) ?: return null
        // LibRaw reads each as get4() whatever the type (the key is UNDEFINED[4] in real files)
        fun u32(tag: Int): Long? = priv[tag]?.takeIf { it.byteCount >= 4 }?.let { Bytes.u32(b, it.dataOffset, priv.littleEndian) }
        val off = u32(0x7200) ?: return null
        val len = u32(0x7201) ?: return null
        val key = u32(0x7221) ?: return null
        if (len <= 0 || len >= 10_240_000 || off < 0 || off + len > b.size) return null
        val buf = b.copyOfRange(off.toInt(), (off + len).toInt())
        sonyDecrypt(buf, 0, (len / 4).toInt(), key.toInt())
        // value offsets inside are absolute file offsets
        return TiffIfd.parse(buf, 0, -off.toInt(), tiff.littleEndian)
    }

    /**
     * LibRaw `sony_decrypt` with start = 1: XORs [words] 32-bit words at [pos] with the key
     * stream; the pad words are applied in big-endian byte order.
     */
    internal fun sonyDecrypt(buf: ByteArray, pos: Int, words: Int, key: Int) {
        val pad = IntArray(128)
        var k = key
        for (p in 0 until 4) { k = k * 48828125 + 1; pad[p] = k }
        pad[3] = (pad[3] shl 1) or ((pad[0] xor pad[2]) ushr 31)
        for (p in 4 until 127) pad[p] = ((pad[p - 4] xor pad[p - 2]) shl 1) or ((pad[p - 3] xor pad[p - 1]) ushr 31)
        var p = 127
        var at = pos
        repeat(words) {
            val v = pad[(p + 1) and 127] xor pad[(p + 65) and 127]
            pad[p and 127] = v
            buf[at] = (buf[at].toInt() xor (v ushr 24)).toByte()
            buf[at + 1] = (buf[at + 1].toInt() xor (v ushr 16)).toByte()
            buf[at + 2] = (buf[at + 2].toInt() xor (v ushr 8)).toByte()
            buf[at + 3] = (buf[at + 3].toInt() xor v).toByte()
            at += 4; p++
        }
    }

    // ------------------------------------------------------------------------- ARW2

    /** Tag 0x7010: four 14-bit knee points -> 12-bit tone curve with slopes 1, 2, 4, 8, 16. */
    internal fun toneCurve(raw: TiffIfd): IntArray {
        val curve = IntArray(4096) { it }
        val knees = raw.ints(0x7010, 4)?.takeIf { it.size == 4 } ?: return curve
        val sc = intArrayOf(0, 0, 0, 0, 0, 4095)
        for (c in 0..3) sc[c + 1] = (knees[c] ushr 2) and 0xfff
        for (i in 0 until 5) for (j in sc[i] + 1..sc[i + 1]) curve[j] = (curve[j - 1] + (1 shl i)) and 0xffff
        return curve
    }

    /**
     * `sony_arw2_load_raw`: each row is rawW bytes of 16-byte blocks; a block holds 16 pixels of
     * one colour (every other column) as 11-bit max, min, their 4-bit positions and 14 7-bit
     * deltas shifted by the block's range. Rows from [height] on stay zero, like LibRaw.
     */
    internal fun decodeArw2(src: ByteArray, offset: Int, rawW: Int, rawH: Int, height: Int, curve: IntArray): ShortArray {
        val out = ShortArray(rawW * rawH)
        val row = ByteArray(rawW + 4)               // LibRaw reads up to 2 bytes past a block (zeros at the row end)
        val pix = IntArray(16)
        for (r in 0 until minOf(height, rawH)) {
            System.arraycopy(src, offset + r * rawW, row, 0, rawW)
            val base = r * rawW
            var col = 0
            var dp = 0
            while (col < rawW - 30) {
                val v = (row[dp].toInt() and 0xff) or ((row[dp + 1].toInt() and 0xff) shl 8) or
                    ((row[dp + 2].toInt() and 0xff) shl 16) or ((row[dp + 3].toInt() and 0xff) shl 24)
                val max = v and 0x7ff
                val min = (v ushr 11) and 0x7ff
                val imax = (v ushr 22) and 0x0f
                val imin = (v ushr 26) and 0x0f
                var sh = 0
                while (sh < 4 && (0x80 shl sh) <= max - min) sh++
                var bit = 30
                for (i in 0 until 16) {
                    pix[i] = when (i) {
                        imax -> max
                        imin -> min
                        else -> {
                            val q = dp + (bit ushr 3)
                            val w = (row[q].toInt() and 0xff) or ((row[q + 1].toInt() and 0xff) shl 8)
                            val delta = (w ushr (bit and 7)) and 0x7f
                            bit += 7
                            minOf((delta shl sh) + min, 0x7ff)
                        }
                    }
                }
                for (i in 0 until 16) { out[base + col] = curve[pix[i] shl 1].toShort(); col += 2 }
                col -= if (col and 1 != 0) 1 else 31
                dp += 16
            }
        }
        return out
    }

    // ------------------------------------------------------------------------- lossless JPEG

    /**
     * `sony_ljpeg_load_raw`: tiles (or one strip) of lossless JPEG with 4 components at half
     * resolution; component k of sample (x, y) is raw pixel (2y + k / 2, 2x + k % 2) of the tile.
     */
    private fun decodeLjpeg(bytes: ByteArray, raw: TiffIfd, rawW: Int, rawH: Int, tiled: Boolean, le: Boolean): ShortArray? {
        val tw: Int; val th: Int
        val offsets: LongArray; val counts: LongArray
        if (tiled) {
            tw = raw.int(TiffTags.TILE_WIDTH) ?: return null
            th = raw.int(TiffTags.TILE_LENGTH) ?: return null
            if (tw < 2 || th < 2 || tw % 2 != 0 || th % 2 != 0) return null
            val n = ((rawW.toLong() + tw - 1) / tw) * ((rawH.toLong() + th - 1) / th)
            if (n !in 1..RawDecodeLimits.MAX_SEGMENTS.toLong()) return null
            offsets = raw.longs(TiffTags.TILE_OFFSETS, RawDecodeLimits.MAX_SEGMENTS) ?: return null
            counts = raw.longs(TiffTags.TILE_BYTE_COUNTS, RawDecodeLimits.MAX_SEGMENTS) ?: return null
            if (offsets.size.toLong() != n || counts.size != offsets.size) return null
        } else {
            tw = rawW; th = rawH
            offsets = raw.longs(TiffTags.STRIP_OFFSETS, 2) ?: return null
            counts = raw.longs(TiffTags.STRIP_BYTE_COUNTS, 2) ?: return null
            if (offsets.size != 1 || counts.size != 1) return null
        }
        // preflight every tile before allocating the frame
        val headers = Array(offsets.size) { i ->
            val off = offsets[i]; val len = counts[i]
            if (off <= 0 || len <= 0 || off + len > bytes.size) return null
            val h = LosslessJpeg.readHeader(bytes, off.toInt(), len.toInt()) ?: return null
            if (h.components != 4 || h.width.toLong() * 2 > tw + 1L || h.height.toLong() * 2 > th + 1L) return null
            h
        }
        val out = ShortArray(rawW * rawH)
        val across = (rawW + tw - 1) / tw
        for (i in offsets.indices) {
            val trow = (i / across) * th
            val tcol = (i % across) * tw
            val wide = headers[i].width
            LosslessJpeg.decode(bytes, offsets[i].toInt(), counts[i].toInt()) { jrow, s ->
                val row = 2 * jrow
                if (trow + row < rawH - 1) {
                    val o = (trow + row) * rawW + tcol
                    var jcol = 0
                    var col = 0
                    while (jcol < wide && tcol + col < rawW - 1) {
                        out[o + col] = s[jcol * 4].toShort()
                        out[o + col + 1] = s[jcol * 4 + 1].toShort()
                        out[o + rawW + col] = s[jcol * 4 + 2].toShort()
                        out[o + rawW + col + 1] = s[jcol * 4 + 3].toShort()
                        jcol++; col += 2
                    }
                }
            } ?: return null
        }
        return out
    }
}
