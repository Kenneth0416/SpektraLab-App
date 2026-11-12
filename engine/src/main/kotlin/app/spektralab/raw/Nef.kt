// Nikon NEF / NRW reader.
//
// Ported from LibRaw 0.22.2 (Copyright 2008-2025 LibRaw LLC, LGPL 2.1 / CDDL 1.0, derived from dcraw.c by
// Dave Coffin): src/decoders/decoders_dcraw.cpp (nikon_load_raw, nikon_read_curve),
// src/decoders/generic.cpp (packed_load_raw, unpacked_load_raw), src/decoders/decoders_libraw.cpp
// (nikon_load_padded_packed_raw, nikon_load_striped_packed_raw, nikon_14bit_load_raw),
// src/metadata/tiff.cpp (loader selection for compression 34713), src/metadata/nikon.cpp
// (parseNikonMakernote: WB, colour-balance decryption tables, black level, inset crop),
// src/metadata/identify.cpp and src/utils/open.cpp (per-model margins, 12-bit black/white adjustment).
// This project is GPLv3; LibRaw's LGPL 2.1 licence permits redistribution under the GPL.
package app.spektralab.raw

/**
 * Nikon NEF (and Coolpix NRW) raw files: the Huffman-coded "NEF compressed" data (lossless and
 * lossy type 1 / type 2, 12 and 14 bit, with the linearisation curve and the split row of
 * lossy files), 12-bit packed (MSB, little-endian, 16-byte padded rows, 32-bit striped),
 * 14-bit packed (Z series / D6) and 16-bit unpacked data, in either byte order.
 *
 * Returns null for non-Nikon files, DNGs, High-Efficiency (TicoRAW / JPEG XS) NEFs, sRAW (YUV)
 * NEFs, 3-colour "NEFX" frames, non-RGB CFAs (old Coolpix CYGM sensors), the old Coolpix
 * "E" models (interlaced / diag hacks), D1 / D1X / D100, cameras without a LibRaw colour
 * matrix and anything truncated or malformed.
 *
 * Levels, WB, margins and crop follow LibRaw: black from maker-note tag 0x3d (divided by 4 for
 * 12-bit data), white = 2^bps - 1 (or the LibRaw table value), WB from tag 0x0c / the NRW block
 * of 0x14 / the plain or encrypted ColorBalance block 0x97, crop = maker-note tag 0x45 inset crop
 * when present, else LibRaw's visible area.
 */
object NefReader {
    fun read(bytes: ByteArray): RawImage? = try { parse(bytes) } catch (e: RuntimeException) { null }

    private enum class Loader { NIKON, PACKED, UNPACKED, PADDED, STRIPED, NIKON14 }

