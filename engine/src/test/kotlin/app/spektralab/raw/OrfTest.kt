package app.spektralab.raw

import java.io.File
import java.util.Random
import java.util.zip.CRC32
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ORF reader tests.
 *
 * Synthetic files carry random pixel payloads: the Olympus compressed stream decodes any
 * bit pattern to *some* mosaic, so the expected CRCs below come from LibRaw 0.22.2 decoding
 * the very same bytes (see tools/orf_pef_libraw_check.py, which decodes the files this test
 * writes when SPEKTRA_SYNTH_DUMP is set). A real E-3 file is checked when SPEKTRA_RAW_SAMPLES
 * points at the sample directory.
 */
class OrfTest {
    private class Variant(
        val name: String, val w: Int, val h: Int, val validBits: Int, val packed: Boolean,
        val t640: Int, val t641: Int, val t642: Int, val t643: Int, val t644: Int, val t645: Int, val t646: Int,
        val t647: Int, val t648: Int, val t649: Int, val t650: Int, val t651: Int, val t652: Int, val t653: Int,
        val black: IntArray, val crop: IntArray?, val wb: IntArray, val seed: Long,
        val crc: Long, val expectBlack: FloatArray, val expectWhite: Float,
    )

    private val e3 = intArrayOf(0, 5, 0, 2, 5, 4, 3, 2, 3, 4, 7, 15, 12, 5)

    private val variants = listOf(
        Variant("e3-params-12bit", 96, 64, 12, false, 0, 5, 0, 2, 5, 4, 3, 2, 3, 4, 7, 15, 12, 5,
            intArrayOf(53, 55, 56, 54), intArrayOf(4, 2, 80, 56), intArrayOf(494, 388), 11L, 0x778c9931L, floatArrayOf(53f, 55f, 56f, 54f), 3993f),
        Variant("t643-1-t640-2", 80, 40, 12, false, 2, 4, 3, 1, 6, 3, 2, 3, 2, 5, 6, 12, 9, 4,
            intArrayOf(60, 60, 60, 60), null, intArrayOf(300, 600), 12L, 0xa947f0d9L, floatArrayOf(60f, 60f, 60f, 60f), 3993f),
        Variant("t643-0-t644-15-14bit", 72, 36, 14, false, 1, 5, 8, 0, 15, 5, 1, 3, 2, 6, 5, 14, 8, 6,
            intArrayOf(50, 52, 52, 50), intArrayOf(6, 4, 60, 30), intArrayOf(256, 256), 13L, 0x66aae4f8L, floatArrayOf(200f, 208f, 208f, 200f), 3993f),
        Variant("packed12", 64, 32, 12, true, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            intArrayOf(40, 42, 42, 40), null, intArrayOf(512, 400), 14L, 0x6012252bL, floatArrayOf(40f, 42f, 42f, 40f), 3993f),
    )

    private fun payload(v: Variant): ByteArray {
        val rnd = Random(v.seed)
        return if (v.packed) ByteArray(v.w * v.h * 3 / 2).also { rnd.nextBytes(it) }
        else {
            // t653 header bytes (…0, 1), two zero bytes, then 6 random bytes per pixel
            val hdr = ByteArray(v.t653 + 2).also { it[v.t653 - 1] = 1 }
            val body = ByteArray(v.w * v.h * 6).also { rnd.nextBytes(it) }
            hdr + body
        }
    }

