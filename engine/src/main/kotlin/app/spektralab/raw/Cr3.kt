package app.spektralab.raw

/**
 * Canon CR3 reader: ISO base media file (brand `crx `) holding the CRX codec ([Crx]).
 *
 * Container walk, track selection and metadata follow LibRaw 0.22.2
 * (src/metadata/cr3_parser.cpp, src/metadata/canon.cpp, src/metadata/identify.cpp):
 * - `moov/uuid(Canon)/CMT1` is a TIFF IFD0 (Make, Model, Orientation), `CMT3` the Canon
 *   maker note; the first sample of the `CTMD` timed-metadata track carries further
 *   maker-note blocks (records 7 and 8) which LibRaw applies after CMT3. On current
 *   bodies the colour data (tag 0x4001: as-shot WB, per-channel black, white levels)
 *   is only in CTMD.
 * - the raw track is the `CRAW` track with a valid `CMP1` header and the most
 *   bits x pixels; its first sample (stsz / co64) is the CRX frame.
 * - levels and crop as LibRaw identify: raw sizes in LibRaw's Canon size table (M50, R,
 *   RP, R1, R3, R10, R6 II, R5 II, 90D, ...) take the table's margins and a black level
 *   measured on the optical-black columns (crop_masked_pixels); other sizes take the
 *   colour data's ChannelBlackLevel and the maker-note SensorInfo (0x00E0) crop when its
 *   sensor size matches the frame. Odd margins move to the next even row / column.
 * - CFA from the CMP1 layout field (or the table's filters byte), orientation from CMT1,
 *   colour matrix from [CameraMatrices.findCanon] (by the maker-note model id, as LibRaw), as-shot WB from the colour data.
 *
 * Returns null for: other files; CMP1 variants LibRaw rejects; 1-plane or encoding-type-1
 * tracks; a largest raw track whose header is unusable (LibRaw would then fall back to a
 * smaller track, e.g. the 1/4-size one some bodies store); sizes outside the table without
 * a per-channel black in the colour data (unknown ColorData layout); table sizes whose
 * optical black LibRaw would not measure; cameras missing from the colour matrix table;
 * C-RAW frames with more than one tile row in header v0x200 (LibRaw reads outside its QP
 * table there, see [Crx]); any decoding error.
 *
 * White level: the data maximum is (1 << bits) - 1 (LibRaw's `maximum`), but Canon
 * records the level where the sensor stops being linear (SpecularWhiteLevel, LibRaw's
 * `linear_max`, e.g. 14008 for the R6 against 16383). That is used when present so that
 * clipped highlights develop to white rather than magenta.
 */
object Cr3Reader {
    private val CANON_UUID = byteArrayOf(
        0x85.toByte(), 0xc0.toByte(), 0xb6.toByte(), 0x87.toByte(), 0x82.toByte(), 0x0f, 0x11, 0xe0.toByte(),
        0x81.toByte(), 0x11, 0xf4.toByte(), 0xce.toByte(), 0x46, 0x2b, 0x6a, 0x48)

    /** Everything the reader found in the container (exposed for tests). */
    class Info {
        var cmt1 = -1; var cmt1Size = 0
        var cmt3 = -1; var cmt3Size = 0
        val tracks = ArrayList<Track>()
    }

    class Track {
        var handler = ""
        var format = ""
        var header: CrxHeader? = null
        var hasCmp1 = false
        var sampleSize = 0L
        var firstSampleSize = 0L
        var chunkOffset = 0L
        val isRaw get() = header != null
    }