    private fun parse(bytes: ByteArray): RawImage? {
        if (bytes.size < 64) return null
        val tiff = Tiff.parse(bytes, 0, setOf(42)) ?: return null
        val ifd0 = tiff.ifd0
        val make = ifd0.string(TiffTags.MAKE) ?: return null
        if (!make.startsWith("NIKON", ignoreCase = true)) return null
        if (TiffTags.DNG_VERSION in ifd0) return null
        val fullModel = ifd0.string(TiffTags.MODEL) ?: return null
        val software = ifd0.string(305) ?: ""
        if (software.startsWith("Nikon Scan") || fullModel.contains("COOLSCAN", ignoreCase = true)) return null
        // LibRaw strips the maker name from the model before its per-model tables
        val model = fullModel.let { if (it.startsWith("NIKON ", ignoreCase = true)) it.substring(6).trim() else it }
        if (model.startsWith("E") || model == "D1" || model == "D1X" || model == "D100") return null

        val ifds = tiff.allIfds()
        // LibRaw picks the IFD with the most pixel bits
        val raw = ifds.filter {
            it.int(TiffTags.PHOTOMETRIC) == 32803 && it.int(TiffTags.SAMPLES_PER_PIXEL, 1) == 1 &&
                it.int(TiffTags.COMPRESSION, 1) in setOf(1, 34713)
        }.maxByOrNull { (it.int(TiffTags.IMAGE_WIDTH, 0).toLong() * it.int(TiffTags.IMAGE_LENGTH, 0)) * it.int(TiffTags.BITS_PER_SAMPLE, 0) }
            ?: return null
        val rawWidth = raw.int(TiffTags.IMAGE_WIDTH) ?: return null
        val rawHeight = raw.int(TiffTags.IMAGE_LENGTH) ?: return null
        var bps = raw.int(TiffTags.BITS_PER_SAMPLE) ?: return null
        val compression = raw.int(TiffTags.COMPRESSION, 1)
        if (rawWidth !in 22..64000 || rawHeight !in 22..64000) return null
        if (rawWidth.toLong() * rawHeight > RawDecodeLimits.MAX_SAMPLES) return null
        val stripOffsets = raw.longs(TiffTags.STRIP_OFFSETS, RawDecodeLimits.MAX_SEGMENTS) ?: return null
        val stripCounts = raw.longs(TiffTags.STRIP_BYTE_COUNTS, RawDecodeLimits.MAX_SEGMENTS) ?: return null
        val dataOffset = raw.base + stripOffsets[0]
        if (dataOffset !in 0 until bytes.size) return null
        val dataSize = stripCounts[0]                       // LibRaw: tiff_ifd.bytes = first strip
        val rowsPerStrip = raw.longs(TiffTags.ROWS_PER_STRIP, 1)?.get(0) ?: 0L
        val rw = rawWidth.toLong(); val rh = rawHeight.toLong()

        // maker note
        val mn = parseMakerNote(tiff, fullModel) ?: return null
        if (mn.nefCompression == 13 || mn.nefCompression == 14) return null   // High Efficiency

        // ---------------------------------------------------- loader selection (tiff.cpp)
        var loadFlags = 0
        var bigEndianWords = tiff.littleEndian.not()
        val loader: Loader = when (compression) {
            34713 -> when {
                (rw + 9) / 10 * 16 * rh == dataSize -> { loadFlags = 1; Loader.PACKED }
                rw * rh * 3 == dataSize * 2 -> { if (fullModel.startsWith("N")) loadFlags = 80; Loader.PACKED }
                rw * rh * 3 == dataSize -> return null                                 // YUV sRAW
                rw * rh * 2 == dataSize -> { loadFlags = 4; bigEndianWords = true; Loader.UNPACKED }
                rowsPerStrip > 0 && stripOffsets.size > 1 && stripOffsets.size == stripCounts.size -> {
                    val fit = (0 until stripCounts.size - 1).all { stripCounts[it] * 2 == rowsPerStrip * rw * 3 }
                    if (fit) Loader.STRIPED else Loader.NIKON
                }
                (rw * 3 / 2 + 15) / 16 * 16 * rh == dataSize -> Loader.PADDED
                else -> {
                    // High-Efficiency NEF: JPEG XS codestream (SOC + CAP markers)
                    if (isJpegXs(bytes, dataOffset.toInt())) return null
                    Loader.NIKON
                }
            }
            1 -> {
                if (dataSize * 5 == rw * rh * 8) { loadFlags = 81; bps = 12 }
                when (bps) {
                    12 -> {
                        if (raw.int(TiffTags.PHOTOMETRIC) == 2) return null
                        if (model.startsWith("COOLPIX A1000", ignoreCase = true) && dataSize == rw * rh * 2) Loader.UNPACKED else Loader.PACKED
                    }
                    14 -> { loadFlags = 0; Loader.UNPACKED }
                    16 -> Loader.UNPACKED
                    else -> return null
                }
            }
            else -> return null
        }
        var loaderSel = loader

        // ---------------------------------------------------- per-model adjustments (identify.cpp)
        var width = rawWidth; var height = rawHeight; var leftMargin = 0; val topMargin = 0
        var forcedFilters = 0
        var maximum = 0
        var blackOverride = -1
        when {
            model == "D40X" || model == "D60" || model == "D80" || model == "D3000" -> { height -= 3; width -= 4 }
            model == "D3" || model == "D3S" || model == "D700" -> { width -= 4; leftMargin = 2 }
            model == "D3100" -> { width -= 28; leftMargin = 6 }
            model == "D5000" || model == "D90" -> width -= 42
            model == "D5100" || model == "D7000" || model == "COOLPIX A" -> width -= 44
            model == "D3200" || model == "D600" || model == "D610" || model.startsWith("D800") -> width -= 46
            model == "D4" || model == "Df" -> { width -= 52; leftMargin = 2 }
            model == "D500" -> {}
            model.startsWith("D40") || model.startsWith("D50") || model.startsWith("D70") -> width--
            model == "D200" -> { leftMargin = 1; width -= 4; forcedFilters = 0x94949494.toInt() }
            model.startsWith("D2H") -> { leftMargin = 6; width -= 14 }
            model.startsWith("D2X") -> if (width == 3264) width -= 32 else width -= 8
            model.startsWith("D300") -> width -= 32
            rawWidth == 4032 -> {
                if (model.startsWith("COOLPIX P7700") || model.startsWith("COOLPIX P7800")) { maximum = 65504; loadFlags = 0 }
                else if (model == "COOLPIX P340") loadFlags = 0
            }
            model.startsWith("COOLPIX P") -> {
                loadFlags = 24; forcedFilters = 0x94949494.toInt()
                // LibRaw: P7000 / P7100 at high ISO (firmware other than 1.2) use black 255
                val iso = tiff.exif?.int(34855)?.takeIf { it > 0 } ?: mn.iso
                if (model.length > 9 && model[9] == '7' && (iso >= 400 || iso == 0) && !software.contains("V1.2")) blackOverride = 255
            }
            model.startsWith("COOLPIX B700") -> loadFlags = 24
            model.startsWith("1 ") -> height -= 2
        }
        if (width <= 0 || height <= 0) return null
        if (loadFlags and 2 != 0) return null          // interlaced rows: only old Coolpix models

        // ---------------------------------------------------- open.cpp adjustments
        if ((model.startsWith("Z") || model.equals("D6", ignoreCase = true))) {
            val line14 = Math.ceil((rawWidth * 7 / 4).toFloat() / 16.0).toLong() * 16
            if (line14 * rh == dataSize) loaderSel = Loader.NIKON14
            val line14c = Math.ceil((rawWidth * 21 / 4).toFloat() / 16.0) * 16 * rh
            if (line14c == dataSize.toDouble()) return null           // 3-colour NEFX frame
        }
        if (loaderSel == Loader.PACKED && loadFlags == 0 && (model.startsWith("D810") || model.equals("D4S", ignoreCase = true)) &&
            dataSize * 2 == rh * rw * 3) loadFlags = 80

        // ---------------------------------------------------- CFA
        val cfaVisible: CfaPattern = if (forcedFilters != 0) CfaPattern.fromDcrawFilters(forcedFilters) else {
            val dim = raw.ints(TiffTags.CFA_REPEAT_PATTERN_DIM)
            if (dim != null && (dim.size != 2 || dim[0] != 2 || dim[1] != 2)) return null
            val pat = raw.ints(TiffTags.CFA_PATTERN)?.takeIf { it.size == 4 } ?: return null
            if (pat.any { it !in 0..2 }) return null
            CfaPattern(2, 2, pat)
        }
        if (!cfaVisible.isBayer) return null
        val cfa = cfaVisible.anchoredAt(topMargin, leftMargin)

        // ---------------------------------------------------- pixels
        // the file must hold at least the data the loader reads before the frame is allocated
        // (Huffman: every code is at least 2 bits long)
        val available = bytes.size - dataOffset
        val minBytes: Long = when (loaderSel) {
            Loader.NIKON -> rw * height / 4
            Loader.PACKED, Loader.STRIPED -> rw * rh * bps / 8
            Loader.UNPACKED -> rw * rh * 2
            Loader.PADDED -> (rw * 3 / 2 + 15) / 16 * 16 * rh
            Loader.NIKON14 -> dataSize
        }
        if (loaderSel == Loader.STRIPED) {
            var sum = 0L
            for (i in stripOffsets.indices) {
                val o = raw.base + stripOffsets[i]; val n = stripCounts.getOrElse(i) { 0L }
                if (o < 0 || o + n > bytes.size) return null
                sum += n
            }
            if (sum < minBytes) return null
        } else if (available < minBytes) return null
        val data = ShortArray(rawWidth * rawHeight)
        val ok = when (loaderSel) {
            Loader.NIKON -> {
                if (bps != 12 && bps != 14) return null
                val meta = mn.metaOffset.takeIf { it > 0 } ?: return null
                val curve = readCurve(bytes, meta, bps, mn.littleEndian) ?: return null
                nikonHuffman(bytes, dataOffset.toInt(), meta, bps, mn.littleEndian, curve, rawWidth, height, data)
            }
            Loader.PACKED -> packed(bytes, dataOffset.toInt(), rawWidth, rawHeight, bps, loadFlags, data)
            Loader.UNPACKED -> unpacked(bytes, dataOffset.toInt(), rawWidth, rawHeight, loadFlags, !bigEndianWords, data)
            Loader.PADDED -> padded(bytes, dataOffset.toInt(), rawWidth, rawHeight, data)
            Loader.STRIPED -> striped(bytes, raw.base, stripOffsets, rowsPerStrip.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), rawWidth, rawHeight, bps, data)
            Loader.NIKON14 -> nikon14(bytes, dataOffset.toInt(), rawWidth, rawHeight, data)
        }
        if (!ok) return null

