package app.spektralab.raw

import org.junit.Assume
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Canon CR2: synthetic files from an in-test CR2 writer (slices, 2 / 4 components, ColorData
 * layouts, SensorInfo, masked-border black, CameraInfo orientation), and the real samples
 * from `$SPEKTRA_RAW_SAMPLES/CR2` against values taken from LibRaw 0.22.2 (rawpy 0.28.0).
 *
 * The synthetic files were also decoded by LibRaw (write them with
 * `-Dspektra.rawdump=DIR`): its raw_image, black_level_per_channel, white_level,
 * camera_whitebalance, crop and flip are the LIBRAW_* constants below.
 */
class Cr2Test {
    // ------------------------------------------------------------------ synthetic CR2 writer

    class Spec(
        val rawWidth: Int, val rawHeight: Int, val components: Int,
        /** Canon slices (count, width, last width), or null for none. */
        val slices: IntArray?,
        val model: String,
        val modelId: Long,
        val precision: Int,
        /** ColorData (0x4001) length in shorts and the as-shot WB / channel black (file order R, G1, G2, B). */
        val colorDataLength: Int?, val colorDataVersion: Int = 0,
        val asShotAt: Int = 0, val asShot: IntArray = intArrayOf(0, 0, 0, 0),
        val blackAt: Int = 0, val channelBlack: IntArray = intArrayOf(0, 0, 0, 0),
        /** SensorInfo crop l, t, r, b (inclusive), or null for no 0x00e0. */
        val sensorCrop: IntArray? = null,
        val orientation: Int = 1,
        /** CameraInfo (0x000d) byte at [cameraInfoFlipAt] = rotation 0, 1 (90 CW) or 2 (90 CCW). */
        val cameraInfoFlipAt: Int = -1, val cameraInfoRotation: Int = 0,
    )

    /** Raw values -> the Canon sample stream: slice 0 top to bottom, then slice 1, ... */
    private fun stream(mosaic: IntArray, s: Spec): IntArray {
        val w = s.rawWidth; val h = s.rawHeight
        val sl = s.slices ?: return mosaic
        val out = IntArray(w * h)
        var k = 0
        for (i in 0..sl[0]) {
            val x0 = i * sl[1]
            val sw = if (i < sl[0]) sl[1] else sl[2]
            for (y in 0 until h) for (x in 0 until sw) out[k++] = mosaic[y * w + x0 + x]
        }
        check(k == w * h)
        return out
    }

    /** Canon maker note IFD whose offsets are absolute file positions (it is built twice, see [build]). */
    private fun makerNote(s: Spec): TiffBuilder.Ifd {
        val mn = TiffBuilder.Ifd()
        if (s.cameraInfoFlipAt >= 0) {
            val ci = ByteArray(1536); ci[s.cameraInfoFlipAt] = s.cameraInfoRotation.toByte()
            mn.undefined(0x000d, ci)
        }
        mn.long(0x0010, s.modelId.toInt())
        s.sensorCrop?.let { c -> mn.short(0x00e0, 34, s.rawWidth, s.rawHeight, 1, 1, c[0], c[1], c[2], c[3], 0, 0, 0, 0, 0, 0, 0, 0) }
        s.colorDataLength?.let { n ->
            val cd = IntArray(n)
            cd[0] = s.colorDataVersion
            for (c in 0 until 4) { cd[s.asShotAt + c] = s.asShot[c]; if (s.blackAt > 0) cd[s.blackAt + c] = s.channelBlack[c] }
            mn.short(0x4001, *cd)
        }
        return mn
    }

