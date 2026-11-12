package app.spektralab.raw

import org.junit.Assume.assumeTrue
import java.io.File
import java.util.zip.CRC32
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sony ARW: synthetic files for every supported layout (in-test encoders; when
 * SPEKTRA_RAWPY names a python with rawpy, LibRaw decodes the same files and must agree), and
 * the real samples under SPEKTRA_RAW_SAMPLES pinned to LibRaw 0.22.2's output.
 */
class ArwTest {
    // ------------------------------------------------------------------ synthetic writers

    /** Knee points of tag 0x7010 as real bodies write them (A7 IV / A7S II). */
    private val knees = intArrayOf(8000, 10400, 12900, 14100)

    /** 12-bit tone curve from [knees], computed independently of the reader. */
    private fun curve(): IntArray {
        val k = intArrayOf(0) + knees.map { (it shr 2) and 0xfff } + intArrayOf(4095)
        val c = IntArray(4096) { it }
        var v = 0
        for (seg in 0 until 5) for (j in k[seg] + 1..k[seg + 1]) { v += 1 shl seg; c[j] = v }
        return c
    }

    class Encoded(val file: ByteArray, val mosaic: ShortArray)

    /**
     * ARW2 encoder: per row, blocks of 16 same-colour pixels (every other column of 32), each
     * with 11-bit max / min, their indices and 14 7-bit deltas, LSB first in 128 bits.
     * Returns the file and the mosaic a correct decoder must produce (the deltas are lossy).
     */
    private fun encodeArw2(w: Int, h: Int, value: (Int, Int) -> Int): Pair<ByteArray, ShortArray> {
        val cv = curve()
        val data = ByteArray(w * h)
        val mosaic = ShortArray(w * h)
        for (r in 0 until h) for (g in 0 until w / 32) for (parity in 0..1) {
            val cols = IntArray(16) { g * 32 + parity + 2 * it }
            val v = IntArray(16) { value(r, cols[it]).coerceIn(0, 0x7ff) }
            var imax = (0 until 16).maxBy { v[it] }
            var imin = (0 until 16).minBy { v[it] }
            if (imax == imin) imin = (imax + 1) % 16
            val max = v[imax]; val min = v[imin]
            var sh = 0
            while (sh < 4 && (0x80 shl sh) <= max - min) sh++
            val bits = java.math.BigInteger.valueOf(max.toLong() or (min.toLong() shl 11) or (imax.toLong() shl 22) or (imin.toLong() shl 26))
            var acc = bits
            var bit = 30
            val recon = IntArray(16)
            for (i in 0 until 16) {
                recon[i] = when (i) {
                    imax -> max
                    imin -> min
                    else -> {
                        val d = ((v[i] - min) shr sh) and 0x7f
                        acc = acc.or(java.math.BigInteger.valueOf(d.toLong()).shiftLeft(bit)); bit += 7
                        minOf((d shl sh) + min, 0x7ff)
                    }
                }
            }
            val block = acc.toByteArray().reversedArray()               // little endian
            val at = r * w + (g * 2 + parity) * 16
            for (i in 0 until minOf(16, block.size)) data[at + i] = block[i]
            for (i in 0 until 16) mosaic[r * w + cols[i]] = cv[recon[i] shl 1].toShort()
        }
        return data to mosaic
    }

