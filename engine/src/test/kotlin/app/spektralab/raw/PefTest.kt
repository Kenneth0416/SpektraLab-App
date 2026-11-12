package app.spektralab.raw

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PEF reader tests. There is no full real PEF file in the reference kit, so the codec is
 * proven the other way round: an in-test encoder writes Pentax-compressed (and uncompressed)
 * files, [PefReader] must give the pixels back, and LibRaw 0.22.2 must decode the very same
 * files to the same mosaic, levels, white balance, crop and CFA (expected values were taken
 * from LibRaw with tools/orf_pef_libraw_check.py on the files this test writes when
 * SPEKTRA_SYNTH_DUMP is set). The Huffman table is the one from a real K-3 II file.
 * No real PEF image has been decoded.
 */
class PefTest {
    /** Tag 0x0220 of a real Pentax K-3 II file (bep/imagemeta sample). */
    private val huffmanTable: ByteArray = hex(
        "030000000c0000000000000000" + "00" + "e00fc00f800f000f000e000a000c00080000" + "0004f00ff80ffc0ffe0fff0f" +
            "0807060504030303020209" + "0a0b0c0c")

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private class Variant(
        val name: String, val make: String, val model: String, val id: Long, val w: Int, val h: Int, val bps: Int,
        val layout: String,                       // "pentax", "packed12", "u16"
        val black: IntArray, val wp: IntArray, val seed: Long,
        // LibRaw's view of the file
        val crc: Long, val expectBlack: FloatArray, val expectWhite: Float, val crop: IntArray, val cfa: String,
    )

    private val ricoh = "RICOH IMAGING COMPANY, LTD."

    private val variants = listOf(
        Variant("k3ii-14bit", ricoh, "PENTAX K-3 II", 0x1309c, 6080, 64, 14, "pentax", intArrayOf(1, 1, 1, 1), intArrayOf(19200, 8192, 8192, 11584), 1,
            0xdb6256afL, floatArrayOf(1f, 1f, 1f, 1f), 16383f, intArrayOf(4, 0, 6040, 64), "RGGB"),
        Variant("k3-12bit", ricoh, "PENTAX K-3", 0x12fc0, 6080, 64, 12, "pentax", intArrayOf(2, 3, 4, 5), intArrayOf(16000, 8192, 9000, 12000), 2,
            0x67441a2eL, floatArrayOf(2f, 3f, 4f, 5f), 4095f, intArrayOf(4, 0, 6040, 64), "RGGB"),
        Variant("k1-generic", ricoh, "PENTAX K-1", 0, 96, 64, 14, "pentax", intArrayOf(10, 12, 12, 10), intArrayOf(15000, 8192, 8192, 10000), 3,
            0x45dc0f02L, floatArrayOf(10f, 12f, 12f, 10f), 16383f, intArrayOf(0, 0, 96, 64), "RGGB"),
        Variant("kx-bggr", "PENTAX Corporation", "PENTAX K-x", 0x12dfe, 4352, 64, 12, "pentax", intArrayOf(0, 0, 0, 0), intArrayOf(8192, 8192, 8192, 8192), 4,
            0x05e84564L, floatArrayOf(0f, 0f, 0f, 0f), 4095f, intArrayOf(0, 0, 4309, 64), "BGGR"),
        Variant("k5-left10", "PENTAX Corporation", "PENTAX K-5", 0x12e76, 4992, 64, 12, "pentax", intArrayOf(0, 0, 0, 0), intArrayOf(8192, 8192, 8192, 8192), 5,
            0x8d45137fL, floatArrayOf(0f, 0f, 0f, 0f), 4095f, intArrayOf(10, 0, 4950, 64), "BGGR"),
        Variant("kp-margins", ricoh, "PENTAX KP", 0x1322c, 6112, 64, 14, "pentax", intArrayOf(5, 5, 5, 5), intArrayOf(9000, 8192, 8192, 9500), 6,
            0x24feaf18L, floatArrayOf(5f, 5f, 5f, 5f), 16383f, intArrayOf(54, 28, 6028, 36), "RGGB"),
        Variant("k30-packed12", ricoh, "PENTAX K-30", 0, 64, 32, 12, "packed12", intArrayOf(16, 16, 16, 16), intArrayOf(9000, 8192, 8192, 10000), 7,
            0x0445507aL, floatArrayOf(16f, 16f, 16f, 16f), 4095f, intArrayOf(0, 0, 64, 32), "RGGB"),
        Variant("k30-u16", ricoh, "PENTAX K-30", 0, 64, 32, 16, "u16", intArrayOf(64, 64, 64, 64), intArrayOf(9000, 8192, 8192, 10000), 8,
            0xf3792dd9L, floatArrayOf(64f, 64f, 64f, 64f), 65535f, intArrayOf(0, 0, 64, 32), "RGGB"),
        Variant("k30-u16-14bit", ricoh, "PENTAX K-30", 0, 64, 32, 14, "u16", intArrayOf(64, 64, 64, 64), intArrayOf(9000, 8192, 8192, 10000), 9,
            0xea0e3d4dL, floatArrayOf(64f, 64f, 64f, 64f), 16383f, intArrayOf(0, 0, 64, 32), "RGGB"),
    )