    fun build(s: Spec, mosaic: IntArray, patchSof: ((ByteArray) -> Unit)? = null): ByteArray {
        val nc = s.components
        val ljpeg = LjpegEncoder.encode(stream(mosaic, s), s.rawWidth / nc, s.rawHeight, nc, s.precision, 1,
            tableFor = IntArray(nc) { minOf(it, 1) })
        patchSof?.invoke(ljpeg)
        val marker = ByteArray(16) { (0xA5 xor it * 37).toByte() }
        fun file(mnBytes: ByteArray): ByteArray {
            val exif = TiffBuilder.Ifd().undefined(TiffTags.MAKER_NOTE, mnBytes)
            val ifd0 = TiffBuilder.Ifd()
                .ascii(TiffTags.MAKE, "Canon").ascii(TiffTags.MODEL, s.model)
                .short(TiffTags.ORIENTATION, s.orientation)
                .subIfds(TiffTags.EXIF_IFD, exif)
            val ifd1 = TiffBuilder.Ifd().long(TiffTags.JPEG_INTERCHANGE, 0).long(TiffTags.JPEG_INTERCHANGE_LENGTH, 0)
            val ifd2 = TiffBuilder.Ifd().short(TiffTags.IMAGE_WIDTH, 8).short(TiffTags.IMAGE_LENGTH, 8).short(TiffTags.COMPRESSION, 1)
            val ifd3 = TiffBuilder.Ifd().short(TiffTags.COMPRESSION, 6)
                .blobs(TiffTags.STRIP_OFFSETS, TiffTags.STRIP_BYTE_COUNTS, listOf(ljpeg))
            s.slices?.let { ifd3.short(0xC640, *it) }
            ifd0.next = ifd1; ifd1.next = ifd2; ifd2.next = ifd3
            // header: "II*\0", IFD0 at 16, "CR\x02\0", raw IFD offset (unused by readers)
            val prefix = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                .put("II".toByteArray()).putShort(42).putInt(16).put("CR".toByteArray()).put(2).put(0).putInt(0).array()
            return TiffBuilder(true).build(ifd0, prefix)
        }
        // pass 1: find where the maker note lands; pass 2: build it with absolute offsets for that position
        val mnSize = TiffBuilder(true).build(makerNote(s), ByteArray(0)).size
        val probe = file(ByteArray(mnSize).also { marker.copyInto(it) })
        val at = (0..probe.size - marker.size).first { p -> marker.indices.all { probe[p + it] == marker[it] } }
        val mn = TiffBuilder(true).build(makerNote(s), ByteArray(at)).copyOfRange(at, at + mnSize)
        return file(mn)
    }

    // ------------------------------------------------------------------ synthetic cases

    /** EOS 5D Mark II colour data, but a sensor size outside LibRaw's margin table: ColorData black + SensorInfo crop. */
    private val sliced4 = Spec(
        rawWidth = 1504, rawHeight = 1000, components = 4, slices = intArrayOf(2, 512, 480),
        model = "Canon EOS 5D Mark II", modelId = 0x80000218, precision = 14,
        colorDataLength = 1250, colorDataVersion = 6, asShotAt = 0x3f, asShot = intArrayOf(2000, 1024, 1030, 1500),
        blackAt = 0x2cb, channelBlack = intArrayOf(1021, 1023, 1025, 1027),
        sensorCrop = intArrayOf(41, 23, 1480, 990),
        cameraInfoFlipAt = 0x31, cameraInfoRotation = 1,
    )

    /** EOS D30 size (in LibRaw's margin table: left 48, top 6), 2 components, no slices, 12 bit, ColorData 582. */
    private val table2 = Spec(
        rawWidth = 2224, rawHeight = 1456, components = 2, slices = null,
        model = "Canon EOS D30", modelId = 0x01140000, precision = 12,
        colorDataLength = 582, asShotAt = 0x19, asShot = intArrayOf(1900, 1000, 1000, 1400),
    )

    /** Black per raw parity position (row % 2, col % 2) the synthetic sensors are given in their masked border. */
    private val borderBlack = intArrayOf(126, 129, 131, 124)