    private fun build(v: Variant, make: String = "OLYMPUS IMAGING CORP.", model: String = "E-3", magic: String = "IIRO"): ByteArray {
        val ip = TiffBuilder.Ifd()
        ip.short(0x0100, v.wb[0], v.wb[1], 256, 256)
        ip.short(0x0600, *v.black)
        ip.short(0x0611, v.validBits, 0)
        v.crop?.let { ip.short(0x0612, it[0]); ip.short(0x0613, it[1]); ip.short(0x0614, it[2]); ip.short(0x0615, it[3]) }
        if (!v.packed) {
            val t = intArrayOf(v.t640, v.t641, v.t642, v.t643, v.t644, v.t645, v.t646, v.t647, v.t648, v.t649, v.t650, v.t651, v.t652, v.t653)
            val tags = intArrayOf(0x640, 0x641, 0x642, 0x643, 0x644, 0x645, 0x646, 0x647, 0x648, 0x649, 0x650, 0x651, 0x652, 0x653)
            for (i in t.indices) ip.short(tags[i], t[i])
        }
        val mnIfd = TiffBuilder.Ifd().subIfds(0x2040, ip)
        val mn = TiffBuilder(true).build(mnIfd, "OLYMPUS\u0000II\u0003\u0000".toByteArray(Charsets.ISO_8859_1))
        val cfa = byteArrayOf(2, 0, 2, 0, 0, 1, 1, 2)                 // 2x2 RGGB, shorts little endian
        val exif = TiffBuilder.Ifd().undefined(37500, mn).undefined(TiffTags.EXIF_CFA_PATTERN, cfa)
        val ifd0 = TiffBuilder.Ifd()
            .ascii(TiffTags.MAKE, make).ascii(TiffTags.MODEL, model)
            .long(TiffTags.IMAGE_WIDTH, v.w).long(TiffTags.IMAGE_LENGTH, v.h)
            .short(TiffTags.BITS_PER_SAMPLE, if (v.packed) 12 else 16).short(TiffTags.COMPRESSION, 1).short(TiffTags.PHOTOMETRIC, 1)
            .short(TiffTags.SAMPLES_PER_PIXEL, 1).short(TiffTags.ORIENTATION, 1).short(TiffTags.ROWS_PER_STRIP, v.h)
            .blobs(TiffTags.STRIP_OFFSETS, TiffTags.STRIP_BYTE_COUNTS, listOf(payload(v)))
            .subIfds(TiffTags.EXIF_IFD, exif)
        val header = byteArrayOf(magic[0].code.toByte(), magic[1].code.toByte(), magic[2].code.toByte(), magic[3].code.toByte(), 8, 0, 0, 0)
        return TiffBuilder(true).build(ifd0, header)
    }

    private fun crc(data: ShortArray, n: Int = data.size): Long {
        val c = CRC32()
        val b = ByteArray(n * 2)
        for (i in 0 until n) { b[2 * i] = data[i].toByte(); b[2 * i + 1] = (data[i].toInt() shr 8).toByte() }
        c.update(b)
        return c.value
    }

    /** Writes the synthetic files for tools/orf_pef_libraw_check.py when asked to. */
    @Test
    fun dumpSyntheticFilesForLibRaw() {
        val dir = System.getenv("SPEKTRA_SYNTH_DUMP") ?: return
        File(dir).mkdirs()
        for (v in variants) File(dir, "orf_${v.name}.ORF").writeBytes(build(v))
    }

    @Test
    fun syntheticFilesDecodeLikeLibRaw() {
        for (v in variants) {
            val img = assertNotNull(OrfReader.read(build(v)), v.name)
            assertEquals(v.w, img.rawWidth, v.name); assertEquals(v.h, img.rawHeight, v.name)
            assertEquals(v.crc, crc(img.data), "mosaic CRC ${v.name}")
            assertContentEquals(v.expectBlack, img.black, v.name)
            assertEquals(v.expectWhite, img.white, v.name)
            assertEquals("RGGB", img.cfa.colors.joinToString("") { "RGB"[it].toString() }, v.name)
            val c = v.crop
            assertEquals(listOf(c?.get(0) ?: 0, c?.get(1) ?: 0, c?.get(2) ?: v.w, c?.get(3) ?: v.h),
                listOf(img.cropX, img.cropY, img.cropWidth, img.cropHeight), v.name)
            assertEquals(v.wb[0] / 256f, img.wb[0] / img.wb[1], 1e-4f, v.name)
            assertEquals(v.wb[1] / 256f, img.wb[2] / img.wb[1], 1e-4f, v.name)
        }
    }