        // ---------------------------------------------------- levels
        val entry = CameraMatrices.find(make, fullModel) ?: return null
        val xyz = entry.xyzToCamera?.takeIf { entry.colors == 3 } ?: return null
        var black = if (blackOverride >= 0) blackOverride else mn.black
        val cblack = mn.cblack.copyOf()      // R, G, B, G2 (LibRaw RGBG order)
        val rblack = black + cblack.sum() / 4
        if (entry.black > 0) { black = entry.black; cblack.fill(0) }
        else if (entry.black < 0 && rblack == 0) { black = -entry.black; cblack.fill(0) }
        if (entry.white != 0) maximum = entry.white
        if (maximum == 0) maximum = (1 shl bps) - 1
        if (maximum > 0xffff) maximum = 0xffff
        if ((loaderSel == Loader.NIKON || loaderSel == Loader.PACKED || loaderSel == Loader.PADDED) &&
            !model.startsWith("COOLPIX") && bps == 12) {
            maximum = 4095
            black /= 4
            for (c in 0 until 4) cblack[c] /= 4
        }
        // per CFA position: colour 3 (G2) is the green on the blue row
        val blackPos = FloatArray(4) { i ->
            val r = i / 2; val c = i % 2
            val col = cfa.color(r, c)
            val ch = if (col == 1 && (cfa.color(r, 0) == 2 || cfa.color(r, 1) == 2)) 3 else col
            (black + cblack[ch]).toFloat()
        }
        if (maximum <= blackPos.max()) return null

        // ---------------------------------------------------- crop
        val ic = mn.insetCrop
        val (cx, cy, cw, ch) = if (ic != null && ic[2] > 0 && ic[3] > 0 && ic[0] + ic[2] <= rawWidth && ic[1] + ic[3] <= rawHeight)
            ic.toList() else listOf(leftMargin, topMargin, width, height)

