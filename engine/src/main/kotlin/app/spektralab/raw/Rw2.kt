package app.spektralab.raw

/**
 * Panasonic / Leica RW2 reader.
 *
 * Container: a TIFF variant with header magic 0x55 whose IFD0 carries Panasonic's own tags
 * (sensor size 2/3, sensor borders 4..7, CFA 9, bits per sample 10, linearity limits 14..16,
 * black levels 28..30, white balance 36..38 / 17..18, RawFormat 45, orientation 274, raw data
 * offset 280). The pixel codec is selected by RawFormat ("pana_encoding" in LibRaw):
 *
 *  * 4 - the classic Lumix codec: 16-byte packets of 14 pixels, each packet read as a 128-bit
 *        little-endian number from its top bit downwards, 0x4000-byte blocks rotated by 0x2008
 *        (LibRaw `panasonic_load_raw`, rawspeed `PanasonicV4Decompressor`);
 *  * 5 - 12/14-bit plain bit packing, 10 or 9 pixels per 16-byte packet, same 0x4000 rotation
 *        (LibRaw `panasonic_load_raw` encoding 5, rawspeed `PanasonicV5Decompressor`);
 *  * 6 - the S1-era 14/11 pixel page codec (LibRaw `panasonicC6_load_raw`);
 *  * 7 - 14-bit plain packing, 9 pixels per 16 bytes, no rotation (LibRaw `panasonicC7_load_raw`).
 *
 * Everything else (RawFormat 2, 3 and 8, i.e. the oldest cameras and the GH6 / S5II Huffman codec,
 * files without a raw data offset, unusual dimensions, truncated data) returns null.
 *
 * Levels, white balance and crop follow what LibRaw 0.22 reports for the same file:
 * black = tags 28..30 (+15 for RawFormat 4) or the camera table when the file has none,
 * white = the table's saturation value or (1 shl bits) - 1 (RawFormat 6 and 7: the largest
 * linearity limit), crop = the sensor borders, CFA anchored at raw (0, 0).
 *
 * Ported from LibRaw (Copyright LibRaw LLC, LGPL 2.1 / CDDL 1.0, derived from dcraw by Dave Coffin)
 * and cross-checked against rawspeed (LGPL 2.1+) and rawloader (LGPL 2.1).
 */
object Rw2Reader {
    private const val BLOCK = 0x4000
    /** File byte k of a 0x4000 block lands at (k + SPLIT) mod 0x4000 of the working buffer. */
    private const val SPLIT = 0x2008

    fun read(bytes: ByteArray): RawImage? = try { build(bytes) } catch (e: Exception) { null }

    /** Everything the file says about the frame, before any pixel is decoded. */
    internal class Meta(
        val make: String, val model: String,
        val width: Int, val height: Int, val dataOffset: Int, val encoding: Int, val bitsPerSample: Int,
        val cfa: CfaPattern, val blackRgb: FloatArray, val white: Float,
        val cropX: Int, val cropY: Int, val cropW: Int, val cropH: Int,
        val wb: RawColor.Result, val orientation: Int,
    )

    private fun build(bytes: ByteArray): RawImage? {
        val m = meta(bytes) ?: return null
        val data = decode(bytes, m.dataOffset, m.width, m.height, m.encoding, m.bitsPerSample) ?: return null
        return RawImage(
            m.width, m.height, data, m.cfa, m.cfa.perColor(m.blackRgb), m.white,
            m.cropX, m.cropY, m.cropW, m.cropH,
            m.wb.wb, m.wb.camToSrgb, m.orientation, make = m.make, model = m.model,
        )
    }