    /** Sony-style TIFF: IFD0 (Make / Model / SubIFD / DNGPrivateData) and the raw SubIFD. */
    private fun sonyFile(
        model: String, w: Int, h: Int, compression: Int, bps: Int,
        pixelBlobs: List<ByteArray>, tile: Pair<Int, Int>? = null,
        plainLevels: Boolean = true, sr2: Boolean = false, crop: IntArray? = null,
        cfa: IntArray = intArrayOf(0, 1, 1, 2), orientation: Int = 1,
    ): ByteArray {
        val raw = TiffBuilder.Ifd()
            .long(TiffTags.NEW_SUBFILE_TYPE, 0)
            .short(TiffTags.IMAGE_WIDTH, w).short(TiffTags.IMAGE_LENGTH, h)
            .short(TiffTags.BITS_PER_SAMPLE, bps).short(TiffTags.COMPRESSION, compression)
            .short(TiffTags.PHOTOMETRIC, 32803).short(TiffTags.SAMPLES_PER_PIXEL, 1)
            .short(TiffTags.CFA_REPEAT_PATTERN_DIM, 2, 2).byte(TiffTags.CFA_PATTERN, *cfa)
            .short(0x7010, *knees)
        if (tile != null) {
            raw.long(TiffTags.TILE_WIDTH, tile.first).long(TiffTags.TILE_LENGTH, tile.second)
            raw.blobs(TiffTags.TILE_OFFSETS, TiffTags.TILE_BYTE_COUNTS, pixelBlobs)
        } else {
            raw.short(TiffTags.ROWS_PER_STRIP, h)
            raw.blobs(TiffTags.STRIP_OFFSETS, TiffTags.STRIP_BYTE_COUNTS, pixelBlobs)
        }
        if (plainLevels) {
            raw.short(0x7310, 510, 512, 514, 516)                     // R G1 G2 B
            raw.short(0x7313, 2380, 1024, 1024, 1920)
        }
        crop?.let { raw.long(0x74c7, it[0], it[1]).long(0x74c8, it[2], it[3]) }
        fun build(privOffset: Int): ByteArray {
            val ifd0 = TiffBuilder.Ifd()
                .long(TiffTags.NEW_SUBFILE_TYPE, 1)
                .ascii(TiffTags.MAKE, "SONY").ascii(TiffTags.MODEL, model)
                .short(TiffTags.ORIENTATION, orientation)
                .subIfds(TiffTags.SUB_IFDS, raw)
            if (sr2) ifd0.byte(0xc634, privOffset and 0xff, (privOffset ushr 8) and 0xff, (privOffset ushr 16) and 0xff, privOffset ushr 24)
            return TiffBuilder(true).build(ifd0)
        }
        if (!sr2) return build(0)
        val base = build(0)
        val privOff = (base.size + 3) and 3.inv()
        val sr2Off = privOff + 44
        // SR2SubIFD: 0x7310 black (R G1 G2 B) and 0x7313 WB (R G1 G2 B), values after the entries
        val sr2 = java.nio.ByteBuffer.allocate(48).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        sr2.putShort(2)
        sr2.putShort(0x7310.toShort()); sr2.putShort(3); sr2.putInt(4); sr2.putInt(sr2Off + 30)
        sr2.putShort(0x7313.toShort()); sr2.putShort(8); sr2.putInt(4); sr2.putInt(sr2Off + 38)
        sr2.putInt(0)
        intArrayOf(600, 604, 604, 608).forEach { sr2.putShort(it.toShort()) }
        intArrayOf(2004, 1024, 1024, 2212).forEach { sr2.putShort(it.toShort()) }
        val sr2Bytes = sr2.array()
        val key = 0x1234ABCD
        ArwReader.sonyDecrypt(sr2Bytes, 0, sr2Bytes.size / 4, key)    // the cipher is an XOR stream
        val priv = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        priv.putShort(3)
        priv.putShort(0x7200.toShort()); priv.putShort(4); priv.putInt(1); priv.putInt(sr2Off)
        priv.putShort(0x7201.toShort()); priv.putShort(4); priv.putInt(1); priv.putInt(sr2Bytes.size)
        priv.putShort(0x7221.toShort()); priv.putShort(7); priv.putInt(4); priv.putInt(key)   // UNDEFINED[4], as cameras write it
        priv.putInt(0)
        val head = build(privOff)
        check(head.size == base.size)
        return head.copyOf(privOff) + priv.array() + sr2Bytes
    }

    private fun scene(r: Int, c: Int, max: Int, rnd: Random): Int {
        val ramp = (c * 37 + r * 11) % max
        return if (rnd.nextInt(8) == 0) rnd.nextInt(max) else ramp
    }

    private fun crc(d: ShortArray): Long {
        val b = ByteArray(d.size * 2)
        for (i in d.indices) { b[2 * i] = d[i].toByte(); b[2 * i + 1] = (d[i].toInt() ushr 8).toByte() }
        return CRC32().apply { update(b) }.value
    }

    // ------------------------------------------------------------------ synthetic tests

    private fun arw2File(sr2: Boolean = false, crop: IntArray? = null): Encoded {
        val w = 256; val h = 40
        val rnd = Random(7)
        val (data, mosaic) = encodeArw2(w, h) { r, c -> if (r % 9 == 0) 300 + (c % 5) else scene(r, c, 2048, rnd) }
        return Encoded(sonyFile("ILCE-7M3", w, h, 32767, 14, listOf(data), sr2 = sr2, crop = crop), mosaic)
    }