    /**
     * A grey scene seen through the as-shot white balance: raw = black + level * span / multiplier, so a correct
     * reader + developer returns r = g = b = level. Masked border (left of [visibleLeft]) is black plus noise.
     */
    private fun mosaic(s: Spec, black: (Int, Int) -> Int, white: Int, visibleLeft: Int, clipAt: Int = Int.MAX_VALUE): IntArray {
        val rnd = Random(11)
        val mul = doubleArrayOf(s.asShot[0].toDouble(), (s.asShot[1] + s.asShot[2]) / 2.0, s.asShot[3].toDouble())
        val cfaRggb = intArrayOf(0, 1, 1, 2)
        return IntArray(s.rawWidth * s.rawHeight) { i ->
            val x = i % s.rawWidth; val y = i / s.rawWidth
            val b = black(y, x)
            if (x < visibleLeft) return@IntArray (b + rnd.nextInt(-6, 7)).coerceAtLeast(0)
            val level = level(x, y, s)
            val c = cfaRggb[(y and 1) * 2 + (x and 1)]
            minOf(clipAt, (b + level * (white - b) * mul[1] / mul[c]).toInt() + rnd.nextInt(0, 2))
        }
    }

    private fun level(x: Int, y: Int, s: Spec) = 0.05 + 0.6 * x / s.rawWidth * (0.4 + 0.6 * y / s.rawHeight)

    @Test
    fun slicedFourComponentColorDataBlackAndSensorInfoCrop() {
        val s = sliced4
        val cb = s.channelBlack
        // LibRaw colours relative to the visible origin (24, 42) = RGGB at the raw origin: R, G1 / G2, B
        val m = mosaic(s, { y, x -> cb[(y and 1) * 2 + (x and 1)] }, 15600, 0)
        val bytes = build(s, m)
        val d = assertNotNull(Cr2Reader.decode(bytes))
        val img = d.image
        assertEquals(1504, img.rawWidth); assertEquals(1000, img.rawHeight)
        assertContentEquals(m, IntArray(m.size) { img.data[it].toInt() and 0xffff })
        assertEquals("CfaPattern(2x2, RGGB)", img.cfa.toString())
        assertContentEquals(floatArrayOf(1021f, 1023f, 1025f, 1027f), img.black)
        assertEquals(15600f, img.white); assertEquals(15600, d.libRawWhite)
        // SensorInfo 41, 23 .. 1480, 990 with odd edges moved to the next even position as LibRaw does
        assertEquals(listOf(42, 24, 1439, 967), listOf(img.cropX, img.cropY, img.cropWidth, img.cropHeight))
        assertEquals(2000f / 1027f, img.wb[0], 1e-5f)
        assertEquals(1500f / 1027f, img.wb[2], 1e-5f)
        assertEquals(6, img.orientation)                                         // CameraInfo: rotated 90 CW
        assertEquals(LIBRAW_SLICED4_CRC, crc(img))
        assertNeutral(img, s)

        // an EXIF orientation other than 1 wins over CameraInfo (LibRaw: only when tiff_flip == 0)
        val rotated = assertNotNull(Cr2Reader.read(build(Spec(s.rawWidth, s.rawHeight, 4, s.slices, s.model, s.modelId, 14,
            s.colorDataLength, 6, s.asShotAt, s.asShot, s.blackAt, s.channelBlack, s.sensorCrop, orientation = 8,
            cameraInfoFlipAt = 0x31, cameraInfoRotation = 1), m)))
        assertEquals(8, rotated.orientation)
    }