    @Test
    fun rejectsOtherFilesAndTruncatedData() {
        val v = variants[0]
        val good = build(v)
        assertNotNull(OrfReader.read(good))
        assertNull(OrfReader.read(build(v, make = "Canon", model = "EOS 5D")))
        assertNull(OrfReader.read(build(v, model = "No Such Camera")))
        assertNull(OrfReader.read(ByteArray(0)))
        assertNull(OrfReader.read(ByteArray(100) { 7 }))
        assertNull(OrfReader.read(good.copyOf(good.size / 2)))
        assertNull(OrfReader.read(good.copyOf(300)))
        // a wrong stream header (tag 0x653 says 5 bytes, 1 is expected inside) must not decode
        val bad = good.copyOf()
        val img = OrfReader.read(good)!!
        assertTrue(img.data.isNotEmpty())
        val off = good.indexOfLast { it == 1.toByte() }                     // not the header; just corrupt every byte of the first 8 data bytes
        var found = -1
        for (i in 0 until good.size - 8) if (good[i].toInt() == 0 && good[i + 1].toInt() == 0 && good[i + 2].toInt() == 0 && good[i + 3].toInt() == 0 && good[i + 4].toInt() == 1 && good[i + 5].toInt() == 0 && good[i + 6].toInt() == 0) { found = i; break }
        assertTrue(found >= 0 && off >= 0)
        bad[found + 4] = 2
        assertNull(OrfReader.read(bad))
    }

    @Test
    fun randomGarbageNeverThrows() {
        val rnd = Random(5)
        val good = build(variants[0])
        repeat(200) {
            val b = good.copyOf()
            repeat(1 + rnd.nextInt(6)) { b[rnd.nextInt(600)] = rnd.nextInt(256).toByte() }
            OrfReader.read(b)
        }
    }

    // ---------------------------------------------------------------- real sample
    private fun sample(name: String): ByteArray {
        val dir = System.getenv("SPEKTRA_RAW_SAMPLES")
        Assume.assumeTrue("SPEKTRA_RAW_SAMPLES not set", !dir.isNullOrEmpty())
        val f = File(dir, name)
        Assume.assumeTrue("missing sample $name", f.isFile)
        return f.readBytes()
    }

    @Test
    fun olympusE3MatchesLibRaw() {
        val bytes = sample("ORF/RAW_OLYMPUS_E3.ORF")
        val t0 = System.nanoTime()
        val img = assertNotNull(OrfReader.read(bytes))
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("E-3 ORF decode: $ms ms")
        assertEquals(3720, img.rawWidth); assertEquals(2800, img.rawHeight)
        assertEquals(0x17171cf1L, crc(img.data))                          // LibRaw raw_image, not black-subtracted
        assertEquals(3993f, img.white)
        assertEquals("RGGB", img.cfa.colors.joinToString("") { "RGB"[it].toString() })
        // LibRaw black_level_per_channel = R 53, G1 55, B 53, G2 55
        assertContentEquals(floatArrayOf(53f, 55f, 55f, 53f), img.black)
        assertEquals(listOf(46, 56, 3648, 2736), listOf(img.cropX, img.cropY, img.cropWidth, img.cropHeight))
        assertEquals(1, img.orientation)
        // camera_whitebalance 1.9296875 1.0 1.515625
        assertEquals(1.9296875f, img.wb[0] / img.wb[1], 1e-5f)
        assertEquals(1.515625f, img.wb[2] / img.wb[1], 1e-5f)
        assertEquals("E-3", img.model.trim())
        assertTrue(RawDeveloper.supports(img))
    }

    @Test
    fun olympusE3DevelopsToSaneColour() {
        val img = assertNotNull(OrfReader.read(sample("ORF/RAW_OLYMPUS_E3.ORF")))
        val dev = RawDeveloper.develop(img, 912)
        var r = 0.0; var g = 0.0; var b = 0.0; var n = 0
        for (y in 0 until dev.height) for (x in 0 until dev.width) { r += dev.red(x, y); g += dev.green(x, y); b += dev.blue(x, y); n++ }
        println("E-3 develop ${dev.width}x${dev.height} mean linear sRGB: ${r / n} ${g / n} ${b / n}")
        // LibRaw (camera WB, sRGB primaries, linear, no brightening) averages 0.1442 / 0.1324 / 0.1109 over the same crop
        assertEquals(0.1442, r / n, 0.1442 * 0.05)
        assertEquals(0.1324, g / n, 0.1324 * 0.05)
        assertEquals(0.1109, b / n, 0.1109 * 0.05)
    }
}