    @Test fun arw2Lossy() {
        val e = arw2File(crop = intArrayOf(8, 4, 224, 30))
        val img = assertNotNull(ArwReader.read(e.file))
        assertContentEquals(e.mosaic, img.data)
        assertEquals(256, img.rawWidth); assertEquals(40, img.rawHeight)
        assertEquals(listOf(8, 4, 224, 30), listOf(img.cropX, img.cropY, img.cropWidth, img.cropHeight))
        assertContentEquals(floatArrayOf(510f, 512f, 514f, 516f), img.black)
        assertEquals(16383f, img.white)
        assertEquals(2380f / 1024, img.wb[0], 1e-5f); assertEquals(1920f / 1024, img.wb[2], 1e-5f)
        assertEquals(CfaPattern.RGGB.colors.toList(), img.cfa.colors.toList())
        libRaw(e.file, img, "arw2")
    }

    @Test fun encryptedSr2LevelsOverridePlainTags() {
        val e = arw2File(sr2 = true)
        val img = assertNotNull(ArwReader.read(e.file))
        assertContentEquals(e.mosaic, img.data)
        assertContentEquals(floatArrayOf(600f, 604f, 604f, 608f), img.black)
        assertEquals(2004f / 1024, img.wb[0], 1e-5f); assertEquals(2212f / 1024, img.wb[2], 1e-5f)
        libRaw(e.file, img, "sr2")
    }

    @Test fun uncompressed14BitInBothCompressionCodes() {
        for (comp in intArrayOf(1, 32767)) {
            val w = 128; val h = 48
            val rnd = Random(comp)
            val px = ShortArray(w * h) { (scene(it / w, it % w, 16384, rnd)).toShort() }
            val blob = ByteArray(w * h * 2).also { b -> for (i in px.indices) { b[2 * i] = px[i].toByte(); b[2 * i + 1] = (px[i].toInt() ushr 8).toByte() } }
            val file = sonyFile("ILCE-7SM2", w, h, comp, 14, listOf(blob), orientation = 6)
            val img = assertNotNull(ArwReader.read(file), "compression $comp")
            assertContentEquals(px, img.data)
            assertEquals(6, img.orientation)
            assertEquals(16383f, img.white)
            libRaw(file, img, "unpacked$comp")
        }
    }

    @Test fun packed12Bit() {
        val w = 128; val h = 48
        val rnd = Random(12)
        val px = IntArray(w * h) { scene(it / w, it % w, 4096, rnd) }
        val blob = Pack.pack(px, 12, Unpack.BitOrder.LSB, w, w * 3 / 2).copyOf(w * h * 3 / 2)
        val file = sonyFile("DSLR-A700", w, h, 32767, 12, listOf(blob))
        val img = assertNotNull(ArwReader.read(file))
        assertContentEquals(px.map { it.toShort() }.toShortArray(), img.data)
        assertEquals(4095f, img.white)
        // the plain 0x7310 levels (510..516) are 14-bit: divided by 4 like LibRaw
        assertContentEquals(floatArrayOf(127f, 127f, 128f, 128f), img.black)
        libRaw(file, img, "packed12")
    }

    @Test fun losslessJpegTiles() {
        // 2 x 3 tiles (LibRaw treats exactly 4 TileOffsets as a Sinar 4-shot file)
        val w = 192; val h = 160
        val tw = 128; val th = 64
        val rnd = Random(3)
        val px = IntArray(w * h) { scene(it / w, it % w, 16384, rnd) }
        val blobs = ArrayList<ByteArray>()
        for (ty in 0 until h step th) for (tx in 0 until w step tw) {
            // each tile is a full tw x th LJPEG (the right / bottom tiles hang over the frame)
            val s = IntArray(tw / 2 * th / 2 * 4)
            for (y in 0 until th / 2) for (x in 0 until tw / 2) for (k in 0..3) {
                val rr = ty + 2 * y + k / 2; val cc = tx + 2 * x + k % 2
                s[(y * tw / 2 + x) * 4 + k] = if (rr < h && cc < w) px[rr * w + cc] else 0
            }
            // LibRaw ignores the scan's table selectors (component c uses DHT table c, else the
            // last one defined), so pick selectors on which both readings agree: 0, 1, 1, 1
            blobs += LjpegEncoder.encode(s, tw / 2, th / 2, 4, 14, 1, tableFor = intArrayOf(0, 1, 1, 1))
        }
        val file = sonyFile("ILCE-7M4", w, h, 7, 14, blobs, tile = tw to th)
        val img = assertNotNull(ArwReader.read(file))
        assertContentEquals(px.map { it.toShort() }.toShortArray(), img.data)
        libRaw(file, img, "ljpeg")
    }