    @Test
    fun twoComponentTableCameraMaskedBlackAndAdjustedMaximum() {
        val s = table2
        // visible area starts at column 48; values clip at 3900 (below the 12-bit maximum LibRaw assumes)
        val m = mosaic(s, { y, x -> borderBlack[(y and 1) * 2 + (x and 1)] }, 3600, 48, clipAt = 3900)
        // make sure something reaches the clip level
        for (x in 1000 until 1010) m[700 * s.rawWidth + x] = 3900
        val d = assertNotNull(Cr2Reader.decode(build(s, m)))
        val img = d.image
        assertContentEquals(m, IntArray(m.size) { img.data[it].toInt() and 0xffff })
        // LibRaw: margins from the table, odd top (6 is even here) -> visible (48, 6) 2176 x 1448, RGGB there
        assertEquals("CfaPattern(2x2, RGGB)", img.cfa.toString())
        assertEquals(listOf(48, 6, 2176, 1448), listOf(img.cropX, img.cropY, img.cropWidth, img.cropHeight))
        // black: integer mean of columns 2 .. 45 over the visible rows, per colour
        val exp = FloatArray(4) { p ->
            var sum = 0L; var n = 0L
            for (y in 6 until 6 + 1448) for (x in 2 until 46) if ((y and 1) * 2 + (x and 1) == p) { sum += m[y * s.rawWidth + x]; n++ }
            (sum / n).toFloat()
        }
        assertContentEquals(exp, img.black)
        assertContentEquals(LIBRAW_TABLE2_BLACK, img.black)
        assertEquals(4095, d.libRawWhite)                                       // no table maximum for the D30: 12 bit
        // adjust_maximum: smallest channel black + largest black-subtracted value of the visible area
        var dmax = 0
        for (y in 6 until 6 + 1448) for (x in 48 until 48 + 2176) dmax = maxOf(dmax, m[y * s.rawWidth + x] - exp[(y and 1) * 2 + (x and 1)].toInt())
        assertEquals(exp.min() + dmax, img.white)
        assertEquals(1900f / 1000f, img.wb[0], 1e-5f)
        assertEquals(1, img.orientation)
        assertEquals(LIBRAW_TABLE2_CRC, crc(img))
    }

    private fun assertNeutral(img: RawImage, s: Spec) {
        val dev = RawDeveloper.develop(img, 4096, fast = true)
        var worst = 0.0
        // develop() applies orientation 6 (90 CW): developed (x, y) <- crop (y, H - 1 - x) in 2x2 cells
        for (y in 4 until dev.height - 4 step 7) for (x in 4 until dev.width - 4 step 7) {
            val r = dev.red(x, y); val g = dev.green(x, y); val b = dev.blue(x, y)
            worst = maxOf(worst, abs(r - g).toDouble() / g, abs(b - g).toDouble() / g)
        }
        println("synthetic CR2 developed: worst channel deviation from neutral %.4f".format(worst))
        assertTrue(worst < 0.02, "not neutral: $worst")
        // the brightest corner has the level the scene was made with (rotated: crop right edge is the bottom)
        val expected = level(img.cropX + img.cropWidth - 2, img.cropY + img.cropHeight - 2, s)
        val got = dev.green(0, dev.height - 1).toDouble()
        assertTrue(abs(got - expected) < 0.02, "level $got vs $expected")
    }

    @Test
    fun rejectsOtherFilesSrawAndTruncation() {
        assertNull(Cr2Reader.read(ByteArray(0)))
        assertNull(Cr2Reader.read(ByteArray(64)))
        val s = table2
        val m = mosaic(s, { _, _ -> 128 }, 3600, 48)
        val good = build(s, m)
        assertNotNull(Cr2Reader.read(good))
        // plain TIFF (no "CR\2" marker)
        assertNull(Cr2Reader.read(good.copyOf().also { it[8] = 0; it[9] = 0 }))
        // not Canon
        assertNull(Cr2Reader.read(String(good, Charsets.ISO_8859_1).replace("Canon\u0000", "Conan\u0000").toByteArray(Charsets.ISO_8859_1)))
        // sRAW: subsampled first component (H=2, V=1) in the SOF3
        val sraw = build(s, m) { j ->
            val sof = (0 until j.size - 1).first { j[it] == 0xFF.toByte() && j[it + 1] == 0xC3.toByte() }
            j[sof + 11] = 0x21
        }
        assertNull(Cr2Reader.read(sraw))
        // truncated: strip runs past the end of the file
        assertNull(Cr2Reader.read(good.copyOf(good.size - 1000)))
        // camera without a colour matrix
        assertNull(Cr2Reader.read(build(Spec(2224, 1456, 2, null, "Canon EOS Unknown 1", 1, 12, null), m)))
    }

