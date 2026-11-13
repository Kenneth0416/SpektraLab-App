package app.spektralab.raw

/*
 * Canon CR2 reader.
 *
 * Ported from LibRaw 0.22.2 (LGPL 2.1 / CDDL; derived from dcraw.c by Dave Coffin, used here
 * under GPLv3): lossless_jpeg_load_raw (slice reassembly, src/decoders/decoders_dcraw.cpp),
 * the Canon margin table and ColorData black fallback (identify(), src/metadata/identify.cpp),
 * crop_masked_pixels (masked-border black, src/utils/utils_dcraw.cpp), the odd-margin
 * adjustment of open_datastream (src/utils/open.cpp), parseCanonMakernotes ColorData /
 * SensorInfo handling and processCanonCameraInfo orientation (src/metadata/canon.cpp).
 */

/**
 * Canon CR2 ("II*\0" + "CR\x02\0"): lossless JPEG raw in Canon slices (tag 0xC640), with
 * 2- or 4-component frames, as written by every EOS / PowerShot from the 20D (2004) up to the
 * cameras that switched to CR3.
 *
 * What LibRaw reports is reproduced exactly: the mosaic equals `raw_image`, black is the
 * per-channel mean of the left (and right) masked border for cameras in the LibRaw margin
 * table, else the maker note ColorData ChannelBlackLevel; WB is the ColorData as-shot
 * multipliers (green = mean of G1 and G2); the colour matrix is looked up under LibRaw's
 * model-id name ([CameraMatrices.findCanon]). The crop is the maker note SensorInfo crop (LibRaw's
 * `raw_inset_crops[0]`, the area of the camera JPEG), falling back to LibRaw's visible area.
 *
 * White, as in the CR3 reader: the ColorData SpecularWhiteLevel (LibRaw's `linear_max`, the level
 * Canon records for the sensor's linear range at the shot's ISO; also what rawspeed / darktable and
 * rawler use as the white point) when the file has one, else
 * LibRaw's `maximum` (the colour-table value, else the full bit range; [Decoded.libRawWhite]).
 * The colour-table value is not the clipping point at every ISO, and LibRaw's render-time
 * adjust_maximum (white := data maximum when that lies within 75 % .. 100 % of it) is not used:
 * it depends on the picture (one hot pixel sets it), so the same scene would develop brighter or
 * darker depending on its content.
 *
 * Returns null for sRAW / mRAW (subsampled YCbCr frames), anything that is not a CR2,
 * truncated files, cameras without a colour matrix in [CameraMatrices], cameras with no
 * black level from either the masked border, the maker note or the table, and corrupt data.
 */
object Cr2Reader {
    fun read(bytes: ByteArray): RawImage? = decode(bytes)?.image

    /** The image plus LibRaw's own `maximum` ([RawImage.white] is the SpecularWhiteLevel when the file has one). */
    internal class Decoded(val image: RawImage, val libRawWhite: Int)