    fun read(bytes: ByteArray): RawImage? = try { parse(bytes) } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }

    fun isCr3(bytes: ByteArray) = bytes.size >= 16 && Bytes.matches(bytes, 4, "ftypcrx ")

    private fun parse(bytes: ByteArray): RawImage? {
        if (!isCr3(bytes)) return null
        val info = scan(bytes) ?: return null

        // raw track: the largest valid CRAW/CMP1 track, as LibRaw's selectCRXTrack
        val crawTracks = info.tracks.filter { it.hasCmp1 }
        val best = info.tracks.filter { it.isRaw }.maxByOrNull { it.header!!.nBits.toLong() * it.header!!.width * it.header!!.height } ?: return null
        val hdr = best.header!!
        // a larger track LibRaw rejected: don't silently develop a smaller one
        if (crawTracks.any { !it.isRaw && it.sampleSizeHint() > best.sampleSizeHint() }) return null
        if (hdr.nPlanes != 4 || (hdr.encType != 0 && hdr.encType != 3)) return null
        val w = hdr.width; val h = hdr.height
        if (w.toLong() * h > RawDecodeLimits.MAX_SAMPLES) return null
        val offset = best.chunkOffset
        val size = if (best.sampleSize > 0) best.sampleSize else best.firstSampleSize
        if (offset <= 0 || size <= 0 || offset + size > bytes.size) return null
        // cheap plausibility before allocating the frame: the compressed data can't be absurdly small
        if (size < hdr.mdatHdrSize + 16) return null

        val notes = metadata(bytes, info) ?: return null
        val make = notes.make
        val model = notes.model
        // LibRaw identify: a raw size in its Canon table fixes margins (and maybe the CFA) and makes
        // the black level come from the optical-black pixels; otherwise the colour data's
        // ChannelBlackLevel and the SensorInfo crop are used
        val table = CanonSizes.find(w, h)
        if (table == null && notes.channelBlack == null) return null

        val matrix = CameraMatrices.findCanon(notes.uniqueId, make, model)?.takeIf { it.colors == 3 && it.xyzToCamera != null } ?: return null

        // pixels
        val data = ShortArray(w * h)
        if (!Crx.decode(bytes, hdr, offset, size, data)) return null

        val maximum = (1 shl hdr.nBits) - 1
        // visible area: LibRaw identify (table, or SensorInfo when its size matches the frame)
        var cropX = 0; var cropY = 0; var cropW = w; var cropH = h
        if (table != null) {
            cropX = table[2]; cropY = table[3]
            cropW = w - cropX - table[4]; cropH = h - cropY - table[5]
        } else {
            val si = notes.sensorInfo
            if (si != null && si[1] == w && si[2] == h) {
                val left = (si[5] + 1) and 0xfffe
                val top = (si[6] + 1) and 0xfffe
                val cw = si[7] - left
                val ch = si[8] - top
                if (left >= 0 && top >= 0 && cw >= 16 && ch >= 16 && left + cw <= w && top + ch <= h) {
                    cropX = left; cropY = top; cropW = cw; cropH = ch
                }
            }
        }
        if (cropW < 16 || cropH < 16 || cropX + cropW > w || cropY + cropH > h) return null
        // LibRaw's filters word is relative to the visible origin (the CMP1 layout and the table's
        // filters byte alike); all CR3 bodies have even margins, where this changes nothing
        val base = if (table != null && table[10] != 0) CfaPattern.fromDcrawFilters(table[10] * 0x01010101)
        else when (hdr.cfaLayout) { 0 -> CfaPattern.RGGB; 1 -> CfaPattern.GRBG; 2 -> CfaPattern.GBRG; else -> CfaPattern.BGGR }
        val cfa = base.anchoredAt(cropY, cropX)
        // LibRaw open_datastream: odd margins are moved to the next even row / column
        if (cropY % 2 != 0) { cropY++; cropH-- }
        if (cropX % 2 != 0) { cropX++; cropW-- }
        // LibRaw's 4-colour index per CFA position: 0 R, 1 green on a red row, 2 B, 3 green on a blue row
        val fc = IntArray(4) { i ->
            val row = i / 2; val col = i % 2
            when (cfa.color(row, col)) { 0 -> 0; 2 -> 2; else -> if (cfa.color(row, 1 - col) == 0) 1 else 3 }
        }
        val black: FloatArray = if (table != null) {
            maskedBlack(data, w, h, fc, cropX, cropY, cropW, cropH, table) ?: return null
        } else {
            val b4 = notes.channelBlack!!                                 // R, G1, G2, B (file order RGGB)
            val byIndex = intArrayOf(b4[0], b4[1], b4[3], b4[2])          // LibRaw cblack order R, G, B, G2
            FloatArray(4) { byIndex[fc[it]].toFloat() }
        }
        // LibRaw open_datastream: 14-bit-scaled maker-note levels are brought to the real bit depth
        var specular = notes.specularWhite
        val bps = if (hdr.encType == 3) hdr.medianBits else hdr.nBits
        if (bps < 14 && specular > (1 shl bps) - 1) specular /= 1 shl (14 - bps)
        val white = specular.takeIf { it in (black.max().toInt() + 1)..maximum }?.toFloat() ?: maximum.toFloat()
        if (white <= black.max()) return null

        val asShot = when {
            notes.ctmdCount == 1 && notes.multishot -> doubleArrayOf(1.0, 1.0, 1.0)
            notes.wb != null -> notes.wb!!.let { doubleArrayOf(it[0].toDouble(), it[1].toDouble(), it[3].toDouble()) }
            else -> null
        }
        val xyz = DoubleArray(9) { matrix.xyzToCamera!![it / 3][it % 3] }
        val colour = RawColor.fromXyzToCamera(xyz, asShot) ?: return null
        val orientation = notes.orientation

        // output crop: the SensorInfo crop (the camera JPEG area, LibRaw's raw_inset_crops[0]) as in the CR2
        // reader, else the visible area; LibRaw moves an odd inset origin to the next even position
        notes.sensorInfo?.let { si ->
            val l = si[5]; val t = si[6]; val r = si[7]; val b = si[8]
            if (si[1] == w && si[2] == h && l >= 0 && t >= 0 && r > l + 22 && b > t + 22 && r < w && b < h) {
                cropX = l; cropY = t; cropW = r - l + 1; cropH = b - t + 1
                if (cropX % 2 != 0) { cropX++; cropW-- }
                if (cropY % 2 != 0) { cropY++; cropH-- }
            }
        }

        return RawImage(w, h, data, cfa, black, white, cropX, cropY, cropW, cropH, colour.wb, colour.camToSrgb,
            orientation = orientation, make = make, model = model)
    }


    /**
     * Black per CFA position from the optical-black columns, as LibRaw crop_masked_pixels for
     * crxLoadRaw: a left strip and a right strip over the rows of the visible area, the mean per
     * colour (integer division). Null when LibRaw would not measure one.
     */
    private fun maskedBlack(data: ShortArray, w: Int, h: Int, fc: IntArray, left: Int, top: Int, width: Int, height: Int, t: IntArray): FloatArray? {
        val m11 = t[8] + 2
        val masks = arrayOf(
            intArrayOf(top, m11, top + height, -t[7] - 2 + left),                // mask[0][1] = (mask[1][1] += 2)
            intArrayOf(top, m11 + left + width, top + height, -t[9] + w))
        val sum = LongArray(4)
        val count = LongArray(4)
        var zero = 0L
        for (m in masks) {
            for (row in maxOf(m[0], 0) until minOf(m[2], h)) {
                val base = row * w
                for (col in maxOf(m[1], 0) until minOf(m[3], w)) {
                    val c = fc[(row and 1) * 2 + (col and 1)]
                    val v = data[base + col].toInt() and 0xffff
                    sum[c] += v.toLong(); count[c]++
                    if (v == 0) zero++
                }
            }
        }
        if (!(zero < count[0] && count[1] > 0 && count[2] > 0 && count[3] > 0)) return null
        return FloatArray(4) { (sum[fc[it]] / count[fc[it]]).toFloat() }
    }

    private fun Track.sampleSizeHint(): Long = header?.let { it.nBits.toLong() * it.width * it.height } ?: (if (sampleSize > 0) sampleSize else firstSampleSize) * 8

    /** LibRaw takes the orientation of the largest IFD with a size; CMT1's IFD0 is the only one. */
    private fun hasSize(ifd: TiffIfd) = (ifd.int(TiffTags.IMAGE_WIDTH) ?: 0) > 0 && (ifd.int(TiffTags.IMAGE_LENGTH) ?: 0) > 0

    /**
     * CMT1 identification plus the maker-note values LibRaw uses, CMT3 first, then the CTMD
     * record maker notes. Works on files whose image data is missing (no decoding).
     */
    internal fun metadata(bytes: ByteArray, info: Info? = scan(bytes)): Notes? {
        if (info == null) return null
        val ifd0 = info.cmt1.takeIf { it >= 0 }?.let { Tiff.parse(bytes, it) }?.ifd0 ?: return null
        val notes = Notes()
        notes.make = ifd0.string(TiffTags.MAKE)?.trim().takeUnless { it.isNullOrEmpty() } ?: "Canon"
        notes.model = ifd0.string(TiffTags.MODEL)?.trim() ?: return null
        notes.orientation = ifd0.int(TiffTags.ORIENTATION)?.takeIf { it in 1..8 && hasSize(ifd0) } ?: 1
        info.cmt3.takeIf { it >= 0 }?.let { Tiff.parse(bytes, it) }?.let { notes.apply(it.ifd0) }
        val ctmd = info.tracks.firstOrNull { it.handler == "meta" && it.format == "CTMD" }
        if (ctmd != null) {
            notes.ctmdCount = 1
            val cs = if (ctmd.sampleSize > 0) ctmd.sampleSize else ctmd.firstSampleSize
            parseCtmd(bytes, ctmd.chunkOffset, cs, notes)
        }
        return notes
    }

    // ------------------------------------------------------------------ maker note values
    internal class Notes {
        var make = ""
        var model = ""
        var orientation = 1
        var ctmdCount = 0
        var wb: IntArray? = null              // R, G1, G2, B as stored (RGGB)
        var channelBlack: IntArray? = null
        var specularWhite = 0
        /** Maker-note model id (tag 0x0010), by which LibRaw names the camera. */
        var uniqueId = 0L
        var sensorInfo: IntArray? = null
        var multishot = false

        fun apply(ifd: TiffIfd) {
            ifd.longs(0x0010, 1)?.firstOrNull()?.takeIf { it != 0L }?.let { uniqueId = it }
            ifd.ints(0x00e0)?.takeIf { it.size >= 9 }?.let { sensorInfo = it }
            colorData(ifd)
            // tag order: 0x4021 (multi-shot) comes after the colour data and resets the WB to unity
            ifd.ints(0x4021)?.takeIf { it.size >= 2 }?.let {
                if (it[0] != 0 && it[1] != 0) { multishot = true; wb = intArrayOf(1024, 1024, 1024, 1024) }
            }
        }

        private fun colorData(ifd: TiffIfd) {
            val cd = ifd.ints(0x4001) ?: return
            val len = cd.size
            if (len <= 500) return
            // (as-shot WB offset, black level offset, white levels offset, fallback black offset) in shorts
            val layout = when (len) {
                1816, 1820, 1824 -> intArrayOf(0x47, 0x47 + 0x2d1, 0x47 + 0x2d5, 0x47 + 0x102)
                1770, 2024, 3656 -> intArrayOf(0x55, 0x55 + 0x2d1, 0x55 + 0x2d5, 0x55 + 0x102)
                3973, 3778 -> intArrayOf(0x69, 0x69 + 0x213, 0x69 + 0x217, 0x69 + 0x102)
                4528 -> intArrayOf(0x69, 0x69 + 0x227, 0x69 + 0x22b, 0x69 + 0x116)
                else -> return
            }
            fun four(at: Int) = if (at + 4 <= len) IntArray(4) { cd[at + it] } else null
            four(layout[0])?.let { wb = it }
            var bl = four(layout[1])
            if (bl == null || bl.sum() / 4 == 0) bl = four(layout[3])?.takeIf { it.sum() / 4 != 0 }
            if (bl != null) channelBlack = bl
            if (layout[2] + 2 <= len) specularWhite = cd[layout[2] + 1]
        }
    }

    /** CTMD sample: records (size u32 LE, type u16 LE, ...); types 7 and 8 hold maker-note TIFF blocks (tag 0x927c). */
    private fun parseCtmd(bytes: ByteArray, offset: Long, size: Long, notes: Notes) {
        if (offset <= 0 || size <= 0 || offset + size > bytes.size) return
        val base = offset.toInt()
        val total = size.toInt()
        var rel = 0
        while (rel + 6 < total) {
            val szItem = Bytes.u32le(bytes, base + rel)
            val type = Bytes.u16le(bytes, base + rel + 4)
            if (szItem < 1 || rel + szItem > total) return
            if (type == 7 || type == 8 || type == 9) {
                var inBox = rel + 12
                while (inBox + 8 < rel + szItem) {
                    val lTag = Bytes.u32le(bytes, base + inBox)
                    val tag = Bytes.u32le(bytes, base + inBox + 4)
                    if (lTag < 8 || inBox + lTag > rel + szItem) return
                    if (tag == 0x927cL && type != 9) Tiff.parse(bytes, base + inBox + 8)?.let { notes.apply(it.ifd0) }
                    inBox += lTag.toInt()
                }
            }
            rel += szItem.toInt()
        }
    }

    // ------------------------------------------------------------------ box walk
    /** Walks the ISO-BMFF boxes LibRaw looks at. */
    fun scan(bytes: ByteArray): Info? {
        if (!isCr3(bytes)) return null
        val info = Info()
        walk(bytes, 0, bytes.size.toLong(), "", info, null, 0)
        return info
    }

    private fun walk(b: ByteArray, start: Long, end: Long, path: String, info: Info, track: Track?, depth: Int) {
        if (depth > 12) return
        var o = start
        val cur = track
        while (o + 8 <= end && o + 8 <= b.size) {
            var size = Bytes.u32be(b, o.toInt())
            val name = String(CharArray(4) { (b[o.toInt() + 4 + it].toInt() and 0xff).toChar() })
            var header = 8L
            if (size == 1L) {
                if (o + 16 > end) return
                size = Bytes.u64(b, o.toInt() + 8, false) and Long.MAX_VALUE
                header = 16
            } else if (size == 0L) {
                if (depth != 0) return
                size = end - o
            }
            if (size < header || o + size > end) return
            val content = o + header
            val contentSize = size - header
            val p = "$path/$name"
            when (p) {
                "/moov" -> walk(b, content, o + size, p, info, null, depth + 1)
                "/moov/uuid" -> if (contentSize >= 16 && (0 until 16).all { b[(content + it).toInt()] == CANON_UUID[it] })
                    walk(b, content + 16, o + size, p, info, null, depth + 1)
                "/moov/uuid/CMT1" -> { info.cmt1 = content.toInt(); info.cmt1Size = contentSize.toInt() }
                "/moov/uuid/CMT3" -> { info.cmt3 = content.toInt(); info.cmt3Size = contentSize.toInt() }
                "/moov/trak" -> {
                    val t = Track()
                    info.tracks += t
                    if (info.tracks.size > 16) return
                    walk(b, content, o + size, p, info, t, depth + 1)
                }
                "/moov/trak/mdia", "/moov/trak/mdia/minf", "/moov/trak/mdia/minf/stbl" -> walk(b, content, o + size, p, info, cur, depth + 1)
                "/moov/trak/mdia/hdlr" -> if (cur != null && contentSize >= 12)
                    cur.handler = String(CharArray(4) { (b[(content + 8 + it).toInt()].toInt() and 0xff).toChar() })
                "/moov/trak/mdia/minf/stbl/stsd" -> if (cur != null && contentSize >= 16) {
                    val entry = content + 8
                    val entrySize = Bytes.u32be(b, entry.toInt())
                    cur.format = String(CharArray(4) { (b[(entry + 4 + it).toInt()].toInt() and 0xff).toChar() })
                    if (cur.handler == "vide" && cur.format == "CRAW" && entrySize >= 8 + 82 && entry + entrySize <= o + size)
                        walk(b, entry + 8 + 82, entry + entrySize, "$p/CRAW", info, cur, depth + 1)
                }
                "/moov/trak/mdia/minf/stbl/stsd/CRAW/CMP1" -> if (cur != null) {
                    cur.hasCmp1 = true
                    cur.header = CrxHeader.parse(b, content.toInt(), contentSize.toInt())
                }
                "/moov/trak/mdia/minf/stbl/stsz" -> if (cur != null && contentSize >= 12) {
                    cur.sampleSize = Bytes.u32be(b, (content + 4).toInt())
                    val n = Bytes.u32be(b, (content + 8).toInt())
                    if (cur.sampleSize == 0L && n >= 1 && contentSize >= 16) cur.firstSampleSize = Bytes.u32be(b, (content + 12).toInt())
                }
                "/moov/trak/mdia/minf/stbl/co64" -> if (cur != null && contentSize >= 16) {
                    val n = Bytes.u32be(b, (content + 4).toInt())
                    if (n >= 1) cur.chunkOffset = Bytes.u64(b, (content + 8).toInt(), false)
                }
            }
            o += size
        }
    }
}