    /** Smooth gradient plus noise, within the sample depth. */
    private fun pixels(v: Variant): IntArray {
        val rnd = Random(v.seed)
        val max = (1 shl v.bps) - 1
        return IntArray(v.w * v.h) { i ->
            val x = i % v.w; val y = i / v.w
            val base = max * 0.15 + max * 0.5 * ((x % 512) / 512.0) + max * 0.1 * ((y % 64) / 64.0)
            (base + rnd.nextInt(max / 16 + 1) - max / 32).toInt().coerceIn(0, max)
        }
    }

    private fun encode(p: IntArray, w: Int, h: Int): ByteArray {
        val len = intArrayOf(8, 7, 6, 5, 4, 3, 3, 3, 2, 2, 9, 10, 11, 12, 12)
        val start = intArrayOf(4064, 4032, 3968, 3840, 3584, 2560, 3072, 2048, 0, 1024, 4080, 4088, 4092, 4094, 4095)
        val out = MsbBitWriter()
        val vpred = IntArray(4); val hpred = IntArray(2)
        for (row in 0 until h) for (col in 0 until w) {
            val v = p[row * w + col]
            val pred = if (col < 2) vpred[(row and 1) * 2 + col] else hpred[col and 1]
            val d = v - pred
            if (col < 2) { vpred[(row and 1) * 2 + col] = v; hpred[col] = v } else hpred[col and 1] = v
            val sym = if (d == 0) 0 else 32 - Integer.numberOfLeadingZeros(Math.abs(d))
            out.put(start[sym] shr (12 - len[sym]), len[sym])
            if (sym > 0) out.put(if (d > 0) d else d + (1 shl sym) - 1, sym)
        }
        return out.bytes()
    }