    @Test fun rejectsWhatItDoesNotDecode() {
        val e = arw2File()
        // other makers, DNG, ARW1 sizes, unknown compression, truncation, garbage
        assertNull(ArwReader.read(e.file.copyOf().also { patchAscii(it, "SONY", "SANY") }))
        assertNull(ArwReader.read(e.file.copyOf(e.file.size - 100)))
        assertNull(ArwReader.read(ByteArray(0)))
        assertNull(ArwReader.read(ByteArray(64) { 0x49 }))
        val w = 64; val h = 32
        assertNull(ArwReader.read(sonyFile("ILCE-7M3", w, h, 32767, 14, listOf(ByteArray(w * h - 20)))), "ARW1-sized")
        assertNull(ArwReader.read(sonyFile("ILCE-7M3", w, h, 32766, 14, listOf(ByteArray(w * h)))), "ARW6")
        assertNull(ArwReader.read(sonyFile("ILCE-6700", w, h, 7, 14, listOf(ByteArray(w * h)), tile = 32 to 32)), "LJPEG on a non-gated model")
        assertNull(ArwReader.read(sonyFile("UNKNOWN-1", w, h, 32767, 14, listOf(ByteArray(w * h)))), "no colour matrix")
        // random corruption of a valid file never throws
        val rnd = Random(1)
        repeat(200) {
            val f = e.file.copyOf()
            repeat(1 + rnd.nextInt(20)) { f[rnd.nextInt(f.size)] = rnd.nextInt(256).toByte() }
            ArwReader.read(f)
        }
        // LJPEG tiles with corrupt entropy data -> null
        val tile = LjpegEncoder.encode(IntArray(16 * 16 * 4) { it and 0x3fff }, 16, 16, 4, 14, 1)
        val bad = tile.copyOf().also { for (i in it.size - 40 until it.size - 2) it[i] = 0xff.toByte() }
        assertNull(ArwReader.read(sonyFile("ILCE-7M4", 32, 32, 7, 14, listOf(bad), tile = 32 to 32)))
    }

    private fun patchAscii(b: ByteArray, from: String, to: String) {
        val f = from.toByteArray()
        for (i in 0..b.size - f.size) if ((f.indices).all { b[i + it] == f[it] }) { to.toByteArray().copyInto(b, i); return }
    }

    /** The key stream itself is proven by the A7S II samples, whose WB only exists in the encrypted SR2SubIFD. */
    @Test fun decryptIsAnInvolution() {
        val buf = ByteArray(64)
        ArwReader.sonyDecrypt(buf, 0, 16, 0x11223344)
        assertTrue(buf.any { it != 0.toByte() })
        ArwReader.sonyDecrypt(buf, 0, 16, 0x11223344)
        assertTrue(buf.all { it == 0.toByte() })
    }

    // ------------------------------------------------------------------ LibRaw cross-check

