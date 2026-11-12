package app.spektralab.raw

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Writes synthetic RAF files laid out like a Fujifilm X-T2 file: RAF header, embedded JPEG
 * with EXIF (+ Fuji maker note), RAF directory with a generation-4 RAFData block, and the
 * TIFF-like raw section (IFD 0xF000). LibRaw 0.22.2 reads these files; see [RafTest.synthetic]
 * for the files whose LibRaw decode is pinned.
 */
class RafBuilder(
    val rawWidth: Int, val rawHeight: Int, val mosaic: IntArray,
    val model: String = "X-T2",
    /** 36 entries of xtrans_abs (anchored at raw (0, 0)), or null for a Bayer file. */
    val xtrans: IntArray? = XT2,
    val bps: Int = 14,
    val layout: RafReader.Layout = RafReader.Layout.UNPACKED16,
    /** Byte order of the EXIF TIFF (LibRaw reads 16-bit words in this order) and of the raw section. */
    val exifLittleEndian: Boolean = true,
    val visible: Pair<Int, Int>? = null,
    val inset: IntArray? = null,                    // top, left, height, width
    val black: IntArray = IntArray(36) { 1023 },    // 0xF00A values
    val wbGrb: IntArray = intArrayOf(302, 495, 743),
    val orientation: Int = 1,
    val cropMode: Int? = null,
    val fujiLayout: Int = 0x0c,
) {
    companion object {
        /** xtrans_abs of the X-T2 samples (0 R, 1 G, 2 B). */
        val XT2 = intArrayOf(0, 2, 1, 2, 0, 1, 1, 1, 0, 1, 1, 2, 1, 1, 2, 1, 1, 0, 2, 0, 1, 0, 2, 1, 1, 1, 2, 1, 1, 0, 1, 1, 0, 1, 1, 2)

        /** Inverse of [RafReader.unpack14]. */
        fun pack14(samples: IntArray, width: Int, height: Int): ByteArray {
            val lineLen = width * 7 / 4
            val out = ByteArray(lineLen * height)
            val line = ByteArray(lineLen)
            for (row in 0 until height) {
                line.fill(0)
                var sp = 0; var dp = 0
                while (dp < width - 3 && sp < lineLen - 6) {
                    val a = samples[row * width + dp]; val b = samples[row * width + dp + 1]
                    val c = samples[row * width + dp + 2]; val d = samples[row * width + dp + 3]
                    val bits = (a.toLong() shl 42) or (b.toLong() shl 28) or (c.toLong() shl 14) or d.toLong()
                    for (i in 0 until 7) line[sp + i] = (bits ushr (48 - 8 * i)).toByte()
                    sp += 7; dp += 4
                }
                val swapped = lineLen / 4 * 4
                for (i in 0 until lineLen) {
                    val src = if (i < swapped) (i and 3.inv()) + 3 - (i and 3) else i
                    out[row * lineLen + src] = line[i]
                }
            }
            return out
        }
    }

    private fun pixels(): ByteArray = when (layout) {
        RafReader.Layout.UNPACKED16 -> ByteBuffer.allocate(mosaic.size * 2)
            .order(if (exifLittleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
            .apply { mosaic.forEach { putShort(it.toShort()) } }.array()
        RafReader.Layout.PACKED14 -> pack14(mosaic, rawWidth, rawHeight)
        RafReader.Layout.COMPRESSED -> error("use FujiCompressedEncoder")
    }

    var pixelData: ByteArray? = null

    fun build(): ByteArray = buildWithPixels(pixels())

    /** The file with [px] as the raw data (e.g. a compressed stream). */
    fun buildWithPixels(px: ByteArray): ByteArray {
        // embedded JPEG: SOI, APP1 Exif (IFD0 Make/Model/Orientation, EXIF IFD with the maker note), EOI
        val exifIfd = TiffBuilder.Ifd()
        if (cropMode != null) {
            val prefix = ByteArray(12).also { "FUJIFILM".toByteArray().copyInto(it); it[8] = 12 }
            val mn = TiffBuilder(true).build(TiffBuilder.Ifd().short(0x104d, cropMode), prefix)
            exifIfd.undefined(TiffTags.MAKER_NOTE, mn)
        } else exifIfd.short(0x8827, 200)
        val ifd0 = TiffBuilder.Ifd().ascii(TiffTags.MAKE, "FUJIFILM").ascii(TiffTags.MODEL, model)
            .short(TiffTags.ORIENTATION, orientation).subIfds(TiffTags.EXIF_IFD, exifIfd)
        val exif = TiffBuilder(exifLittleEndian).build(ifd0)
        val jpeg = ByteArrayOutputStream().apply {
            write(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte()))
            val len = exif.size + 8
            write(len shr 8); write(len and 0xff)
            write("Exif\u0000\u0000".toByteArray()); write(exif)
            write(byteArrayOf(0xff.toByte(), 0xd9.toByte()))
        }.toByteArray()

        // RAF directory (big endian)
        val dir = ByteArrayOutputStream()
        var entries = 0
        fun tag(t: Int, data: ByteArray) {
            dir.write(t shr 8); dir.write(t and 0xff); dir.write(data.size shr 8); dir.write(data.size and 0xff); dir.write(data); entries++
        }
        fun shorts(vararg v: Int) = ByteArray(v.size * 2) { (v[it / 2] shr (if (it % 2 == 0) 8 else 0)).toByte() }
        tag(0x100, shorts(rawHeight, rawWidth))
        if (inset != null) { tag(0x110, shorts(inset[0], inset[1])); tag(0x111, shorts(inset[2], inset[3])) }
        tag(0x130, byteArrayOf(fujiLayout.toByte(), fujiLayout.toByte(), fujiLayout.toByte(), fujiLayout.toByte()))
        if (xtrans != null) tag(0x131, ByteArray(36) { xtrans[35 - it].toByte() })
        if (visible != null) {
            val rafData = ByteBuffer.allocate(21984).order(ByteOrder.LITTLE_ENDIAN)
            rafData.putShort(0).putShort(0x358).putInt(0x53545257).putInt(visible.first).putInt(visible.second)
            tag(0xc000, rafData.array())
        }
        val dirBytes = ByteBuffer.allocate(4 + dir.size()).putInt(entries).put(dir.toByteArray()).array()

        // raw section
        pixelData = px
        val rawIfd = TiffBuilder.Ifd().long(0xf001, rawWidth).long(0xf002, rawHeight).long(0xf003, bps)
            .long(0xf00a, *black).long(0xf00e, *wbGrb).blobs(0xf007, 0xf008, listOf(px))
        val raw = TiffBuilder(exifLittleEndian).build(TiffBuilder.Ifd().subIfds(0xf000, rawIfd))

        val jpegOff = 148
        val dirOff = jpegOff + jpeg.size
        val rawOff = (dirOff + dirBytes.size + 15) and 15.inv()
        val out = ByteBuffer.allocate(rawOff + raw.size)
        out.put("FUJIFILMCCD-RAW 0201FF129502".toByteArray())
        out.position(0x1c); out.put(model.toByteArray())
        out.position(0x3c); out.put("0100".toByteArray())
        out.position(84); out.putInt(jpegOff).putInt(jpeg.size).putInt(dirOff).putInt(dirBytes.size).putInt(rawOff).putInt(raw.size)
        out.position(jpegOff); out.put(jpeg)
        out.position(dirOff); out.put(dirBytes)
        out.position(rawOff); out.put(raw)
        return out.array()
    }
}

class RafTest {
    private fun scene(w: Int, h: Int, max: Int, black: Int, seed: Int = 3): IntArray {
        val rnd = Random(seed)
        return IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            (black + (max - black) * (0.1 + 0.4 * x / w + 0.3 * y / h) + rnd.nextInt(-40, 41)).toInt().coerceIn(0, max)
        }
    }

    private fun crc(data: ShortArray): Long {
        val b = ByteArray(data.size * 2)
        for (i in data.indices) { b[2 * i] = data[i].toByte(); b[2 * i + 1] = (data[i].toInt() shr 8).toByte() }
        return CRC32().apply { update(b) }.value
    }

    private fun assertMosaic(expected: IntArray, img: RawImage) {
        assertEquals(expected.size, img.rawWidth * img.rawHeight)
        for (i in expected.indices) if (expected[i] != (img.data[i].toInt() and 0xffff)) throw AssertionError("pixel $i: ${expected[i]} vs ${img.data[i].toInt() and 0xffff}")
    }

    @Test
    fun xTransUncompressedLittleEndian() {
        val w = 120; val h = 72
        val m = scene(w, h, 16383, 1023)
        val img = assertNotNull(RawFormatCheck.read("RAF", RafBuilder(w, h, m, visible = 96 to 72, inset = intArrayOf(4, 16, 64, 88)).build()))
        assertMosaic(m, img)
        assertContentEquals(RafBuilder.XT2, img.cfa.colors)
        assertEquals(16383f, img.white)
        assertTrue(img.black.all { it == 1023f })
        // inset (top 4, left 16) is moved to the next multiple of 6, as LibRaw does for X-Trans
        assertEquals(listOf(18, 6, 86, 62), listOf(img.cropX, img.cropY, img.cropWidth, img.cropHeight))
        assertEquals((495.0 / 302).toFloat(), img.wb[0], 1e-5f)
        assertEquals((743.0 / 302).toFloat(), img.wb[2], 1e-5f)
        assertTrue(RawDeveloper.supports(img))
    }

    @Test
    fun bigEndianWordsFollowTheExifByteOrder() {
        val w = 120; val h = 72
        val m = scene(w, h, 16383, 1023)
        val img = assertNotNull(RawFormatCheck.read("RAF", RafBuilder(w, h, m, exifLittleEndian = false, visible = 96 to 72).build()))
        assertMosaic(m, img)
        assertEquals(listOf(12, 0, 96, 72), listOf(img.cropX, img.cropY, img.cropWidth, img.cropHeight))
    }

    @Test
    fun packed14Bit() {
        val w = 128; val h = 36
        val m = scene(w, h, 16383, 1023)
        val b = RafBuilder(w, h, m, layout = RafReader.Layout.PACKED14, visible = 120 to 36)
        val img = assertNotNull(RawFormatCheck.read("RAF", b.build()))
        assertEquals(w * h * 7 / 4, b.pixelData!!.size)
        assertMosaic(m, img)
        // widths that are not a multiple of 16 use the plain 7-byte groups (no 28-byte blocks)
        val w2 = 100
        val m2 = scene(w2, h, 16383, 1023)
        assertMosaic(m2, assertNotNull(RawFormatCheck.read("RAF", RafBuilder(w2, h, m2, layout = RafReader.Layout.PACKED14, visible = 96 to 36).build())))
    }

    @Test
    fun bayerFileUsesLibRawDefaultPattern() {
        val w = 96; val h = 64
        val m = scene(w, h, 16383, 512)
        // visible 90 x 60 -> margins (64-60)>>2<<1 = 2 rows, (96-90)>>2<<1 = 2 columns; filters 0x94949494 relative to them
        val img = assertNotNull(RawFormatCheck.read("RAF", RafBuilder(w, h, m, model = "X-A5", xtrans = null, black = IntArray(4) { 512 }, visible = 90 to 60).build()))
        assertMosaic(m, img)
        assertTrue(img.cfa.isBayer)
        assertEquals(CfaPattern.fromDcrawFilters(0x94949494.toInt()).anchoredAt(2, 2).colors.toList(),
            IntArray(4) { img.cfa.color(it / 2, it % 2) }.toList())
        assertEquals(listOf(2, 2, 90, 60), listOf(img.cropX, img.cropY, img.cropWidth, img.cropHeight))
        // non-X Bayer models get LibRaw's 0x3e00 white
        val old = assertNotNull(RafReader.parse(RafBuilder(w, h, m, model = "XQ1", xtrans = null, black = IntArray(4) { 512 }, visible = 90 to 60).build()))
        assertEquals(0x3e00, old.white)
    }

    @Test
    fun orientationAndCropMode() {
        val w = 120; val h = 72
        val m = scene(w, h, 16383, 1023)
        val img = assertNotNull(RawFormatCheck.read("RAF", RafBuilder(w, h, m, orientation = 6, visible = 96 to 72).build()))
        assertEquals(6, img.orientation)
        // crop mode 1 (GFX full frame): the visible area is the whole raw frame, not the RAFData size
        val info = assertNotNull(RafReader.parse(RafBuilder(w, h, m, visible = 96 to 72, cropMode = 1).build()))
        assertEquals(listOf(0, 0, w, h), listOf(info.left, info.top, info.width, info.height))
    }

    @Test
    fun rejectsOtherFilesAndUnsupportedVariants() {
        assertNull(RafReader.read(ByteArray(0)))
        assertNull(RafReader.read(ByteArray(4096)))
        assertNull(RafReader.read("FUJIFILMCCD-RAW 0201".toByteArray() + ByteArray(300)))
        val w = 120; val h = 72
        val m = scene(w, h, 16383, 1023)
        // SuperCCD (FujiLayout bit 3 clear -> fuji_width)
        assertNull(RafReader.read(RafBuilder(w, h, m, fujiLayout = 0x04, visible = 96 to 72).build()))
        // unknown camera: no colour matrix
        assertNull(RafReader.read(RafBuilder(w, h, m, model = "X-Q99", visible = 96 to 72).build()))
        // pixel data that is neither 16-bit nor 14-bit packed and has no compressed header
        val good = RafBuilder(w, h, m, visible = 96 to 72).build()
        val truncated = good.copyOf(good.size - 1000)
        assertNull(RafReader.read(truncated))
    }

    @Test
    fun corruptInputNeverThrows() {
        val w = 60; val h = 36
        val m = scene(w, h, 16383, 1023)
        val good = RafBuilder(w, h, m, visible = 48 to 36, inset = intArrayOf(2, 2, 30, 40)).build()
        val rnd = Random(11)
        repeat(400) {
            val b = good.copyOf()
            repeat(1 + rnd.nextInt(8)) { b[rnd.nextInt(minOf(b.size, 1200))] = rnd.nextInt(256).toByte() }
            RafReader.read(b)
            RafReader.read(b.copyOf(rnd.nextInt(b.size)))
        }
    }

    /**
     * Synthetic files whose LibRaw 0.22.2 decode is pinned: run with SPEKTRA_RAF_DUMP=dir to
     * write them, then `tools/raf_synthetic_check.py dir` prints LibRaw's raw_image CRC, crop,
     * black, white, WB and pattern for each; those values are the constants below.
     */
    @Test
    fun synthetic() {
        val cases = syntheticFiles()
        val dump = System.getenv("SPEKTRA_RAF_DUMP")?.takeIf { it.isNotEmpty() }
        for ((name, pair) in cases) {
            val (bytes, mosaic) = pair
            dump?.let { File(it, "$name.RAF").writeBytes(bytes) }
            val img = assertNotNull(RawFormatCheck.read("RAF", bytes), name)
            assertMosaic(mosaic, img)
            val expected = LIBRAW_SYNTHETIC[name]
            if (expected != null) {
                assertEquals(expected.crc, crc(img.data), "$name CRC (LibRaw raw_image)")
                assertEquals(expected.crop, listOf(img.cropX, img.cropY, img.cropWidth, img.cropHeight), "$name crop")
                assertEquals(expected.white, img.white, name)
                // LibRaw raw_pattern is relative to its visible origin
                val info = RafReader.parse(bytes)!!
                val pattern = IntArray(expected.pattern.size) { i ->
                    val n = if (expected.pattern.size == 36) 6 else 2
                    img.cfa.color(info.top + i / n, info.left + i % n)
                }
                assertEquals(expected.pattern.map { if (it == 3) 1 else it }, pattern.toList(), "$name pattern")
                assertEquals(expected.black, img.black[0], name)
            }
        }
        assertEquals(cases.keys, LIBRAW_SYNTHETIC.keys)
    }

    class Expected(val crc: Long, val crop: List<Int>, val white: Float, val black: Float, val pattern: List<Int>)

    private fun syntheticFiles(): Map<String, Pair<ByteArray, IntArray>> {
        val out = LinkedHashMap<String, Pair<ByteArray, IntArray>>()
        val w = 6160 / 40 * 2; val h = 96
        val m = scene(w, h, 16383, 1023, seed = 5)
        out["xtrans16le"] = RafBuilder(w, h, m, visible = 288 to 96, inset = intArrayOf(4, 16, 88, 280)).build() to m
        out["xtrans16be"] = RafBuilder(w, h, m, exifLittleEndian = false, visible = 288 to 96).build() to m
        out["xtransMargins"] = RafBuilder(w, h, m, visible = 288 to 88).build() to m
        out["xtrans14packed"] = RafBuilder(w, h, m, layout = RafReader.Layout.PACKED14, visible = 288 to 96).build() to m
        val mb = scene(w, h, 16383, 512, seed = 6)
        out["bayerXA5"] = RafBuilder(w, h, mb, model = "X-A5", xtrans = null, black = IntArray(4) { 512 }, visible = 290 to 94).build() to mb
        return out
    }

    companion object {
        private val XT2_VISIBLE = listOf(0, 2, 1, 2, 0, 1, 1, 1, 0, 1, 1, 2, 1, 1, 2, 1, 1, 0, 2, 0, 1, 0, 2, 1, 1, 1, 2, 1, 1, 0, 1, 1, 0, 1, 1, 2)

        /** From tools/raf_synthetic_check.py (LibRaw 0.22.2); the pattern is LibRaw COLOR() at the visible origin. */
        val LIBRAW_SYNTHETIC: Map<String, Expected> = mapOf(
            "xtrans16le" to Expected(0xe5c04ba8L, listOf(18, 6, 278, 86), 16383f, 1023f, XT2_VISIBLE),
            "xtrans16be" to Expected(0xe5c04ba8L, listOf(12, 0, 286, 96), 16383f, 1023f, XT2_VISIBLE),
            "xtransMargins" to Expected(0xe5c04ba8L, listOf(12, 6, 286, 86), 16383f, 1023f, XT2_VISIBLE),
            "xtrans14packed" to Expected(0xe5c04ba8L, listOf(12, 0, 286, 96), 16383f, 1023f, XT2_VISIBLE),
            "bayerXA5" to Expected(0xe30af9eeL, listOf(8, 0, 290, 94), 16383f, 512f, listOf(0, 1, 3, 2)),
        )
    }
}
