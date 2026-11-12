package app.spektralab.raw

/*
 * Olympus / OM System ORF reader.
 *
 * The compressed-pixel decoder is a port of LibRaw 0.22.2's olympus_load_raw()
 * (src/decoders/olympus14.cpp, Copyright 2024-2025 Alex Tutubalin, LibRaw LLC; LGPL 2.1 /
 * CDDL, ported under GPLv3), whose bit-stream parameters live in the maker note's
 * ImageProcessing IFD (tags 0x0640..0x0653). The 12-bit packed variant follows LibRaw's
 * packed_load_raw with load_flags 24 (dcraw.c, Dave Coffin). Metadata (white balance,
 * black level, ValidBits, crop) follows LibRaw's parseOlympus_ImageProcessing().
 */

/**
 * ORF files ("IIRO" / "IIRS" / "MMOR" TIFF variants, or plain TIFF magic with an Olympus /
 * OM Digital `Make`). Supported pixel layouts, chosen the way LibRaw chooses them:
 *  - Olympus compressed (E-3 and later, 12 or 14 valid bits), parameters from the maker note;
 *  - 12-bit packed (strip length = 1.5 bytes per pixel), 32-bit little-endian word order.
 * Everything else (older 8-bit/other layouts, plain 16-bit unpacked, multi-strip data, DNG,
 * cameras without a colour matrix in [CameraMatrices]) returns null.
 */
object OrfReader {
    fun read(bytes: ByteArray): RawImage? = runCatching { parse(bytes) }.getOrNull()

    private const val IMAGE_PROCESSING = 0x2040

    private fun parse(bytes: ByteArray): RawImage? {
        if (bytes.size < 64) return null
        val tiff = Tiff.parse(bytes, 0, setOf(0x4F52, 0x5352, 42), maxIfds = 4) ?: return null
        val ifd0 = tiff.ifd0
        if (TiffTags.DNG_VERSION in ifd0) return null
        val make = ifd0.string(TiffTags.MAKE) ?: return null
        if (!make.startsWith("OLYMPUS", ignoreCase = true) && !make.startsWith("OM Digi", ignoreCase = true)) return null
        val model = ifd0.string(TiffTags.MODEL) ?: return null

        val width = ifd0.int(TiffTags.IMAGE_WIDTH) ?: return null
        val height = ifd0.int(TiffTags.IMAGE_LENGTH) ?: return null
        if (width < 8 || height < 8 || width.toLong() * height > RawDecodeLimits.MAX_SAMPLES) return null
        if (ifd0.int(TiffTags.SAMPLES_PER_PIXEL, 1) != 1) return null
        val tiffBps = ifd0.int(TiffTags.BITS_PER_SAMPLE, 0)
        val offsets = ifd0.longs(TiffTags.STRIP_OFFSETS, 4) ?: return null
        val counts = ifd0.longs(TiffTags.STRIP_BYTE_COUNTS, 4) ?: return null
        if (offsets.size != 1 || counts.size != 1) return null
        val dataOffset = offsets[0]; val dataBytes = counts[0]
        if (dataOffset <= 0 || dataBytes <= 0 || dataOffset >= bytes.size || dataBytes > bytes.size - dataOffset) return null

        // maker note: ImageProcessing sub-IFD
        val mn = tiff.makerNote() ?: return null
        if (mn.kind != "olympus") return null
        val ipEntry = mn.ifd[IMAGE_PROCESSING] ?: return null
        val ipAbs = mn.ifd.base.toLong() + mn.ifd.value(ipEntry, 0).toLong()
        if (ipAbs < 0 || ipAbs > Int.MAX_VALUE) return null
        val ip = TiffIfd.parse(bytes, ipAbs.toInt(), mn.ifd.base, mn.ifd.littleEndian) ?: return null

        val validBits = ip.int(0x0611) ?: 0

        // CFA: EXIF CFAPattern (2x2), as LibRaw takes it
        val cfa = readCfa(tiff, ifd0) ?: return null
        if (!cfa.isBayer) return null

        if (validBits !in 10..16) return null
        val w = width.toLong(); val h = height.toLong()
        val pixels: ShortArray = when {
            // 12-bit packed, LibRaw load_flags 24 (32-bit little-endian words, MSB first)
            tiffBps == 12 && dataBytes * 2 == w * h * 3 -> {
                if (validBits != 12) return null
                val out = ShortArray(width * height)
                if (!Unpack.packed(bytes, dataOffset.toInt(), 0, width, height, 12, Unpack.BitOrder.MSB32, out)) return null
                out
            }
            (tiffBps == 14 || tiffBps == 16) && dataBytes * 7 > w * h -> {
                decodeCompressed(bytes, dataOffset.toInt(), dataBytes.toInt(), width, height, ip, validBits) ?: return null
            }
            else -> return null
        }

        // levels
        val entry = CameraMatrices.find(make, model) ?: return null
        val matrix = entry.xyzToCamera?.takeIf { entry.colors == 3 } ?: return null
        val scale = if (validBits == 14) 4f else 1f
        val tableBlack = entry.blackLevel.toFloat()
        val fileBlack = ip.ints(0x0600)?.takeIf { it.size >= 4 }
        val blackByPos = FloatArray(4) { i ->
            val row = i / 2; val col = i % 2
            val c = cfa.color(row, col)
            // file order is R, Gr (green in the red row), Gb (green in the blue row), B
            val slot = when (c) {
                0 -> 0
                2 -> 3
                else -> if (cfa.color(row, 1 - col) == 0) 1 else 2
            }
            val file = fileBlack?.get(slot)?.toFloat() ?: (if (entry.black < 0) tableBlack else 0f)
            (file + (if (entry.black > 0) tableBlack else 0f)) * scale
        }
        val white = (if (entry.white > 0) entry.white else (1 shl validBits) - 1).toFloat()
        if (white <= blackByPos.max()) return null

        // crop (ImageProcessing 0x0612..0x0615, Crop Left / Top / Width / Height)
        var cx = 0; var cy = 0; var cw = width; var ch = height
        val l = ip.int(0x0612); val t = ip.int(0x0613); val cwid = ip.int(0x0614); val chei = ip.int(0x0615)
        if (l != null && t != null && cwid != null && chei != null && l >= 0 && t >= 0 && cwid >= 8 && chei >= 8 &&
            l.toLong() + cwid <= width && t.toLong() + chei <= height) { cx = l; cy = t; cw = cwid; ch = chei }

        // colour: as-shot gains from ImageProcessing 0x0100 (R, B in 1/256 units, green 1)
        val wbTag = ip.ints(0x0100)?.takeIf { it.size >= 2 && it[0] > 0 && it[1] > 0 }
        val shot = wbTag?.let { doubleArrayOf(it[0] / 256.0, 1.0, it[1] / 256.0) }
        val flat = DoubleArray(9) { matrix[it / 3][it % 3] }
        val colour = RawColor.fromXyzToCamera(flat, shot) ?: return null
        val orientation = ifd0.int(TiffTags.ORIENTATION)?.takeIf { it in 1..8 } ?: 1

        return RawImage(width, height, pixels, cfa, blackByPos, white, cx, cy, cw, ch,
            colour.wb, colour.camToSrgb, orientation, make = make, model = model)
    }

