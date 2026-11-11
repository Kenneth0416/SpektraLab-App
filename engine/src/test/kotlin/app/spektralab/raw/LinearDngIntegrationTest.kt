package app.spektralab.raw

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

class LinearDngIntegrationTest {
    private fun source(orientation: Int = 1): RawImage {
        val i = TiffBuilder.Ifd().byte(50706, 1, 4, 0, 0).long(256, 4).long(257, 4)
            .short(258, 16).short(259, 1).short(262, 32803).short(277, 1).short(274, orientation)
            .short(33421, 2, 2).byte(33422, 0, 1, 1, 2).long(278, 4)
            .srational(50721, *Mat3.IDENTITY).srational(50722, *Mat3.IDENTITY)
            .short(50778, 17).short(50779, 21)
            .srational(50723, *Mat3.IDENTITY).srational(50724, *Mat3.IDENTITY)
            .rational(50727, 1.1, 1.0, 0.9).rational(50728, 0.5, 1.0, 0.75)
            .blobs(273, 279, listOf(ByteArray(32)))
        return assertNotNull(DngReader.read(TiffBuilder().build(i)))
    }

    private fun write(w: Int, h: Int, rgb: ShortArray, source: RawImage, maps: List<GainMap> = emptyList()): ByteArray =
        ByteArrayOutputStream().also { DngWriter.writeLinear(it, w, h, rgb, source, maps, 0.001 to 0.00001) }.toByteArray()

    @Test fun writeReadDevelopPreservesLinearRgbMetadataAndPlaneGainMaps() {
        // Two strips, short final strip, odd dimensions, and mirrored orientations.
        val w = 7; val h = 65
        val rgb = ShortArray(w * h * 3) { (4000 + (it * 31) % 15000).toShort() }
        val maps = (0..2).map { c -> GainMap(0, 0, h, w, 1, 1, 2, 2, 1.0, 1.0, 0.0, 0.0,
            FloatArray(4) { 1f + c * .1f }, c) }
        for (orientation in listOf(1, 5, 7)) {
            val src = source(orientation)
            val bytes = write(w, h, rgb, src, maps)
            val decoded = assertNotNull(DngReader.read(bytes))
            assertTrue(decoded.linear)
            assertTrue(RawDeveloper.supports(decoded))
            assertEquals(w, decoded.cropWidth); assertEquals(h, decoded.cropHeight)
            assertEquals(orientation, decoded.orientation)
            assertEquals(listOf(0, 1, 2), decoded.gainMaps.map { it.plane })
            assertEquals(.001f, decoded.noiseScale!!, 1e-8f)
            assertEquals(.00001f, decoded.noiseOffset, 1e-8f)
            for ((tag, value) in src.tags) assertContentEquals(value.values, decoded.tags[tag]!!.values)
            for (i in rgb.indices) assertTrue(kotlin.math.abs((rgb[i].toInt() and 65535) - (decoded.data[i].toInt() and 65535)) <= 8)
            val expected = RawImage(w, h, rgb, CfaPattern.RGGB, FloatArray(4), 65535f,
                0, 0, w, h, src.wb, src.camToSrgb, orientation, gainMaps = maps, linear = true)
            val opts = RawDeveloper.Options(reconstructHighlights = false, chromaDenoise = false)
            val a = RawDeveloper.develop(expected, 4096, options = opts)
            val b = RawDeveloper.develop(decoded, 4096, options = opts)
            assertEquals(a.width, b.width); assertEquals(a.height, b.height)
            for (y in 0 until a.height) for (x in 0 until a.width) {
                assertEquals(a.red(x, y), b.red(x, y), .001f)
                assertEquals(a.green(x, y), b.green(x, y), .001f)
                assertEquals(a.blue(x, y), b.blue(x, y), .001f)
            }
        }
    }

    @Test fun rejectsTruncatedLinearDeflateAndOversizedSampleCounts() {
        val bytes = write(7, 65, ShortArray(7 * 65 * 3) { 16000 }, source())
        val ifd = Tiff.parse(bytes)!!.ifd0
        val counts = ifd[279]!!
        val bad = bytes.copyOf()
        ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(counts.dataOffset, 1)
        assertNull(DngReader.read(bad))
        val oversized = bytes.copyOf()
        ByteBuffer.wrap(oversized).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(ifd[256]!!.dataOffset, 5000).putInt(ifd[257]!!.dataOffset, 5000)
        assertNull(DngReader.read(oversized)) // 75 million RGB samples
    }

    @Test fun linearIntegerStripsAndHorizontalDeflateRemainSupported() {
        val w = 5; val h = 4
        val values = IntArray(w * h * 3) { 1000 + it * 17 }
        for (compression in listOf(1, 8)) for (le in listOf(false, true)) {
            val encoded = values.copyOf()
            if (compression == 8) for (y in 0 until h) for (k in w * 3 - 1 downTo 3)
                encoded[y * w * 3 + k] -= values[y * w * 3 + k - 3]
            val raw = ByteBuffer.allocate(encoded.size * 2).order(if (le) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
                .apply { encoded.forEach { putShort(it.toShort()) } }.array()
            val payload = if (compression == 1) raw else ByteArrayOutputStream().also { out ->
                java.util.zip.DeflaterOutputStream(out).use { it.write(raw) }
            }.toByteArray()
            val i = TiffBuilder.Ifd().byte(50706, 1, 4, 0, 0).long(256, w).long(257, h)
                .short(258, 16, 16, 16).short(259, compression).short(262, 34892).short(277, 3)
                .short(317, if (compression == 8) 2 else 1).long(278, h)
                .srational(50721, *Mat3.IDENTITY).rational(50728, .5, 1.0, .75)
                .blobs(273, 279, listOf(payload))
            val dng = assertNotNull(DngReader.read(TiffBuilder(le).build(i)))
            assertContentEquals(values, dng.data.map { it.toInt() and 65535 }.toIntArray())
        }
    }

    @Test fun croppedBayerCellUsesPhysicalColorsAndBlackLevels() {
        val img = RawImage(10, 10, ShortArray(100) { 1000 }, CfaPattern.RGGB,
            floatArrayOf(10f, 20f, 30f, 40f), 65535f, 1, 1, 8, 8,
            floatArrayOf(1f, 1f, 1f), FloatArray(9) { if (it % 4 == 0) 1f else 0f })
        assertContentEquals(intArrayOf(2, 1, 1, 0), img.cropCfa)
        assertContentEquals(floatArrayOf(40f, 30f, 20f, 10f), img.cropBlack)
        val result = BurstMerge.gather(BurstMerge.Planner(img).build())
        for (i in result.indices step 3) {
            assertTrue(kotlin.math.abs(990 - (result[i].toInt() and 65535)) <= 2)
            assertTrue(kotlin.math.abs(960 - (result[i + 2].toInt() and 65535)) <= 2)
        }
    }
}