    @Test
    fun survivesCorruptInput() {
        val good = build(table2, mosaic(table2, { _, _ -> 128 }, 3600, 48))
        val rnd = Random(5)
        var decoded = 0
        repeat(150) {
            val bad = good.copyOf(if (rnd.nextInt(4) == 0) rnd.nextInt(good.size) else good.size)
            repeat(1 + rnd.nextInt(8)) { if (bad.isNotEmpty()) bad[rnd.nextInt(minOf(bad.size, if (rnd.nextBoolean()) 2048 else bad.size))] = rnd.nextInt(256).toByte() }
            if (Cr2Reader.read(bad) != null) decoded++
        }
        println("corrupt CR2s still decoded: $decoded / 150")
    }

    @Test
    fun metadataOnlySamplesReturnNull() {
        val dir = samplesDir()
        for (n in listOf("bep_imagemeta_sample_truncated.cr2", "exiftool_CanonRaw.cr2")) {
            val f = File(dir, "_metadata_only/$n")
            Assume.assumeTrue(f.isFile)
            assertNull(Cr2Reader.read(f.readBytes()), n)
        }
    }

    /** Writes the synthetic files for decoding with LibRaw (tools/rawdump.py). */
    @Test
    fun dumpSyntheticForLibRaw() {
        val dir = System.getProperty("spektra.rawdump")?.takeIf { it.isNotEmpty() } ?: return
        File(dir).mkdirs()
        val cb = sliced4.channelBlack
        File(dir, "synthetic_sliced4.cr2").writeBytes(build(sliced4, mosaic(sliced4, { y, x -> cb[(y and 1) * 2 + (x and 1)] }, 15600, 0)))
        val m = mosaic(table2, { y, x -> borderBlack[(y and 1) * 2 + (x and 1)] }, 3600, 48, clipAt = 3900)
        for (x in 1000 until 1010) m[700 * table2.rawWidth + x] = 3900
        File(dir, "synthetic_table2.cr2").writeBytes(build(table2, m))
    }

    // ------------------------------------------------------------------ real samples

    class Expect(
        val name: String, val make: String, val model: String, val rawWidth: Int, val rawHeight: Int, val crc: Long,
        val cfa: String, val black: FloatArray, val libRawWhite: Int, val white: Float,
        val crop: List<Int>, val asShot: DoubleArray, val orientation: Int,
    )

    private val samples = listOf(
        // values from LibRaw 0.22.2: raw_image CRC-32 (uint16 LE), black_level_per_channel at the 2x2 raw positions,
        // white_level, raw_inset_crops[0] (= SensorInfo), camera_whitebalance (R, G, B, G2), flip 0
        Expect("jeremytorres_little_endian.CR2", "Canon", "Canon EOS 5D Mark II", 5792, 3804, 1963067855L,
            "GBRG", floatArrayOf(1023f, 1024f, 1023f, 1024f), 15600, 13604f,
            listOf(168, 56, 5616, 3744), doubleArrayOf(2661.0, 1024.0, 1475.0, 1024.0), 1),
        Expect("rubyraw_IMG_2112.CR2", "Canon", "Canon EOS 7D", 5360, 3516, 3126108881L,
            "GBRG", floatArrayOf(2047f, 2047f, 2047f, 2047f), 13584, 12649f,
            listOf(168, 56, 5184, 3456), doubleArrayOf(2284.0, 1024.0, 1467.0, 1024.0), 1),
    )