    /** 2x2 CFA from the EXIF CFAPattern tag (or IFD0's TIFF/EP one); colours 0 R, 1 G, 2 B. */
    private fun readCfa(tiff: Tiff, ifd0: TiffIfd): CfaPattern? {
        val exif = tiff.exif
        val e = exif?.get(TiffTags.EXIF_CFA_PATTERN)
        if (e != null && e.count >= 8) {
            val b = tiff.bytes; val p = e.dataOffset
            val le = tiff.littleEndian
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

    // ---------------------------------------------------------------- compressed pixels

    /** Parameter set from ImageProcessing tags 0x0640..0x0653 (LibRaw tagX640..tagX653). */
    private class Params(private val ip: TiffIfd, val validBits: Int) {
        fun t(tag: Int) = ip.int(tag) ?: 0
        val t640 = t(0x640); val t641 = t(0x641); val t642 = t(0x642); val t643 = t(0x643)
        val t644 = t(0x644); val t645 = t(0x645); val t646 = t(0x646); val t647 = t(0x647)
        val t648 = t(0x648); val t649 = t(0x649); val t650 = t(0x650); val t651 = t(0x651)
        val t652 = maxOf(1, t(0x652)); val t653 = t(0x653)
        fun sane() = t640 in 0..8 && t641 in 0..30 && t642 in 0..65535 && t643 in 0..8 && t644 in 0..15 &&
            t645 in 0..31 && t646 in 0..65535 && t647 in 0..15 && t648 in 0..65535 && t649 in 0..15 &&
            t650 in 0..65535 && t651 in 0..15 && t652 in 1..64 && t653 in 1..8 && validBits in 8..16
    }

    /** LibRaw's buffered_bitpump_t: a 32-bit window over big-endian 16/32-bit reads. */
    private class Pump(private val b: ByteArray, private var pos: Int, private val end: Int) {
        var bitcount = 0
        var bitstorage = 0
        /** Bytes requested beyond [end] (zeros were supplied). */
        var overread = 0; private set

        private fun byte(): Int {
            if (pos < end) return b[pos++].toInt() and 0xff
            overread++; return 0
        }
        private fun get2(): Int { val a = byte(); return (a shl 8) or byte() }
        private fun get4(): Int { val a = byte(); val c = byte(); val d = byte(); return (a shl 24) or (c shl 16) or (d shl 8) or byte() }

        fun replaceData() { bitstorage = get4(); bitcount = 32 }
        fun addData(): Int { bitstorage = (bitstorage shl 16) or get2(); bitcount += 16; return bitcount }
        /** Returns the new vbits. */
        fun skipBit(vbits: Int): Int {
            if (vbits == 0) { replaceData(); bitcount--; return bitcount }
            bitcount = vbits - 1; return vbits - 1
        }
        fun getBits(n: Int): Int {
            while (bitcount < n) addData()
            val r = (bitstorage shl (32 - (bitcount and 0x1f))) ushr (32 - n)
            bitcount -= n
            return r
        }
        fun readHeaderBytes(n: Int): Long { var v = 0L; repeat(n) { v = (v shl 8) or byte().toLong() }; return v }
    }

    private fun decodeCompressed(bytes: ByteArray, offset: Int, length: Int, width: Int, height: Int, ip: TiffIfd, validBits: Int): ShortArray? {
        val p = Params(ip, validBits)
        if (!p.sane()) return null
        val end = offset + length
        val pump = Pump(bytes, offset, end)
        // checkhdr(): t653 bytes equal to 1, then two zero bytes
        if (pump.readHeaderBytes(p.t653) != 1L) return null
        if (pump.readHeaderBytes(2) != 0L) return null

        val out = ShortArray(width * height)
        val oneShl641 = 1 shl p.t641
        val oneShl645 = 1 shl (p.t645 and 0x1f)
        val t640 = p.t640; val t643 = p.t643; val t652 = p.t652
        var c0 = 0; var c1 = 0; var c2 = 0; var c3 = 0
        var lpred = 0; var pred1 = 0; var glc = 0; var gcode = 0

        for (row in 0 until height) {
            val rowBase = row * width
            for (col in 0 until width) {
                val pp = gcode
                gcode = glc
                val psel = if (col > 1 && oneShl645 >= pp) c3 + 1 else 0
                c3 = c2
                c2 = psel
                var wbits: Int
                if (col > 1) {
                    val highbitcount = 32 - Integer.numberOfLeadingZeros(pp and 0xffff)
                    val t1 = if (psel >= p.t646) p.t648 else p.t650
                    val t2 = if (psel >= p.t646) p.t647 else p.t649
                    val tt = maxOf(highbitcount - t1, -1)
                    wbits = t2 + tt + 1
                } else wbits = p.t649
                if (wbits > p.t651) wbits = p.t651
                if (wbits >= 16) return null

                // oly_code()
                if (pump.bitcount == 0) pump.replaceData()
                val v0 = --pump.bitcount
                val highdatabit = (pump.bitstorage ushr v0) and 1
                val t643bits = if (t643 != 0) pump.getBits(t643) else 0
                val t640bits = if (t640 != 0) pump.getBits(t640) else 0
                var vb = pump.bitcount
                var high = 0
                while (true) {
                    vb = pump.skipBit(vb)
                    if (((pump.bitstorage ushr vb) and 1) != 0) break
                    if (t652 == ++high) {
                        high = 0
                        if (wbits != 15) {
                            val i = 15 - wbits
                            while (vb < i) vb = pump.addData()
                            high = (pump.bitstorage shl (32 - (vb and 0x1f))) ushr (wbits + 17)
                            vb -= i
                            pump.bitcount = vb
                        }
                        vb = pump.skipBit(vb)
                        break
                    }
                }
                var low = 0
                if (wbits >= 1) {
                    while (vb < wbits) vb = pump.addData()
                    low = (pump.bitstorage shl (32 - (vb and 0x1f))) ushr (32 - (wbits and 0x1f))
                    pump.bitcount = vb - wbits
                }
                glc = low or (high shl wbits)
                var g = glc xor (-highdatabit)

                val vbits: Int
                if (t643 != 0) {
                    val v = if (col < 2) 0 else pred1
                    if (t643 == 1) {
                        g = t643bits + 2 * (v + g)
                        vbits = v + (g shr 1)
                    } else {
                        g = t643bits + 4 * (v + g)
                        vbits = ((g shr 1) and -2) + v + (g shr 2)
                    }
                } else vbits = 0

                pred1 = c0
                c0 = if (p.t644 == 15) 0 else vbits shr p.t644
                val w = if (col < 2) p.t642 else c1
                var n = if (row < 2) p.t642 else (out[rowBase - 2 * width + col].toInt() and 0xffff) shr t640
                val nw = if (row < 2 || col < 2) p.t642 else (out[rowBase - 2 * width + col - 2].toInt() and 0xffff) shr t640
                c1 = lpred
                if (w < n || nw < w) {
                    if (nw <= n && w >= n) n = w
                    else if (nw < n || w >= n) {
                        if (nw > w || w >= n) {
                            n = if (Math.abs(n - nw) > oneShl641) n + w - nw
                            else if (Math.abs(w - nw) <= oneShl641) (n + w) shr 1
                            else n + w - nw
                        }
                    } else n = w
                }
                lpred = g + n
                out[rowBase + col] = (t640bits + ((g + n) shl t640)).toShort()
            }
            // a stream that ran dry before the last row is not a valid file
            if (pump.overread > 16) return null
        }
        return out
    }
}