    internal fun meta(bytes: ByteArray): Meta? {
        // header: "II", 0x55 0x00
        if (bytes.size < 64 || bytes[0] != 'I'.code.toByte() || bytes[1] != 'I'.code.toByte() ||
            bytes[2] != 0x55.toByte() || bytes[3] != 0.toByte()) return null
        val tiff = Tiff.parse(bytes, 0, setOf(0x55)) ?: return null
        val ifd = tiff.ifd0
        val make = ifd.string(TiffTags.MAKE) ?: return null
        val model = ifd.string(TiffTags.MODEL) ?: return null
        if (!make.startsWith("Panasonic", ignoreCase = true) && !make.startsWith("LEICA", ignoreCase = true)) return null

        val width = ifd.int(2) ?: return null
        val height = ifd.int(3) ?: return null
        if (width < 32 || height < 32 || width > 65535 || height > 65535 ||
            width.toLong() * height > RawDecodeLimits.MAX_SAMPLES) return null
        val offsetEntry = ifd[0x118] ?: return null
        if (offsetEntry.type != 4 || offsetEntry.count < 1) return null
        val offset = ifd.value(offsetEntry, 0).toLong()
        if (offset <= 0 || offset >= bytes.size) return null

        val encoding = ifd.int(0x2d) ?: return null
        val bpp = ifd.int(0x0a) ?: return null
        if (bpp !in 8..16) return null

        // visible area = sensor borders (top, left, bottom, right)
        val top = ifd.int(4) ?: return null
        val left = ifd.int(5) ?: return null
        val bottom = ifd.int(6) ?: return null
        val right = ifd.int(7) ?: return null
        if (top < 0 || left < 0 || bottom <= top || right <= left || bottom > height || right > width) return null
        if (right - left < 16 || bottom - top < 16) return null

        // CFA, relative to raw (0, 0)
        var cfaTag = ifd.int(9) ?: return null
        if (model == "DC-LX100M2" && height == 3568 && width == 4816 && cfaTag == 3) cfaTag = 4
        val cfa = when (cfaTag) {
            1 -> CfaPattern.RGGB
            2 -> CfaPattern.GRBG
            3 -> CfaPattern.GBRG
            4 -> CfaPattern.BGGR
            else -> return null
        }

        // levels
        val matrix = CameraMatrices.find(make, model)
        val xyz = matrix?.xyzToCamera
        if (matrix == null || xyz == null || matrix.colors != 3) return null
        val fileBlack = intArrayOf(ifd.int(0x1c) ?: 0, ifd.int(0x1d) ?: 0, ifd.int(0x1e) ?: 0)
        val blackRgb = FloatArray(3)
        if (encoding >= 6) {
            // black = the file's own levels, nothing else (LibRaw: cblack from pana_black, black 0)
            for (c in 0 until 3) blackRgb[c] = fileBlack[c].toFloat()
        } else {
            val fromFile = fileBlack.all { it != 0 }
            val add = if (encoding == 4) 15 else 0
            for (c in 0 until 3) blackRgb[c] = (if (fromFile) fileBlack[c] + add else matrix.blackLevel).toFloat()
        }
        val white: Float = if (encoding >= 6) {
            fun limit(tag: Int) = (ifd.int(tag) ?: 0).let { if (it == 16383) it - 64 else if (it == 4095) it - 16 else it }
            maxOf(limit(14), limit(15), limit(16)).toFloat()
        } else {
            if (matrix.white > 0) matrix.white.toFloat() else ((1 shl bpp) - 1).toFloat()
        }
        if (white <= blackRgb.max() || white > 65535f) return null

        // colour: white balance multipliers, daylight if the file has none
        val wbTags = intArrayOf(ifd.int(0x24) ?: 0, ifd.int(0x25) ?: 0, ifd.int(0x26) ?: 0)
        val asShot: DoubleArray? = when {
            wbTags.all { it > 0 } -> DoubleArray(3) { wbTags[it].toDouble() }
            (ifd.int(0x11) ?: 0) > 0 && (ifd.int(0x12) ?: 0) > 0 ->
                doubleArrayOf(ifd.int(0x11)!!.toDouble(), 256.0, ifd.int(0x12)!!.toDouble())
            else -> null
        }
        val colour = RawColor.fromXyzToCamera(DoubleArray(9) { xyz[it / 3][it % 3] }, asShot) ?: return null
        val orientation = (ifd.int(TiffTags.ORIENTATION) ?: 1).let { if (it in 1..8) it else 1 }
        return Meta(make, model, width, height, offset.toInt(), encoding, bpp, cfa, blackRgb, white,
            left, top, right - left, bottom - top, colour, orientation)
    }

    // ------------------------------------------------------------------ codecs