    @Test
    fun realSamplesMatchLibRaw() {
        val dir = samplesDir()
        var tested = 0
        for (e in samples) {
            val f = File(dir, "CR2/${e.name}")
            if (!f.isFile) continue
            val bytes = f.readBytes()
            Cr2Reader.decode(bytes)                                               // warm-up
            val t0 = System.nanoTime()
            val d = assertNotNull(Cr2Reader.decode(bytes), e.name)
            println("${e.name}: decoded in %.0f ms".format((System.nanoTime() - t0) / 1e6))
            val img = d.image
            assertEquals(e.rawWidth, img.rawWidth); assertEquals(e.rawHeight, img.rawHeight)
            assertEquals(e.crc, crc(img), "${e.name} mosaic")
            assertEquals("CfaPattern(2x2, ${e.cfa})", img.cfa.toString())
            assertContentEquals(e.black, img.black)
            assertEquals(e.libRawWhite, d.libRawWhite)
            // LibRaw's render lowers maximum to the visible data maximum (adjust_maximum, 0.75)
            assertEquals(e.white, img.white)
            assertEquals(e.crop, listOf(img.cropX, img.cropY, img.cropWidth, img.cropHeight))
            val g = (e.asShot[1] + e.asShot[3]) / 2
            assertEquals((e.asShot[0] / g).toFloat(), img.wb[0], 1e-5f)
            assertEquals(1f, img.wb[1])
            assertEquals((e.asShot[2] / g).toFloat(), img.wb[2], 1e-5f)
            assertEquals(e.orientation, img.orientation)
            assertEquals(e.make, img.make); assertEquals(e.model, img.model)
            tested++
            dumpDeveloped(e.name, img)
        }
        Assume.assumeTrue("no CR2 samples in $dir", tested > 0)
    }

    /** Half-size development for tools/cr2check.py (comparison with a LibRaw render). */
    private fun dumpDeveloped(name: String, img: RawImage) {
        val out = System.getProperty("spektra.rawdump")?.takeIf { it.isNotEmpty() } ?: return
        File(out).mkdirs()
        val t0 = System.nanoTime()
        val dev = RawDeveloper.develop(img, maxOf(img.cropWidth, img.cropHeight) / 2)
        println("$name: developed (binned) in %.0f ms".format((System.nanoTime() - t0) / 1e6))
        // LibRaw's visible area for these cameras: SensorInfo crop minus (10, 4) raw pixels
        val hdr = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(dev.width).putInt(dev.height).putInt(5).putInt(2).array()
        val px = ByteArray(dev.width * dev.height * 8)
        dev.halfRgba.duplicate().order(ByteOrder.LITTLE_ENDIAN).also { it.rewind() }.get(px)
        File(out, "$name.dev").writeBytes(hdr + px)
    }

    // ------------------------------------------------------------------ helpers

    private fun samplesDir(): String {
        val dir = System.getenv("SPEKTRA_RAW_SAMPLES")
        Assume.assumeTrue("SPEKTRA_RAW_SAMPLES not set", !dir.isNullOrEmpty())
        return dir!!
    }

    private fun crc(img: RawImage): Long {
        val n = img.rawWidth * img.rawHeight
        val b = ByteArray(n * 2)
        for (i in 0 until n) { val v = img.data[i].toInt(); b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte() }
        return CRC32().apply { update(b) }.value
    }

    companion object {
        // LibRaw 0.22.2 on the files dumpSyntheticForLibRaw writes
        const val LIBRAW_SLICED4_CRC = 750652034L       // + black 1021/1023/1025/1027, white 15600, crop 42,24 1439x967, flip 6
        const val LIBRAW_TABLE2_CRC = 2988733056L      // + white 4095, visible area 48,6 2176x1448, flip 0
        val LIBRAW_TABLE2_BLACK = floatArrayOf(125f, 129f, 130f, 123f)
    }
}