        // ---------------------------------------------------- colour
        val m = mn.camMul
        val asShot = if (m[0] > 0 && m[1] > 0 && m[2] > 0)
            doubleArrayOf(m[0], if (m[3] > 0) (m[1] + m[3]) / 2 else m[1], m[2]) else null
        val colour = RawColor.fromXyzToCamera(DoubleArray(9) { xyz[it / 3][it % 3] }, asShot) ?: return null

        return RawImage(rawWidth, rawHeight, data, cfa, blackPos, maximum.toFloat(), cx, cy, cw, ch,
            colour.wb, colour.camToSrgb, orientation(ifds), make = make, model = fullModel)
    }

    /** JPEG XS SOC + CAP markers: the High-Efficiency (TicoRAW) NEF codestream. */
    private fun isJpegXs(b: ByteArray, p: Int) = p >= 0 && p + 4 <= b.size &&
        (b[p].toInt() and 0xff) == 0xff && b[p + 1].toInt() == 0x10 && (b[p + 2].toInt() and 0xff) == 0xff && b[p + 3].toInt() == 0x50

    /** EXIF orientation as LibRaw sees it: the first IFD whose Orientation maps to a non-zero flip. */
    private fun orientation(ifds: List<TiffIfd>): Int {
        for (ifd in ifds) {
            val v = ifd.int(TiffTags.ORIENTATION) ?: continue
            val flip = "50132467"[v and 7] - '0'
            if (flip != 0) return intArrayOf(1, 2, 4, 3, 5, 8, 6, 7)[flip]
        }
        return 1
    }

    // ================================================================= maker note
    internal class MakerNoteInfo(
        val littleEndian: Boolean,
        val camMul: DoubleArray,           // R, G, B, G2
        val black: Int,
        val cblack: IntArray,              // R, G, B, G2
        val insetCrop: IntArray?,          // left, top, width, height
        val metaOffset: Int,
        val nefCompression: Int,
        val colorBalanceVersion: Int,
        /** ISO from maker-note tag 0x0002 (used when EXIF has none). */
        val iso: Int = 0,
    )

    internal fun parseMakerNote(tiff: Tiff, fullModel: String): MakerNoteInfo? {
        val note = tiff.makerNote()
        // LibRaw ignores the type-1 ("Nikon\0\1") maker note; a bare IFD is parsed as is
        val ifd = note?.takeIf { it.kind == "nikon" || it.kind == "plain" }?.ifd
            ?: return MakerNoteInfo(tiff.littleEndian, DoubleArray(4), 0, IntArray(4), null, 0, 0, 0)
        val b = tiff.bytes
        val le = ifd.littleEndian
        fun u16(p: Int) = Bytes.u16(b, p, le)
        fun u32(p: Int) = Bytes.u32(b, p, le)
        fun inFile(p: Int, n: Int) = p >= 0 && p.toLong() + n <= b.size

        val camMul = DoubleArray(4)
        var black = 0
        val cblack = IntArray(4)
        var inset: IntArray? = null
        var meta = 0
        var nefCompression = 0

        ifd[0x000c]?.let { e -> if (e.count == 4 && e.type == 5) {
            camMul[0] = ifd.value(e, 0); camMul[2] = ifd.value(e, 1); camMul[1] = ifd.value(e, 2); camMul[3] = ifd.value(e, 3)
        } }
        ifd[0x0014]?.let { e -> if (e.type == 7 && e.count > 0) {
            val p = e.dataOffset
            if (e.count == 1280) camMul.fill(1.0)
            else if (e.count != 2560 && inFile(p, 10) && Bytes.matches(b, p, "NRW ")) {
                if (Bytes.matches(b, p + 4, "0100")) {
                    val q = p + 10 + 0x13de
                    if (inFile(q, 16)) {
                        camMul[0] = (u32(q) shl 1).toDouble(); camMul[1] = u32(q + 4).toDouble()
                        camMul[3] = u32(q + 8).toDouble(); camMul[2] = (u32(q + 12) shl 1).toDouble()
                    }
                } else {
                    val q = p + 10 + 0x16
                    if (inFile(q, 2)) black = u16(q)
                    val w = q + 2 + 0x16
                    if (camMul[0] < 0.1 && inFile(w, 16)) {
                        camMul[0] = (u32(w) shl 1).toDouble(); camMul[1] = u32(w + 4).toDouble()
                        camMul[3] = u32(w + 8).toDouble(); camMul[2] = (u32(w + 12) shl 1).toDouble()
                    }
                }
            }
        } }
        // serial number (0x1d), as LibRaw folds it into a decryption key
        var serial = 0
        var customSerial = 0
        ifd[0x001d]?.let { e ->
            var len = e.byteCount
            var p = e.dataOffset
            val ml = fullModel.length
            while (p < b.size) {
                val c = b[p++].toInt() and 0xff
                if (c == 0 || len-- <= 0) break
                val digit = c in '0'.code..'9'.code
                if (customSerial == 0 && !digit) {
                    customSerial = if ((ml == 3 && fullModel == "D50") ||
                        (ml >= 4 && !fullModel[ml - 4].isLetterOrDigit() && fullModel.endsWith("D50"))) 34 else 96
                    break
                }
                serial = serial * 10 + if (digit) c - '0'.code else c % 10
            }
        }
        ifd[0x003d]?.let { e -> if (e.count >= 4 && e.type == 3) {
            val v = IntArray(4) { ifd.value(e, it).toInt() }
            // RGGB -> RGBG
            cblack[0] = v[0]; cblack[1] = v[1]; cblack[3] = v[2]; cblack[2] = v[3]
            val mn = cblack.min()
            for (c in 0 until 4) cblack[c] -= mn
            black += mn
        } }
        ifd[0x0045]?.let { e -> if (e.count >= 4 && e.type == 3) inset = IntArray(4) { ifd.value(e, it).toInt() } }
        ifd[0x008c]?.let { meta = it.dataOffset }
        ifd[0x0096]?.let { meta = it.dataOffset }
        ifd[0x0093]?.let { e -> if (e.count > 0) nefCompression = ifd.value(e, 0).toInt() }

        var cbVersion = 0
        var cbBuf: ByteArray? = null
        ifd[0x0097]?.let { e ->
            val p = e.dataOffset
            if (!inFile(p, 4)) return@let
            for (i in 0 until 4) cbVersion = cbVersion * 10 + (b[p + i].toInt() and 0xff) - '0'.code
            when (cbVersion) {
                100 -> if (inFile(p + 4 + 0x44, 8)) for (c in 0 until 4) camMul[(c shr 1) or ((c and 1) shl 1)] = u16(p + 4 + 0x44 + 2 * c).toDouble()
                102 -> if (inFile(p + 4 + 0x6, 8)) for (c in 0 until 4) camMul[c xor (c shr 1)] = u16(p + 4 + 0x6 + 2 * c).toDouble()
                103 -> if (inFile(p + 4 + 0x10, 8)) for (c in 0 until 4) camMul[c] = u16(p + 4 + 0x10 + 2 * c).toDouble()
            }
            if (cbVersion >= 200) {
                val q = p + 4 + if (cbVersion != 205) 0x118 else 0
                if (inFile(q, 324)) cbBuf = b.copyOfRange(q, q + 324)
            }
        }
        ifd[0x00a7]?.let { e ->
            val p = e.dataOffset
            if (!inFile(p, 4)) return@let
            val key = (b[p].toInt() xor b[p + 1].toInt() xor b[p + 2].toInt() xor b[p + 3].toInt()) and 0xff
            val buf = cbBuf
            if (cbVersion - 200 in 0 until 18 && buf != null) {
                val ci = XLAT0[if (customSerial != 0) customSerial else serial and 0xff].toInt() and 0xff
                var cj = XLAT1[key].toInt() and 0xff
                var ck = 0x60
                for (i in 0 until 324) {
                    cj = (cj + ci * ck) and 0xff
                    ck = (ck + 1) and 0xff
                    buf[i] = (buf[i].toInt() xor cj).toByte()
                }
                val i = "66666>666;6A;:;555"[cbVersion - 200] - '0'
                for (c in 0 until 4) camMul[c xor (c shr 1) xor (i and 1)] = Bytes.u16(buf, (i and -2) + c * 2, le).toDouble()
            }
        }
        val iso = ifd[0x0002]?.takeIf { it.count >= 2 && it.type == 3 }?.let { ifd.value(it, 1).toInt() } ?: 0
        return MakerNoteInfo(le, camMul, black, cblack, inset, meta, nefCompression, cbVersion, iso)
    }

    // ================================================================= decoders
    /** nikon_read_curve: the linearisation curve (identity for lossless files). */
    internal fun readCurve(b: ByteArray, meta: Int, bps: Int, le: Boolean): IntArray? {
        val curve = IntArray(0x10000) { it }
        if (meta < 0 || meta + 2 > b.size) return null
        val ver0 = b[meta].toInt() and 0xff
        val ver1 = b[meta + 1].toInt() and 0xff
        var p = meta + 2
        if (ver0 == 0x49 || ver1 == 0x58) p += 2110
        p += 8                                            // vpred
        if (p + 2 > b.size) return null
        var max = (1 shl bps) and 0x7fff
        var step = max
        val csize = Bytes.u16(b, p, le); p += 2
        if (csize > 1) step = max / (csize - 1)
        if (ver0 == 0x44 && (ver1 == 0x20 || (ver1 == 0x40 && step > 3)) && step > 0) {
            if (ver1 == 0x40) { step /= 4; max /= 4 }
            if (p.toLong() + 2L * csize > b.size) return null
            for (i in 0 until csize) curve[i * step] = Bytes.u16(b, p + 2 * i, le)
            for (i in 0 until max) {
                val r = i % step
                curve[i] = (curve[i - r] * (step - r) + curve[i - r + step] * r) / step
            }
        } else if (ver0 != 0x46 && csize <= 0x4001) {
            if (p.toLong() + 2L * csize > b.size) return null
            for (i in 0 until csize) curve[i] = Bytes.u16(b, p + 2 * i, le)
        }
        return curve
    }

    private val TREES: Array<IntArray> = arrayOf(
        intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, 0, 5, 4, 3, 6, 2, 7, 1, 0, 8, 9, 11, 10, 12),          // 12-bit lossy
        intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, 0, 0x39, 0x5a, 0x38, 0x27, 0x16, 5, 4, 3, 2, 1, 0, 11, 12, 12), // after split
        intArrayOf(0, 1, 4, 2, 3, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 5, 4, 6, 3, 7, 2, 8, 1, 9, 0, 10, 11, 12),          // 12-bit lossless
        intArrayOf(0, 1, 4, 3, 1, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, 0, 5, 6, 4, 7, 8, 3, 9, 2, 1, 0, 10, 11, 12, 13, 14),  // 14-bit lossy
        intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, 8, 0x5c, 0x4b, 0x3a, 0x29, 7, 6, 5, 4, 3, 2, 1, 0, 13, 14), // after split
        intArrayOf(0, 1, 4, 2, 2, 3, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 7, 6, 8, 5, 9, 4, 10, 3, 11, 12, 2, 0, 1, 13, 14),  // 14-bit lossless
    ).map { t -> IntArray(32).also { t.copyInto(it) } }.toTypedArray()

    /** nikon_load_raw: Huffman-coded differences, two vertical predictors per row parity. */
    internal fun nikonHuffman(b: ByteArray, dataOffset: Int, meta: Int, bps: Int, le: Boolean, curve: IntArray,
                              rawWidth: Int, height: Int, out: ShortArray): Boolean {
        val ver0 = b[meta].toInt() and 0xff
        val ver1 = b[meta + 1].toInt() and 0xff
        var p = meta + 2
        if (ver0 == 0x49 || ver1 == 0x58) p += 2110
        var tree = if (ver0 == 0x46) 2 else 0
        if (bps == 14) tree += 3
        if (p + 8 > b.size) return false
        val vpred = IntArray(4) { Bytes.u16(b, p + 2 * it, le) }   // [row & 1][col]
        var split = 0
        if (ver0 == 0x44 && (ver1 == 0x20 || ver1 == 0x40)) {
            if (meta + 564 > b.size) return false
            split = Bytes.u16(b, meta + 562, le)
        }
        var huff = HuffmanTable.fromDcraw(TREES[tree]) ?: return false
        val pump = MsbBitPump(b, dataOffset)
        var h0 = 0; var h1 = 0
        for (row in 0 until height) {
            if (split != 0 && row == split) huff = HuffmanTable.fromDcraw(TREES[tree + 1]) ?: return false
            val base = row * rawWidth
            val vp = (row and 1) * 2
            for (col in 0 until rawWidth) {
                var i = huff.decodeSymbol(pump)
                if (i < 0) i = 0
                val len = i and 15
                val shl = i ushr 4
                var diff = ((pump.bits((len - shl).coerceAtLeast(0)) shl 1) + 1) shl shl ushr 1
                if (len > 0 && (diff and (1 shl (len - 1))) == 0) diff -= (1 shl len) - (if (shl == 0) 1 else 0)
                val v: Int
                if (col < 2) {
                    v = (vpred[vp + col] + diff) and 0xffff
                    vpred[vp + col] = v
                    if (col == 0) h0 = v else h1 = v
                } else if (col and 1 == 0) { h0 = (h0 + diff) and 0xffff; v = h0 }
                else { h1 = (h1 + diff) and 0xffff; v = h1 }
                out[base + col] = curve[v.toShort().toInt().coerceIn(0, 0x3fff)].toShort()
            }
            if (pump.consumedPadding) return false      // truncated data
        }
        return true
    }

    /** packed_load_raw (load_flags: 1 = pad byte per 10 px, 24 = word size, 64 = swap pairs, 128 = round row). */
    internal fun packed(b: ByteArray, offset: Int, rawWidth: Int, rawHeight: Int, bps: Int, loadFlags: Int, out: ShortArray): Boolean {
        if (bps !in 1..16) return false
        var bwide = (rawWidth.toLong() * bps / 8).toInt()
        bwide += bwide and (loadFlags ushr 7)
        val rbits = bwide * 8 - rawWidth * bps
        val padEvery10 = loadFlags and 1 != 0
        // input bounds: the bytes the loop below consumes
        val need = (rawWidth.toLong() * bps + rbits.coerceAtLeast(0)) * rawHeight / 8 + (if (padEvery10) rawWidth / 10L * rawHeight else 0)
        if (offset + need > b.size + 8) return false
        val bite = 8 + (loadFlags and 24)
        val swap = (loadFlags ushr 6) and 1
        var pos = offset
        var vbits = 0
        var bitbuf = 0L
        val n = b.size
        for (row in 0 until rawHeight) {
            val base = row * rawWidth
            for (col in 0 until rawWidth) {
                vbits -= bps
                while (vbits < 0) {
                    bitbuf = bitbuf shl bite
                    var i = 0
                    while (i < bite) { if (pos < n) bitbuf = bitbuf or ((b[pos].toLong() and 0xff) shl i); pos++; i += 8 }
                    vbits += bite
                }
                val v = ((bitbuf shl (64 - bps - vbits)) ushr (64 - bps)).toInt()
                val idx = base + (col xor swap)
                if (idx < out.size) out[idx] = v.toShort()
                if (padEvery10 && col % 10 == 9) pos++
            }
            vbits -= rbits
        }
        return pos <= n
    }

    /** unpacked_load_raw: 16-bit words, shifted right by [shift]. */
    internal fun unpacked(b: ByteArray, offset: Int, rawWidth: Int, rawHeight: Int, shift: Int, littleEndian: Boolean, out: ShortArray): Boolean {
        val count = rawWidth * rawHeight
        if (offset.toLong() + 2L * count > b.size) return false
        var p = offset
        for (i in 0 until count) { out[i] = (Bytes.u16(b, p, littleEndian) ushr shift).toShort(); p += 2 }
        return true
    }

    /** nikon_load_padded_packed_raw: 12-bit little-endian pairs, rows padded to 16 bytes. */
    internal fun padded(b: ByteArray, offset: Int, rawWidth: Int, rawHeight: Int, out: ShortArray): Boolean {
        val bpr = ((rawWidth * 3 / 2) + 15) / 16 * 16
        if (bpr < 2000 || bpr > 64000) return false
        if (offset.toLong() + bpr.toLong() * rawHeight > b.size) return false
        for (row in 0 until rawHeight) {
            val s = offset + row * bpr
            val d = row * rawWidth
            for (ic in 0 until rawWidth / 2) {
                val b0 = b[s + ic * 3].toInt() and 0xff; val b1 = b[s + ic * 3 + 1].toInt() and 0xff; val b2 = b[s + ic * 3 + 2].toInt() and 0xff
                out[d + ic * 2] = (((b1 and 0xf) shl 8) or b0).toShort()
                out[d + ic * 2 + 1] = ((b2 shl 4) or (b1 ushr 4)).toShort()
            }
        }
        return true
    }

    /** nikon_load_striped_packed_raw: packed MSB data read through 32-bit little-endian words, per strip. */
    internal fun striped(b: ByteArray, base: Int, offsets: LongArray, rowsPerStrip: Int, rawWidth: Int, rawHeight: Int, bps: Int, out: ShortArray): Boolean {
        if (rowsPerStrip <= 0 || bps !in 1..16) return false
        val bwide = rawWidth * bps / 8
        val rbits = bwide * 8 - rawWidth * bps
        val bite = 32
        var vbits = 0
        var bitbuf = 0L
        var pos = 0
        var strip = 0
        val n = b.size
        for (row in 0 until rawHeight) {
            if (row % rowsPerStrip == 0) {
                if (strip >= offsets.size) return false
                val o = base + offsets[strip++]
                if (o !in 0 until n) return false
                pos = o.toInt()
            }
            val d = row * rawWidth
            for (col in 0 until rawWidth) {
                vbits -= bps
                while (vbits < 0) {
                    bitbuf = bitbuf shl bite
                    var i = 0
                    while (i < bite) { if (pos < n) bitbuf = bitbuf or ((b[pos].toLong() and 0xff) shl i); pos++; i += 8 }
                    vbits += bite
                }
                out[d + col] = ((bitbuf shl (64 - bps - vbits)) ushr (64 - bps)).toShort()
            }
            vbits -= rbits
            if (pos > n + 4) return false
        }
        return true
    }

    /** nikon_14bit_load_raw: 4 pixels in 7 bytes (little-endian bit order), rows padded to 16 bytes. */
    internal fun nikon14(b: ByteArray, offset: Int, rawWidth: Int, rawHeight: Int, out: ShortArray): Boolean {
        val linelen = (Math.ceil((rawWidth * 7 / 4).toFloat() / 16.0).toInt()) * 16
        if (offset.toLong() + linelen.toLong() * rawHeight > b.size) return false
        for (row in 0 until rawHeight) {
            val s0 = offset + row * linelen
            val d0 = row * rawWidth
            var sp = 0; var dp = 0
            while (dp < rawWidth - 3 && sp < linelen - 6) {
                val s = s0 + sp
                fun u(k: Int) = b[s + k].toInt() and 0xff
                out[d0 + dp + 3] = ((u(6) shl 6) or (u(5) ushr 2)).toShort()
                out[d0 + dp + 2] = (((u(5) and 0x3) shl 12) or (u(4) shl 4) or (u(3) ushr 4)).toShort()
                out[d0 + dp + 1] = (((u(3) and 0xf) shl 10) or (u(2) shl 2) or (u(1) ushr 6)).toShort()
                out[d0 + dp] = (((u(1) and 0x3f) shl 8) or u(0)).toShort()
                sp += 7; dp += 4
            }
        }
        return true
    }

    // ================================================================= tables (LibRaw nikon.cpp xlat)
    private fun hex(s: String) = s.trim().split(Regex("[,\\s]+")).filter { it.isNotEmpty() }.map { it.removePrefix("0x").toInt(16).toByte() }.toByteArray()

    internal val XLAT0 = hex("""
        0xc1 0xbf 0x6d 0x0d 0x59 0xc5 0x13 0x9d 0x83 0x61 0x6b 0x4f 0xc7 0x7f 0x3d 0x3d
        0x53 0x59 0xe3 0xc7 0xe9 0x2f 0x95 0xa7 0x95 0x1f 0xdf 0x7f 0x2b 0x29 0xc7 0x0d
        0xdf 0x07 0xef 0x71 0x89 0x3d 0x13 0x3d 0x3b 0x13 0xfb 0x0d 0x89 0xc1 0x65 0x1f
        0xb3 0x0d 0x6b 0x29 0xe3 0xfb 0xef 0xa3 0x6b 0x47 0x7f 0x95 0x35 0xa7 0x47 0x4f
        0xc7 0xf1 0x59 0x95 0x35 0x11 0x29 0x61 0xf1 0x3d 0xb3 0x2b 0x0d 0x43 0x89 0xc1
        0x9d 0x9d 0x89 0x65 0xf1 0xe9 0xdf 0xbf 0x3d 0x7f 0x53 0x97 0xe5 0xe9 0x95 0x17
        0x1d 0x3d 0x8b 0xfb 0xc7 0xe3 0x67 0xa7 0x07 0xf1 0x71 0xa7 0x53 0xb5 0x29 0x89
        0xe5 0x2b 0xa7 0x17 0x29 0xe9 0x4f 0xc5 0x65 0x6d 0x6b 0xef 0x0d 0x89 0x49 0x2f
        0xb3 0x43 0x53 0x65 0x1d 0x49 0xa3 0x13 0x89 0x59 0xef 0x6b 0xef 0x65 0x1d 0x0b
        0x59 0x13 0xe3 0x4f 0x9d 0xb3 0x29 0x43 0x2b 0x07 0x1d 0x95 0x59 0x59 0x47 0xfb
        0xe5 0xe9 0x61 0x47 0x2f 0x35 0x7f 0x17 0x7f 0xef 0x7f 0x95 0x95 0x71 0xd3 0xa3
        0x0b 0x71 0xa3 0xad 0x0b 0x3b 0xb5 0xfb 0xa3 0xbf 0x4f 0x83 0x1d 0xad 0xe9 0x2f
        0x71 0x65 0xa3 0xe5 0x07 0x35 0x3d 0x0d 0xb5 0xe9 0xe5 0x47 0x3b 0x9d 0xef 0x35
        0xa3 0xbf 0xb3 0xdf 0x53 0xd3 0x97 0x53 0x49 0x71 0x07 0x35 0x61 0x71 0x2f 0x43
        0x2f 0x11 0xdf 0x17 0x97 0xfb 0x95 0x3b 0x7f 0x6b 0xd3 0x25 0xbf 0xad 0xc7 0xc5
        0xc5 0xb5 0x8b 0xef 0x2f 0xd3 0x07 0x6b 0x25 0x49 0x95 0x25 0x49 0x6d 0x71 0xc7
    """)

    internal val XLAT1 = hex("""
        0xa7 0xbc 0xc9 0xad 0x91 0xdf 0x85 0xe5 0xd4 0x78 0xd5 0x17 0x46 0x7c 0x29 0x4c
        0x4d 0x03 0xe9 0x25 0x68 0x11 0x86 0xb3 0xbd 0xf7 0x6f 0x61 0x22 0xa2 0x26 0x34
        0x2a 0xbe 0x1e 0x46 0x14 0x68 0x9d 0x44 0x18 0xc2 0x40 0xf4 0x7e 0x5f 0x1b 0xad
        0x0b 0x94 0xb6 0x67 0xb4 0x0b 0xe1 0xea 0x95 0x9c 0x66 0xdc 0xe7 0x5d 0x6c 0x05
        0xda 0xd5 0xdf 0x7a 0xef 0xf6 0xdb 0x1f 0x82 0x4c 0xc0 0x68 0x47 0xa1 0xbd 0xee
        0x39 0x50 0x56 0x4a 0xdd 0xdf 0xa5 0xf8 0xc6 0xda 0xca 0x90 0xca 0x01 0x42 0x9d
        0x8b 0x0c 0x73 0x43 0x75 0x05 0x94 0xde 0x24 0xb3 0x80 0x34 0xe5 0x2c 0xdc 0x9b
        0x3f 0xca 0x33 0x45 0xd0 0xdb 0x5f 0xf5 0x52 0xc3 0x21 0xda 0xe2 0x22 0x72 0x6b
        0x3e 0xd0 0x5b 0xa8 0x87 0x8c 0x06 0x5d 0x0f 0xdd 0x09 0x19 0x93 0xd0 0xb9 0xfc
        0x8b 0x0f 0x84 0x60 0x33 0x1c 0x9b 0x45 0xf1 0xf0 0xa3 0x94 0x3a 0x12 0x77 0x33
        0x4d 0x44 0x78 0x28 0x3c 0x9e 0xfd 0x65 0x57 0x16 0x94 0x6b 0xfb 0x59 0xd0 0xc8
        0x22 0x36 0xdb 0xd2 0x63 0x98 0x43 0xa1 0x04 0x87 0x86 0xf7 0xa6 0x26 0xbb 0xd6
        0x59 0x4d 0xbf 0x6a 0x2e 0xaa 0x2b 0xef 0xe6 0x78 0xb6 0x4e 0xe0 0x2f 0xdc 0x7c
        0xbe 0x57 0x19 0x32 0x7e 0x2a 0xd0 0xb8 0xba 0x29 0x00 0x3c 0x52 0x7d 0xa8 0x49
        0x3b 0x2d 0xeb 0x25 0x49 0xfa 0xa3 0xaa 0x39 0xa7 0xc5 0xa7 0x50 0x11 0x36 0xfb
        0xc6 0x67 0x4a 0xf5 0xa5 0x12 0x65 0x7e 0xb0 0xdf 0xaf 0x4e 0xb3 0x61 0x7f 0x2f
    """)
}