    private fun build(v: Variant, make: String = v.make, withMakerNote: Boolean = true): ByteArray {
        val px = pixels(v)
        val strip: ByteArray = when (v.layout) {
            "pentax" -> encode(px, v.w, v.h)
            "packed12" -> Pack.pack(px, 12, Unpack.BitOrder.MSB, v.w, v.w * 12 / 8)
            else -> ByteBuffer.allocate(px.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { px.forEach { putShort(it.toShort()) } }.array()
        }
        val mnIfd = TiffBuilder.Ifd()
        if (v.id != 0L) mnIfd.long(0x0005, v.id.toInt())
        mnIfd.short(0x0200, *v.black).short(0x0201, *v.wp)
        if (v.layout == "pentax") mnIfd.undefined(0x0220, huffmanTable)
        val mn = TiffBuilder(true).build(mnIfd, "PENTAX \u0000II".toByteArray(Charsets.ISO_8859_1))
        val cfaBytes = byteArrayOf(2, 0, 2, 0, 0, 1, 1, 2)
        val exif = TiffBuilder.Ifd().undefined(TiffTags.EXIF_CFA_PATTERN, cfaBytes)
        if (withMakerNote) exif.undefined(37500, mn)
        val ifd0 = TiffBuilder.Ifd()
            .ascii(TiffTags.MAKE, make).ascii(TiffTags.MODEL, v.model)
            .long(TiffTags.IMAGE_WIDTH, v.w).long(TiffTags.IMAGE_LENGTH, v.h)
            .short(TiffTags.BITS_PER_SAMPLE, v.bps).short(TiffTags.COMPRESSION, if (v.layout == "pentax") 65535 else 1)
            .short(TiffTags.PHOTOMETRIC, 32803).short(TiffTags.SAMPLES_PER_PIXEL, 1).short(TiffTags.ORIENTATION, 1)
            .short(TiffTags.ROWS_PER_STRIP, v.h)
            .blobs(TiffTags.STRIP_OFFSETS, TiffTags.STRIP_BYTE_COUNTS, listOf(strip))
            .subIfds(TiffTags.EXIF_IFD, exif)
        return TiffBuilder(true).build(ifd0)
    }

    private fun crc(data: ShortArray): Long {
        val c = CRC32()
        val b = ByteArray(data.size * 2)
        for (i in data.indices) { b[2 * i] = data[i].toByte(); b[2 * i + 1] = (data[i].toInt() shr 8).toByte() }
        c.update(b)
        return c.value
    }

    @Test
    fun dumpSyntheticFilesForLibRaw() {
        val dir = System.getenv("SPEKTRA_SYNTH_DUMP") ?: return
        File(dir).mkdirs()
        for (v in variants) File(dir, "pef_${v.name}.PEF").writeBytes(build(v))
    }

    @Test
    fun encodedFilesRoundTrip() {
        for (v in variants) {
            val img = assertNotNull(RawFormatCheck.read("PEF", build(v)), v.name)
            val expect = pixels(v)
            assertEquals(v.w, img.rawWidth, v.name); assertEquals(v.h, img.rawHeight, v.name)
            for (i in expect.indices) if ((img.data[i].toInt() and 0xffff) != expect[i]) {
                throw AssertionError("${v.name}: pixel $i is ${img.data[i].toInt() and 0xffff}, expected ${expect[i]}")
            }
        }
    }

    @Test
    fun matchesLibRaw() {
        for (v in variants) {
            val img = assertNotNull(RawFormatCheck.read("PEF", build(v)), v.name)
            assertEquals(v.crc, crc(img.data), "mosaic CRC ${v.name}")
            assertContentEquals(v.expectBlack, img.black, v.name)
            assertEquals(v.expectWhite, img.white, v.name)
            assertEquals(v.cfa, img.cfa.colors.joinToString("") { "RGB"[it].toString() }, v.name)
            assertEquals(v.crop.toList(), listOf(img.cropX, img.cropY, img.cropWidth, img.cropHeight), v.name)
            // LibRaw cam_mul = (R, G, B) = (wp[0], wp[1], wp[3])
            assertEquals(v.wp[0].toFloat() / v.wp[1], img.wb[0] / img.wb[1], 1e-4f, v.name)
            assertEquals(v.wp[3].toFloat() / v.wp[1], img.wb[2] / img.wb[1], 1e-4f, v.name)
            assertTrue(RawDeveloper.supports(img))
        }
    }

    @Test
    fun geometryTableMatchesLibRaw() {
        // (raw width, raw height, PentaxModelID) -> visible left, top, width, height, CFA override
        // Values from LibRaw 0.22.2 identify_finetune_pentax(), checked with LibRaw on synthetic files
        // of exactly these sizes (the large ones were decoded once offline, not kept in the tests).
        data class G(val w: Int, val h: Int, val id: Long, val out: List<Int>, val filters: Int?)
        val cases = listOf(
            G(3936, 2624, 0, listOf(0, 0, 3896, 2616), null),                       // K10D, GX10
            G(4864, 3136, 0, listOf(0, 0, 4688, 3124), 0x16161616),                 // K20D, GX20
            G(4352, 3264, 0x12dfe, listOf(0, 0, 4309, 3264), 0x16161616),           // K-x
            G(4352, 3264, 0x12e6c, listOf(0, 0, 4309, 3264), 0x16161616),           // K-r
            G(4992, 3300, 0x12e76, listOf(10, 0, 4950, 3300), 0x16161616),          // K-5
            G(4992, 3300, 0x12f70, listOf(10, 0, 4950, 3300), 0x16161616),          // K-5 II
            G(4992, 3300, 0x12f71, listOf(10, 0, 4950, 3300), 0x16161616),          // K-5 IIs
            G(4736, 3128, 0x12db8, listOf(0, 2, 4684, 3122), 0x16161616),           // K-7
            G(6080, 4048, 0x13222, listOf(60, 32, 6020, 4016), null),               // K-70
            G(6080, 4052, 0x132b8, listOf(56, 28, 6024, 4024), null),               // KF
            G(6080, 4032, 0x1309c, listOf(4, 0, 6040, 4032), null),                 // K-3 II
            G(6080, 4032, 0x12fc0, listOf(4, 0, 6040, 4032), null),                 // K-3
            G(6304, 4194, 0x13254, listOf(26, 34, 6224, 4160), null),               // K-3 III
            G(6112, 4056, 0x1322c, listOf(54, 28, 6028, 4028), null),               // KP
            G(7424, 5532, 0x12e08, listOf(48, 30, 7328, 5501), 0x61616161),         // 645D (odd top margin 29 -> 30, as LibRaw)
            G(6016, 4000, 0x1309c, listOf(0, 0, 6016, 4000), null),                 // anything else: whole frame
        )
        for (c in cases) {
            val g = assertNotNull(PefReader.geometry(c.w, c.h, c.id), "$c")
            assertEquals(c.out, listOf(g.left, g.top, g.width, g.height), "$c")
            assertEquals(c.filters, g.filters, "$c")
        }
        // 645D: GRBG relative to the odd origin (48, 29) is BGGR at raw (0, 0), as LibRaw's raw_pattern reports
        val g645 = assertNotNull(PefReader.geometry(7424, 5532, 0x12e08))
        assertEquals(listOf(29, 48), listOf(g645.originTop, g645.originLeft))
        assertEquals("BGGR", CfaPattern.fromDcrawFilters(0x61616161).anchoredAt(29, 48).colors.joinToString("") { "RGB"[it].toString() })
        assertNull(PefReader.geometry(6304, 4194, 0x132d6))                          // K-3 III Monochrome
    }

    @Test
    fun rejectsOtherFilesAndBrokenData() {
        val v = variants[2]
        val good = build(v)
        assertNotNull(RawFormatCheck.read("PEF", good))
        assertNull(PefReader.read(build(v, make = "SAMSUNG TECHWIN")))
        assertNull(PefReader.read(build(v, make = "Canon")))
        assertNull(PefReader.read(build(v, withMakerNote = false)))
        assertNull(PefReader.read(ByteArray(0)))
        assertNull(PefReader.read(ByteArray(200) { 3 }))
        assertNull(PefReader.read(good.copyOf(good.size / 2)))
        // a stream cut short must not produce a partial image
        val v2 = variants[2]
        val short = build(v2)
        assertNull(PefReader.read(short.copyOf(short.size - 600)))
    }

    @Test
    fun randomGarbageNeverThrows() {
        val rnd = Random(9)
        val good = build(variants[2])
        repeat(300) {
            val b = good.copyOf()
            repeat(1 + rnd.nextInt(8)) { b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }
            PefReader.read(b)
        }
    }

    /** Timing of a K-3 II sized frame (set SPEKTRA_PEF_PERF=1; needs a larger test heap, so it is opt-in). */
    @Test
    fun fullFrameDecodeTime() {
        org.junit.Assume.assumeTrue("SPEKTRA_PEF_PERF not set", !System.getenv("SPEKTRA_PEF_PERF").isNullOrEmpty())
        val v = Variant("perf", ricoh, "PENTAX K-3 II", 0x1309c, 6080, 4032, 14, "pentax", intArrayOf(1, 1, 1, 1), intArrayOf(19200, 8192, 8192, 11584), 21,
            0L, floatArrayOf(), 16383f, intArrayOf(), "RGGB")
        val bytes = build(v)
        repeat(3) {
            val t0 = System.nanoTime()
            val img = assertNotNull(RawFormatCheck.read("PEF", bytes))
            println("PEF ${v.w}x${v.h} decode ${(System.nanoTime() - t0) / 1_000_000} ms (${bytes.size / 1_000_000} MB file)")
            assertEquals(6040, img.cropWidth)
        }
    }

    @Test
    fun truncatedRealK3iiFileIsNotDecoded() {
        val dir = System.getenv("SPEKTRA_RAW_SAMPLES")
        org.junit.Assume.assumeTrue("SPEKTRA_RAW_SAMPLES not set", !dir.isNullOrEmpty())
        val f = File(dir, "_metadata_only/bep_imagemeta_pentax_k3ii_truncated.pef")
        org.junit.Assume.assumeTrue("missing sample", f.isFile)
        // header, maker note and Huffman table are valid but the pixel strip is cut off: no partial image
        assertNull(PefReader.read(f.readBytes()))
        // metadata the reader works from, as LibRaw 0.22.2 reports it for this file
        val tiff = assertNotNull(Tiff.parse(f.readBytes(), 0, setOf(42)))
        val mn = assertNotNull(tiff.makerNote())
        assertEquals("pentax", mn.kind)
        assertEquals(0x1309cL, mn.ifd.longs(0x0005)!![0])
        assertEquals(listOf(1, 1, 1, 1), mn.ifd.ints(0x0200)!!.toList())
        assertEquals(listOf(19200, 8192, 8192, 11584), mn.ifd.ints(0x0201)!!.toList())
        assertEquals(59, mn.ifd[0x0220]!!.byteCount)
        assertContentEquals(huffmanTable, tiff.bytes.copyOfRange(mn.ifd[0x0220]!!.dataOffset, mn.ifd[0x0220]!!.dataOffset + 59))
    }
}