    /** When SPEKTRA_RAWPY is set, LibRaw must decode [file] to the same mosaic and levels. */
    private fun libRaw(file: ByteArray, img: RawImage, name: String) {
        val python = System.getenv("SPEKTRA_RAWPY") ?: return
        val script = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "tools/arw_libraw_check.py") }.firstOrNull { it.isFile } ?: error("tools/arw_libraw_check.py not found")
        val f = File.createTempFile("arw-$name", ".ARW").apply { deleteOnExit(); writeBytes(file) }
        System.getenv("SPEKTRA_ARW_DUMP")?.let { File(it, "$name.ARW").writeBytes(file) }
        val p = ProcessBuilder(python, "-I", script.path, f.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(0, p.waitFor(), out)
        fun num(key: String) = Regex("\"$key\": (-?[0-9.]+)").find(out)?.groupValues?.get(1) ?: error("$key in $out")
        fun list(key: String) = Regex("\"$key\": \\[([^\\]]*)]").find(out)!!.groupValues[1].split(",").map { it.trim().toDouble() }
        assertEquals(crc(img.data), num("crc").toLong(), "$name: LibRaw mosaic ($out)")
        assertEquals(img.white, num("white").toFloat(), "$name white")
        val b = list("black")                                  // R G B G2
        assertEquals(listOf(img.black[0], img.black[1], img.black[3], img.black[2]).map { it.toDouble() }, b, "$name black")
        val wb = list("wb")
        if (wb[1] > 0) { assertEquals(wb[0] / wb[1], img.wb[0].toDouble(), 1e-5); assertEquals(wb[2] / wb[1], img.wb[2].toDouble(), 1e-5) }
        val crop = list("crop").map { it.toInt() }
        if (crop[2] > 0) assertEquals(crop, listOf(img.cropX, img.cropY, img.cropWidth, img.cropHeight), "$name crop")
        else assertEquals(listOf(num("width").toInt(), num("height").toInt()), listOf(img.cropWidth, img.cropHeight), "$name visible")
    }

    // ------------------------------------------------------------------ real samples

    private fun sample(name: String): ByteArray {
        val dir = System.getenv("SPEKTRA_RAW_SAMPLES")
        assumeTrue("SPEKTRA_RAW_SAMPLES not set", dir != null)
        val f = File(dir, "ARW/$name")
        assumeTrue("$f missing", f.isFile)
        return f.readBytes()
    }

    private class Expect(
        val rawW: Int, val rawH: Int, val crc: Long, val crop: List<Int>,
        val black: Float, val white: Float, val wb: List<Int>, val libRawRatios: DoubleArray,
        /** Relative tolerance: the 0.1 s frame sits a few DN above black, where clipping order matters. */
        val tol: Double = 0.04,
    )

    private fun check(name: String, e: Expect) {
        val bytes = sample(name)
        val t0 = System.nanoTime()
        val img = assertNotNull(ArwReader.read(bytes), name)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("$name decoded in $ms ms")
        assertEquals(e.rawW, img.rawWidth); assertEquals(e.rawH, img.rawHeight)
        assertEquals(e.crc, crc(img.data), "$name mosaic CRC vs LibRaw raw_image")
        assertEquals(e.crop, listOf(img.cropX, img.cropY, img.cropWidth, img.cropHeight))
        assertTrue(img.black.all { it == e.black }, img.black.toList().toString())
        assertEquals(e.white, img.white)
        assertEquals(e.wb[0].toFloat() / e.wb[1], img.wb[0], 1e-5f)
        assertEquals(e.wb[2].toFloat() / e.wb[1], img.wb[2], 1e-5f)
        assertEquals(1, img.orientation)
        assertEquals(listOf(0, 1, 1, 2), img.cfa.colors.toList())
        assertTrue(RawDeveloper.supports(img))
        // developed colour: mean R/G and B/G against LibRaw's linear sRGB render
        // (postprocess(use_camera_wb, sRGB, gamma (1, 1), no_auto_bright, half_size, clip highlights))
        val t1 = System.nanoTime()
        val dev = RawDeveloper.develop(img, 1024, options = RawDeveloper.Options(reconstructHighlights = false, chromaDenoise = false))
        println("$name developed in ${(System.nanoTime() - t1) / 1_000_000} ms")
        var r = 0.0; var g = 0.0; var b = 0.0
        for (y in 0 until dev.height) for (x in 0 until dev.width) {
            r += dev.red(x, y).coerceIn(0f, 1f); g += dev.green(x, y).coerceIn(0f, 1f); b += dev.blue(x, y).coerceIn(0f, 1f)
        }
        println("$name developed R/G %.4f B/G %.4f (LibRaw %.4f %.4f)".format(r / g, b / g, e.libRawRatios[0], e.libRawRatios[1]))
        assertEquals(e.libRawRatios[0], r / g, e.tol * e.libRawRatios[0], "$name R/G")
        assertEquals(e.libRawRatios[1], b / g, e.tol * e.libRawRatios[1], "$name B/G")
    }

    // Constants from LibRaw 0.22.2 (rawpy 0.28.0): crc32 of raw_image as little-endian uint16,
    // crop = (crop_left_margin, crop_top_margin, crop_width, crop_height), camera_whitebalance.
    @Test fun a7sIIUncompressedDark() = check("00001_00_0.1s.ARW",
        Expect(4288, 2848, 0xc4d28639L, listOf(0, 0, 4240, 2832), 512f, 16383f, listOf(2004, 1024, 2212), doubleArrayOf(1.3137, 0.4894), tol = 0.1))

    @Test fun a7sIIUncompressedBright() = check("00001_00_10s.ARW",
        Expect(4288, 2848, 0x5b98588aL, listOf(0, 0, 4240, 2832), 512f, 16383f, listOf(1976, 1024, 2316), doubleArrayOf(1.3431, 0.6249)))

    /** Despite its MANIFEST label this file is ARW2 lossy (Compression 32767, 1 byte per pixel). */
    @Test fun a7IVArw2Lossy() = check("RAW_SONY_ILCE-7M4.ARW",
        Expect(4736, 3132, 0x436bf20eL, listOf(44, 30, 4608, 3072), 512f, 16383f, listOf(2380, 1024, 1920), doubleArrayOf(0.8831, 0.7276)))
}