    internal fun decode(bytes: ByteArray): Decoded? = try { parse(bytes) } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }

    private const val TAG_CR2_SLICE = 0xC640

    private fun parse(bytes: ByteArray): Decoded? {
        if (bytes.size < 16 || !Bytes.matches(bytes, 0, "II") || !Bytes.matches(bytes, 8, "CR") || bytes[10].toInt() != 2) return null
        val tiff = Tiff.parse(bytes, 0, setOf(42)) ?: return null
        val ifd0 = tiff.ifd0
        val make = ifd0.string(TiffTags.MAKE) ?: return null
        if (!make.startsWith("Canon", ignoreCase = true)) return null
        if (TiffTags.DNG_VERSION in ifd0) return null
        val model = ifd0.string(TiffTags.MODEL) ?: ""

        // ---- raw frame: the largest lossless-JPEG strip in the IFD chain (normally IFD3)
        var rawIfd: TiffIfd? = null
        var header: LosslessJpeg.Header? = null
        var stripOff = 0
        var stripLen = 0
        for (ifd in tiff.ifds) {
            if (ifd.int(TiffTags.COMPRESSION) != 6) continue
            val off = ifd.longs(TiffTags.STRIP_OFFSETS, 1)?.get(0) ?: continue
            if (off <= 0 || off >= bytes.size) continue
            val cnt = ifd.longs(TiffTags.STRIP_BYTE_COUNTS, 1)?.get(0) ?: (bytes.size - off)
            if (cnt > bytes.size - off) return null                                       // truncated file
            val len = cnt.toInt()
            // subsampled (sRAW / mRAW) and lossy frames are rejected by readHeader
            val h = LosslessJpeg.readHeader(bytes, off.toInt(), len) ?: continue
            if (header == null || h.rowSamples.toLong() * h.height > header.rowSamples.toLong() * header.height) {
                rawIfd = ifd; header = h; stripOff = off.toInt(); stripLen = len
            }
        }
        if (rawIfd == null || header == null) return null
        val h = header
        if (h.components != 2 && h.components != 4) return null
        if (h.pointTransform != 0 || h.precision > 16) return null
        var rawWidth = h.width * h.components
        var rawHeight = h.height
        if (rawWidth > 4 * rawHeight) { rawWidth /= 2; rawHeight *= 2 }                     // LibRaw apply_tiff
        if (rawWidth < 22 || rawHeight < 22 || rawWidth.toLong() * rawHeight > RawDecodeLimits.MAX_SAMPLES) return null

        val slices = rawIfd.ints(TAG_CR2_SLICE, 3)?.takeIf { it.size == 3 } ?: intArrayOf(0, 0, 0)
        if (slices[0] != 0 && (slices[1] <= 0 || slices[2] <= 0)) return null
        if (slices[0] < 0 || slices[0].toLong() * slices[1] > rawWidth) return null

        // ---- maker note
        val mn = parseMakerNote(tiff)

        // ---- pixels
        val data = ShortArray(rawWidth * rawHeight)
        if (!decode(bytes, stripOff, stripLen, h, rawWidth, rawHeight, slices, data)) return null

        // ---- margins (LibRaw identify: canon[] table, then ColorData black / SensorInfo)
        var left = 0; var top = 0; var width = rawWidth; var height = rawHeight
        // masks as LibRaw's mask[m] = {top, left, bottom, right}; null = derive from the margins
        val mask = Array(2) { IntArray(4) }
        var filters = 0x94949494.toInt()
        var cblack: IntArray? = null                    // per LibRaw colour 0..3 (R, G, B, G2)
        val row = CanonSizes.find(rawWidth, rawHeight)
        val fromTable = row != null
        if (row != null) {
            left = row[2]; top = row[3]
            width = rawWidth - left - row[4]
            height = rawHeight - top - row[5]
            mask[0][1] = row[6]; mask[0][3] = -row[7]
            mask[1][1] = row[8]; mask[1][3] = -row[9]
            if (row[10] != 0) filters = row[10] * 0x01010101
        }
        if ((mn.uniqueId or 0x20000L) == 0x2720000L) { left = 8; top = 16 }                  // PowerShot G11, S90
        if (!fromTable && mn.averageBlack != 0) {
            cblack = mn.channelBlack.copyOf()
            mask[0][3] = 1; mask[0][1] = 2                                                     // no masked-area black
            val crop = mn.crop
            if (crop != null && mn.sensorWidth == rawWidth && mn.sensorHeight == rawHeight) {
                left = (crop[0] + 1) and 0xfffe; width = crop[2] - left
                top = (crop[1] + 1) and 0xfffe; height = crop[3] - top
            }
        }
        // identify_finetune_dcr: PowerShot overrides that apply to CR2 files
        val shortModel = model.removePrefix("Canon ").trim()
        if (shortModel == "PowerShot G16") {
            mask[0][0] = 0; mask[0][2] = 80; mask[0][1] = 0; mask[0][3] = 16
            top = 29; left = 120
            width = rawWidth - left - 48; height = rawHeight - top - 14
        } else if (shortModel == "PowerShot SX50 HS") top = 17
        if (left < 0 || top < 0 || width < 22 || height < 22 || left + width > rawWidth || top + height > rawHeight) return null

        // CFA: LibRaw's filters are relative to the visible origin as identified
        val idTop = top; val idLeft = left
        // open_datastream: odd margins move to the next even row / column
        if (top % 2 != 0) { top += 1; height -= 1 }
        if (left % 2 != 0) { left += 1; width -= 1 }
        val fc4 = fourColourFilters(filters)
        fun libColour(r: Int, c: Int) = dcrawFc(fc4, r - idTop, c - idLeft)
        val cfa = CfaPattern(2, 2, IntArray(4) { i -> libColour(i / 2, i % 2).let { if (it == 3) 1 else it } })
        if (!cfa.isBayer) return null

        // ---- black: crop_masked_pixels, else ColorData, else the colour table
        // LibRaw names Canon bodies by their maker-note model id (so "EOS Rebel T2i" / "EOS Kiss X4" are "EOS 550D")
        val entry = CameraMatrices.findCanon(mn.uniqueId, make, model) ?: return null
        val matrix = entry.xyzToCamera?.takeIf { entry.colors == 3 } ?: return null
        maskedBlack(data, rawWidth, rawHeight, left, top, width, height, mask, ::libColour)?.let { cblack = it }
        val blackPerColour = cblack ?: if (entry.blackLevel > 0) IntArray(4) { entry.blackLevel } else return null
        val black = FloatArray(4) { i -> blackPerColour[libColour(i / 2, i % 2)].toFloat() }

        // ---- white: Canon's SpecularWhiteLevel, else LibRaw's maximum (colour table, else the bit range)
        val libRawWhite = if (entry.white > 0) entry.white else (1 shl h.precision) - 1
        if (libRawWhite <= blackPerColour.max()) return null
        val bitMax = (1 shl h.precision) - 1
        // LibRaw open_datastream: maker-note levels recorded on a 14-bit scale are brought to the real bit depth
        var specular = mn.specularWhite
        if (h.precision < 14 && specular > bitMax) specular /= 1 shl (14 - h.precision)
        val white = (specular.takeIf { it > blackPerColour.max() && it <= bitMax } ?: libRawWhite).toFloat()

        // ---- crop: SensorInfo (the camera's JPEG area), else LibRaw's visible area
        var cropX = left; var cropY = top; var cropW = width; var cropH = height
        mn.crop?.let { c ->
            if (mn.sensorWidth == rawWidth && mn.sensorHeight == rawHeight &&
                c[0] >= 0 && c[1] >= 0 && c[2] > c[0] + 22 && c[3] > c[1] + 22 && c[2] < rawWidth && c[3] < rawHeight) {
                cropX = c[0]; cropY = c[1]; cropW = c[2] - c[0] + 1; cropH = c[3] - c[1] + 1
                // open_datastream rounds an odd inset origin up to even (adjust_margins = 2)
                if (cropX % 2 != 0) { cropX++; cropW-- }
                if (cropY % 2 != 0) { cropY++; cropH-- }
            }
        }

        // ---- colour
        val mul = mn.camMul?.takeIf { validMultipliers(it) }
        val asShot = mul?.let { doubleArrayOf(it[0], if (it[3] > 0.001) (it[1] + it[3]) / 2 else it[1], it[2]) }
        val colour = RawColor.fromXyzToCamera(DoubleArray(9) { matrix[it / 3][it % 3] }, asShot) ?: return null

        // ---- orientation (LibRaw flip -> EXIF)
        var flip = 0
        for (ifd in tiff.ifds.reversed()) {
            val o = ifd.int(TiffTags.ORIENTATION) ?: continue
            val f = "50132467"[o and 7] - '0'
            if (f != 0) flip = f
        }
        if ((flip == 0 || mn.uniqueId == ID_EOS_40D) && mn.makernotesFlip != 0) flip = mn.makernotesFlip
        val orientation = FLIP_TO_EXIF[flip and 7]

        return Decoded(RawImage(rawWidth, rawHeight, data, cfa, black, white, cropX, cropY, cropW, cropH,
            colour.wb, colour.camToSrgb, orientation, make = make, model = model), libRawWhite)
    }

    // ------------------------------------------------------------------ pixels

    /**
     * lossless_jpeg_load_raw: the decoded sample stream fills slice 0 top to bottom
     * ([slices] = count, width, last width), then slice 1, ... and finally the last slice.
     */
    private fun decode(
        bytes: ByteArray, off: Int, len: Int, h: LosslessJpeg.Header,
        rawWidth: Int, rawHeight: Int, slices: IntArray, data: ShortArray,
    ): Boolean {
        val n = slices[0]
        val sliceW = if (n == 0) rawWidth else slices[1]
        val shift = rawWidth == 3984                                                         // EOS-1D Mark III
        if (shift && n == 0) return false                         // LibRaw's shift only makes sense for sliced frames
        var slice = 0
        var sliceX = 0
        var sw = if (n == 0) rawWidth else sliceW
        var r = 0
        var c = 0
        var bad = false
        var filled = 0L
        val done = LosslessJpeg.decode(bytes, off, len) { _, s ->
            if (bad) return@decode
            for (v in 0 until h.rowSamples) {
                if (r > rawHeight) { bad = true; return@decode }
                var row = r
                var col = sliceX + c
                if (shift) { col -= 2; if (col < 0) { col += rawWidth; row-- } }
                if (row in 0 until rawHeight && col in 0 until rawWidth) { data[row * rawWidth + col] = s[v].toShort(); filled++ }
                if (++c >= sw) {
                    c = 0
                    if (++r >= rawHeight && n != 0 && slice < n) {
                        // next slice; after the n regular ones the last one runs to the end of the stream
                        slice++; r = 0; sliceX = slice * sliceW
                        sw = if (slice < n) sliceW else slices[2]
                    }
                }
            }
        } ?: return false
        if (bad) return false
        // the frame must cover the raw area (it does by construction unless the slices disagree)
        return done.rowSamples.toLong() * done.height >= rawWidth.toLong() * rawHeight && filled >= rawWidth.toLong() * rawHeight - (if (shift) 2 else 0)
    }

    // ------------------------------------------------------------------ black

    /** crop_masked_pixels for lossless_jpeg_load_raw: per-colour integer mean of the masked side borders. */
    private fun maskedBlack(
        data: ShortArray, rawWidth: Int, rawHeight: Int, left: Int, top: Int, width: Int, height: Int,
        maskIn: Array<IntArray>, colour: (Int, Int) -> Int,
    ): IntArray? {
        val mask = Array(2) { maskIn[it].copyOf() }
        if (mask[0][3] <= 0) {
            mask[1][1] += 2; mask[0][1] = mask[1][1]
            mask[0][3] -= 2
            mask[0][0] = top; mask[1][0] = top
            mask[0][2] = top + height; mask[1][2] = top + height
            mask[0][3] += left
            mask[1][1] += left + width
            mask[1][3] += rawWidth
        }
        val sum = LongArray(4)
        val count = LongArray(4)
        var zero = 0L
        for (m in mask) {
            val c0 = maxOf(m[1], 0); val c1 = minOf(m[3], rawWidth)
            if (c1 <= c0) continue
            for (row in maxOf(m[0], 0) until minOf(m[2], rawHeight)) {
                val base = row * rawWidth
                for (col in c0 until c1) {
                    val v = data[base + col].toInt() and 0xffff
                    val c = colour(row, col)
                    sum[c] += v; count[c]++
                    if (v == 0) zero++
                }
            }
        }
        if (!(zero < count[0] && count[1] > 0 && count[2] > 0 && count[3] > 0)) return null
        return IntArray(4) { (sum[it] / maxOf(1L, count[it])).toInt() }
    }

    // ------------------------------------------------------------------ CFA helpers

    /** dcraw `filters` with the second green marked as colour 3 (identify(), colors == 3). */
    private fun fourColourFilters(f: Int): Int =
        f or ((((f ushr 2) and 0x22222222) or ((f shl 2) and 0x88888888.toInt())) and (f shl 1))

    private fun dcrawFc(filters: Int, row: Int, col: Int): Int {
        val r = Math.floorMod(row, 8); val c = Math.floorMod(col, 2)
        return (filters ushr ((((r shl 1) and 14) or (c and 1)) shl 1)) and 3
    }

    private fun validMultipliers(m: DoubleArray): Boolean {
        // LibRaw's cam_mul range check (colors == 3 uses R, G, B)
        if (m.size < 4 || (0..2).any { m[it] <= 0.001 || !m[it].isFinite() }) return false
        val min = minOf(m[0], m[1], m[2])
        val norm = DoubleArray(3) { m[it] / min }
        return norm.min() > 0.01 && norm.max() <= 100.0
    }

    // ------------------------------------------------------------------ maker note

    private class MakerInfo {
        var uniqueId = 0L
        /** As-shot multipliers in LibRaw cam_mul order (R, G, B, G2), null when absent. */
        var camMul: DoubleArray? = null
        val channelBlack = IntArray(4)
        var averageBlack = 0
        /** ColorData SpecularWhiteLevel, 0 when the colour data has no white levels. */
        var specularWhite = 0
        var sensorWidth = 0
        var sensorHeight = 0
        /** SensorInfo DefaultCropAbsolute: left, top, right, bottom (inclusive). */
        var crop: IntArray? = null
        var makernotesFlip = 0
    }

    private fun parseMakerNote(tiff: Tiff): MakerInfo {
        val info = MakerInfo()
        val mn = tiff.makerNote()?.ifd ?: return info
        val b = tiff.bytes
        val le = mn.littleEndian
        fun u16(p: Int) = if (p >= 0 && p + 2 <= b.size) Bytes.u16(b, p, le) else 0
        fun u32(p: Int) = if (p >= 0 && p + 4 <= b.size) Bytes.u32(b, p, le) else 0L

        mn[0x0010]?.takeIf { it.count > 0 }?.let { info.uniqueId = u32(it.dataOffset) }

        // 0x00e0 SensorInfo: size, width, height, 2 skipped, crop l t r b
        mn[0x00e0]?.takeIf { it.count >= 9 }?.let { e ->
            val p = e.dataOffset
            info.sensorWidth = u16(p + 2); info.sensorHeight = u16(p + 4)
            info.crop = intArrayOf(u16(p + 10), u16(p + 12), u16(p + 14), u16(p + 16))
        }

        // 0x4001 ColorData: layout by length (parseCanonMakernotes)
        mn[0x4001]?.takeIf { it.count > 500 }?.let { e -> colorData(e, info, ::u16) }

        // 0x4021 multi-exposure: neutral multipliers
        mn[0x4021]?.takeIf { it.count >= 2 }?.let { e ->
            if (u32(e.dataOffset) != 0L && u32(e.dataOffset + 4) != 0L) info.camMul = doubleArrayOf(1024.0, 1024.0, 1024.0, 1024.0)
        }

        // 0x000d CameraInfo: orientation recorded by the camera (processCanonCameraInfo)
        mn[0x000d]?.let { e ->
            val long = e.type == 4 || e.type == 9
            val bytesLen = if (long) e.count * 4 else e.count
            if (e.count in 16 until 256000 && e.dataOffset + bytesLen <= b.size) {
                val buf = ByteArray(maxOf(16, bytesLen) + 16)
                System.arraycopy(b, e.dataOffset, buf, 0, bytesLen)
                info.makernotesFlip = cameraInfoFlip(info.uniqueId, buf, e.count, e.type == 7, le)
            }
        }
        return info
    }

    private fun colorData(e: TiffEntry, info: MakerInfo, u16: (Int) -> Int) {
        val base = e.dataOffset
        fun at(i: Int) = u16(base + (i shl 1))
        val subVer = at(0)
        var asShot = -1
        var blackAt = 0
        var black2At = 0
        when (e.count) {
            582 -> { asShot = 0x0019; blackAt = 0x00a6 }
            653 -> { asShot = 0x0022; blackAt = 0x011e }
            796 -> { asShot = 0x003f; blackAt = 0x00c4 }
            674, 692, 702, 1227, 1250, 1251, 1337, 1338, 1346 -> {
                asShot = 0x003f
                blackAt = when (subVer) { 4, 5 -> 0x02b4; 6, 7 -> 0x02cb; 9 -> 0x02cf; else -> 0x00e7 }
            }
            5120 -> {
                asShot = 0x0047
                blackAt = when (subVer) { 0xfffc -> 0x014d; 0xfffd -> 0x0108; else -> 0 }
            }
            1273, 1275 -> { asShot = 0x003f; blackAt = 0x01df }
            1312, 1313, 1316, 1506 -> { asShot = 0x003f; blackAt = when (subVer) { 10 -> 0x01f8; 11 -> 0x02d8; else -> 0 } }
            1560, 1592, 1353, 1602 -> { asShot = 0x003f; blackAt = if (subVer == 14) 0x022c else 0x030a }
            1820, 1824, 1816 -> { asShot = 0x0047; black2At = 0x0047 + 0x0102; blackAt = 0x0047 + 0x02d1 }
            1770, 2024, 3656 -> { asShot = 0x0055; black2At = 0x0055 + 0x0102; blackAt = 0x0055 + 0x02d1 }
            3973, 3778 -> { asShot = 0x0069; black2At = 0x0069 + 0x0102; blackAt = 0x0069 + 0x0213 }
            4528 -> { asShot = 0x0069; black2At = 0x0069 + 0x0116; blackAt = 0x0069 + 0x0227 }
        }
        // file order R, G1, G2, B -> LibRaw R, G, B, G2 (RGGB_2_RGBG)
        fun rgbg(at0: Int) = IntArray(4).also { o -> for (c in 0 until 4) o[c xor (c shr 1)] = at(at0 + c) }
        if (asShot >= 0) info.camMul = rgbg(asShot).map { it.toDouble() }.toDoubleArray()
        if (blackAt != 0) {
            val bl = rgbg(blackAt)
            bl.copyInto(info.channelBlack); info.averageBlack = bl.sum() / 4
        }
        if (info.averageBlack == 0 && black2At != 0) {
            val bl = rgbg(black2At)
            bl.copyInto(info.channelBlack); info.averageBlack = bl.sum() / 4
        }
        // white levels (NormalWhiteLevel, SpecularWhiteLevel) follow the channel black levels, except in
        // the colour data versions that have none (582, 653, 796 and the early 674-group sub-versions);
        // the same offsets as rawspeed (Cr2Decoder getBlackAndWhiteLevelOffsetsInColorData), which also
        // knows the 5120 sub-version 0xfffd one that LibRaw does not read
        val whiteAt = when {
            e.count == 5120 -> when (subVer) { 0xfffc -> 0x0569; 0xfffd -> 0x0295; else -> 0 }
            blackAt in intArrayOf(0, 0x00a6, 0x011e, 0x00c4, 0x00e7) -> 0
            else -> blackAt + 4
        }
        if (whiteAt != 0 && whiteAt + 1 < e.count) info.specularWhite = at(whiteAt + 1)
    }

    /** processCanonCameraInfo: the camera's rotation (LibRaw flip code 0, 6 or 5), 0 when unknown. */
    private fun cameraInfoFlip(id: Long, ci: ByteArray, maxlen: Int, undefinedType: Boolean, le: Boolean): Int {
        fun s2(p: Int) = Bytes.u16(ci, p, le)
        fun s4(p: Int) = Bytes.u32(ci, p, le)
        val mgck = s2(0)
        ci[0] = 0; ci[1] = 0
        fun fw(off: Int): Boolean {
            if (maxlen < off + 5) return false
            fun d(i: Int) = (ci[off + i].toInt() and 0xff) in '0'.code..'9'.code
            val end = ci[off + 5].toInt()
            return d(0) && d(2) && d(4) && ci[off + 1] == '.'.code.toByte() && ci[off + 3] == '.'.code.toByte() &&
                (end == 0 || end == ' '.code || end in 9..13)
        }
        val idx: Int = when (id) {
            ID_EOS_1D_MARK_III, ID_EOS_1DS_MARK_III -> 0x30
            ID_EOS_1D_MARK_IV -> 0x35
            ID_EOS_1D_X -> {
                val sub = when { fw(0x271) -> 1; fw(0x279) -> 2; fw(0x280) -> 3; fw(0x285) -> 4; else -> 0 }
                0x7d + (if (sub < 3) -3 else 0)
            }
            ID_EOS_5D -> 0x27
            ID_EOS_5D_MARK_II, ID_EOS_50D, ID_EOS_500D -> 0x31
            ID_EOS_5D_MARK_III -> {
                val sub = when { fw(0x22c) -> 1; fw(0x22d) -> 2; fw(0x23c) -> 3; fw(0x242) -> 4; fw(0x247) -> 5; else -> 0 }
                if (sub == 0) 0 else {
                    var o = if (sub < 3) -1 else 0
                    o += when { sub == 1 -> -3; sub == 2 -> -2; sub >= 4 -> 6; else -> 0 }
                    0x7d + o
                }
            }
            ID_EOS_5D_MARK_IV, ID_EOS_5DS, ID_EOS_5DS_R, ID_EOS_80D, ID_EOS_750D, ID_EOS_760D -> 0x96
            ID_EOS_6D -> 0x83
            ID_EOS_7D -> {
                val sub = when { fw(0x1a8) -> 1; fw(0x1ac) -> 2; else -> 0 }
                if (sub == 0) 0 else 0x35 + (if (sub < 2) -4 else 0)
            }
            ID_EOS_40D, ID_EOS_450D, ID_EOS_1000D -> 0x30
            ID_EOS_60D -> 0x36
            ID_EOS_1200D -> 0x3a
            ID_EOS_70D -> 0x84
            ID_EOS_550D -> 0x35
            ID_EOS_600D, ID_EOS_1100D -> 0x38
            ID_EOS_650D, ID_EOS_700D -> 0x7d
            else -> 0
        }
        if (idx != 0 && idx < maxlen && (ci[idx].toInt() and 0xff) < 3) return "065"[ci[idx].toInt()] - '0'
        if (undefinedType && mgck == 0xaaaa) {
            var i = 2
            while (i < maxlen && i + 2 <= ci.size && s2(i) != 0xbbbb) i++
            i += 2
            while (i < maxlen - 5 && i + 9 <= ci.size) {
                val c = ci[i + 8].toInt() and 0xff
                if (s4(i) == 257L && c < 3) return "065"[c] - '0'
                i += 4
            }
        }
        return 0
    }

    /** LibRaw flip code -> EXIF orientation (inverse of "50132467"). */
    private val FLIP_TO_EXIF = intArrayOf(1, 2, 4, 3, 5, 8, 6, 7)

    private const val ID_EOS_1D_MARK_III = 0x80000169L
    private const val ID_EOS_1DS_MARK_III = 0x80000215L
    private const val ID_EOS_1D_MARK_IV = 0x80000281L
    private const val ID_EOS_1D_X = 0x80000269L
    private const val ID_EOS_5D = 0x80000213L
    private const val ID_EOS_5D_MARK_II = 0x80000218L
    private const val ID_EOS_5D_MARK_III = 0x80000285L
    private const val ID_EOS_5D_MARK_IV = 0x80000349L
    private const val ID_EOS_5DS = 0x80000382L
    private const val ID_EOS_5DS_R = 0x80000401L
    private const val ID_EOS_6D = 0x80000302L
    private const val ID_EOS_7D = 0x80000250L
    private const val ID_EOS_40D = 0x80000190L
    private const val ID_EOS_50D = 0x80000261L
    private const val ID_EOS_60D = 0x80000287L
    private const val ID_EOS_70D = 0x80000325L
    private const val ID_EOS_80D = 0x80000350L
    private const val ID_EOS_450D = 0x80000176L
    private const val ID_EOS_500D = 0x80000252L
    private const val ID_EOS_550D = 0x80000270L
    private const val ID_EOS_600D = 0x80000286L
    private const val ID_EOS_650D = 0x80000301L
    private const val ID_EOS_700D = 0x80000326L
    private const val ID_EOS_750D = 0x80000393L
    private const val ID_EOS_760D = 0x80000347L
    private const val ID_EOS_1000D = 0x80000254L
    private const val ID_EOS_1100D = 0x80000288L
    private const val ID_EOS_1200D = 0x80000327L

}