    private fun decode(bytes: ByteArray, offset: Int, width: Int, height: Int, encoding: Int, bpp: Int): ShortArray? {
        val pixels = width.toLong() * height
        val out = ShortArray(pixels.toInt())
        return when (encoding) {
            4 -> {
                if (width % 14 != 0) return null
                val packets = pixels / 14
                if (!hasBlocks(bytes, offset, packets)) return null
                decodeRotated(bytes, offset, packets, 14) { buf, p, o -> packetV4(buf, p, out, o) }
                out
            }
            5 -> {
                if (bpp != 12 && bpp != 14) return null
                val perPacket = if (bpp == 12) 10 else 9
                if (width % perPacket != 0) return null
                val packets = pixels / perPacket
                if (!hasBlocks(bytes, offset, packets)) return null
                decodeRotated(bytes, offset, packets, perPacket) { buf, p, o -> packetPacked(buf, p, out, o, bpp, perPacket) }
                out
            }
            6 -> {
                if (bpp != 12 && bpp != 14) return null
                val perPage = if (bpp == 12) 14 else 11
                if (width % perPage != 0) return null
                val pages = pixels / perPage
                if (offset + pages * 16 > bytes.size) return null
                decodeV6(bytes, offset, out, pages.toInt(), bpp == 12)
                out
            }
            7 -> {
                if (bpp != 14 || width % 9 != 0) return null
                val packets = pixels / 9
                if (offset + packets * 16 > bytes.size) return null
                for (k in 0 until packets.toInt()) packetPacked(bytes, offset + k * 16, out, k * 9, 14, 9)
                out
            }
            else -> null
        }
    }

    /** True when the file holds the whole 0x4000-byte blocks the packets live in. */
    private fun hasBlocks(bytes: ByteArray, offset: Int, packets: Long): Boolean {
        val blocks = (packets * 16 + BLOCK - 1) / BLOCK
        return offset + blocks * BLOCK <= bytes.size
    }

    /** Runs [packet] (buffer, packet start, first output index) over every 16-byte packet, un-rotating each 0x4000-byte block first. */
    private inline fun decodeRotated(bytes: ByteArray, offset: Int, packets: Long, perPacket: Int, packet: (ByteArray, Int, Int) -> Unit) {
        val buf = ByteArray(BLOCK + 2)
        val perBlock = BLOCK / 16
        var done = 0L
        var block = 0
        while (done < packets) {
            val src = offset + block * BLOCK
            System.arraycopy(bytes, src, buf, SPLIT, BLOCK - SPLIT)
            System.arraycopy(bytes, src + BLOCK - SPLIT, buf, 0, SPLIT)
            val n = minOf(perBlock.toLong(), packets - done).toInt()
            val first = (done * perPacket).toInt()
            for (j in 0 until n) packet(buf, j * 16, first + j * perPacket)
            done += n; block++
        }
    }

    /** Classic codec: one packet = 14 pixels, 128 bits consumed from the top down. */
    private fun packetV4(b: ByteArray, base: Int, out: ShortArray, o: Int) {
        var pos = 128
        var sh = 0
        var pred0 = 0; var pred1 = 0
        var nonz0 = 0; var nonz1 = 0
        for (i in 0 until 14) {
            if (i % 3 == 2) {
                pos -= 2
                sh = 4 shr (3 - ((((b[base + (pos ushr 3)].toInt() and 0xff) or ((b[base + (pos ushr 3) + 1].toInt() and 0xff) shl 8)) ushr (pos and 7)) and 3))
            }
            val even = i and 1 == 0
            var nonz = if (even) nonz0 else nonz1
            var pred = if (even) pred0 else pred1
            if (nonz != 0) {
                pos -= 8
                val j = (((b[base + (pos ushr 3)].toInt() and 0xff) or ((b[base + (pos ushr 3) + 1].toInt() and 0xff) shl 8)) ushr (pos and 7)) and 0xff
                if (j != 0) {
                    pred -= 0x80 shl sh
                    if (pred < 0 || sh == 4) pred = pred and ((1 shl sh) - 1)
                    pred += j shl sh
                }
            } else {
                pos -= 8
                nonz = (((b[base + (pos ushr 3)].toInt() and 0xff) or ((b[base + (pos ushr 3) + 1].toInt() and 0xff) shl 8)) ushr (pos and 7)) and 0xff
                if (nonz != 0 || i > 11) {
                    pos -= 4
                    val low = (((b[base + (pos ushr 3)].toInt() and 0xff) or ((b[base + (pos ushr 3) + 1].toInt() and 0xff) shl 8)) ushr (pos and 7)) and 0xf
                    pred = nonz shl 4 or low
                }
            }
            if (even) { nonz0 = nonz; pred0 = pred } else { nonz1 = nonz; pred1 = pred }
            out[o + i] = pred.toShort()
        }
    }

    /** [perPacket] samples of [bits] bits each, least significant bit first, in one 16-byte packet. */
    private fun packetPacked(b: ByteArray, base: Int, out: ShortArray, o: Int, bits: Int, perPacket: Int) {
        var acc = 0L
        var have = 0
        var p = base
        val mask = (1L shl bits) - 1
        for (k in 0 until perPacket) {
            while (have < bits) { acc = acc or ((b[p++].toLong() and 0xff) shl have); have += 8 }
            out[o + k] = (acc and mask).toShort()
            acc = acc ushr bits; have -= bits
        }
    }

    /** S1-era page codec, a straight port of LibRaw `panasonicC6_load_raw`. */
    private fun decodeV6(b: ByteArray, offset: Int, out: ShortArray, pages: Int, twelve: Boolean) {
        val perPage = if (twelve) 14 else 11
        val base0 = if (twelve) 0x80 else 0x200
        val baseCompare = if (twelve) 0x800 else 0x2000
        val spixCompare = if (twelve) 0x3fff else 0xffff
        val pixelMask = if (twelve) 0xfff else 0x3fff
        val fields = IntArray(18)
        var o = 0
        for (page in 0 until pages) {
            val at = offset + page * 16
            fun w(i: Int) = b[at + 15 - i].toInt() and 0xff
            if (twelve) {
                fields[0] = (w(0) shl 4) or (w(1) shr 4)
                fields[1] = ((w(1) and 0xf) shl 8 or w(2)) and 0xfff
                fields[2] = (w(3) shr 6) and 3
                fields[3] = ((w(3) and 0x3f) shl 2) or (w(4) shr 6)
                fields[4] = ((w(4) and 0x3f) shl 2) or (w(5) shr 6)
                fields[5] = ((w(5) and 0x3f) shl 2) or (w(6) shr 6)
                fields[6] = (w(6) shr 4) and 3
                fields[7] = ((w(6) and 0xf) shl 4) or (w(7) shr 4)
                fields[8] = ((w(7) and 0xf) shl 4) or (w(8) shr 4)
                fields[9] = ((w(8) and 0xf) shl 4) or (w(9) shr 4)
                fields[10] = (w(9) shr 2) and 3
                fields[11] = ((w(9) and 3) shl 6) or (w(10) shr 2)
                fields[12] = ((w(10) and 3) shl 6) or (w(11) shr 2)
                fields[13] = ((w(11) and 3) shl 6) or (w(12) shr 2)
                fields[14] = w(12) and 3
                fields[15] = w(13)
                fields[16] = w(14)
                fields[17] = w(15)
            } else {
                fields[0] = (w(0) shl 6) or (w(1) shr 2)
                fields[1] = ((w(1) and 3) shl 12 or (w(2) shl 4) or (w(3) shr 4)) and 0x3fff
                fields[2] = (w(3) shr 2) and 3
                fields[3] = ((w(3) and 3) shl 8) or w(4)
                fields[4] = (w(5) shl 2) or (w(6) shr 6)
                fields[5] = ((w(6) and 0x3f) shl 4) or (w(7) shr 4)
                fields[6] = (w(7) shr 2) and 3
                fields[7] = ((w(7) and 3) shl 8) or w(8)
                fields[8] = ((w(9) shl 2) and 0x3fc) or (w(10) shr 6)
                fields[9] = ((w(10) shl 4) or (w(11) shr 4)) and 0x3ff
                fields[10] = (w(11) shr 2) and 3
                fields[11] = ((w(11) and 3) shl 8) or w(12)
                fields[12] = (((w(13) shl 2) and 0x3fc) or (w(14) shr 6)) and 0x3ff
                fields[13] = ((w(14) shl 4) or (w(15) shr 4)) and 0x3ff
            }
            var cur = 0
            var oddeven0 = 0L; var oddeven1 = 0L
            var nonzero0 = 0L; var nonzero1 = 0L
            var pmul = 0L; var pixelBase = 0L
            for (pix in 0 until perPage) {
                if (pix % 3 == 2) {
                    var base = fields[cur++]
                    if (base == 3) base = 4
                    pixelBase = (base0 shl base).toLong()
                    pmul = 1L shl base
                }
                var epixel = fields[cur++].toLong()
                val odd = pix and 1 == 1
                val oe = if (odd) oddeven1 else oddeven0
                var nz = if (odd) nonzero1 else nonzero0
                if (oe != 0L) {
                    epixel *= pmul
                    if (pixelBase < baseCompare && nz > pixelBase) epixel += nz - pixelBase
                    nz = epixel
                } else {
                    if (odd) oddeven1 = epixel else oddeven0 = epixel
                    if (epixel != 0L) nz = epixel else epixel = nz
                }
                if (odd) nonzero1 = nz else nonzero0 = nz
                val spix = epixel - 0xf
                out[o++] = when {
                    spix in 0..spixCompare.toLong() -> (spix and spixCompare.toLong()).toShort()
                    epixel < 0xf -> 0
                    else -> pixelMask.toShort()
                }
            }
        }
    }
}
