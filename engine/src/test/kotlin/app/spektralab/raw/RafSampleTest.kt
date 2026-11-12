package app.spektralab.raw

import org.junit.Assume
import java.io.File
import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real Fujifilm X-T2 files (X-Trans, uncompressed 14-bit in 16-bit words) against LibRaw 0.22.2.
 * Expectations were obtained with rawpy: `zlib.crc32(raw_image.astype('<u2').tobytes())`,
 * `black_level_per_channel`, `white_level`, `camera_whitebalance`, `sizes.crop_*`,
 * `raw_pattern`, `rgb_xyz_matrix`. Skipped unless SPEKTRA_RAW_SAMPLES points at the samples.
 */
class RafSampleTest {
    private fun sample(name: String): ByteArray {
        val dir = System.getenv("SPEKTRA_RAW_SAMPLES")
        Assume.assumeTrue("SPEKTRA_RAW_SAMPLES not set", !dir.isNullOrEmpty())
        val f = File(dir, "RAF/$name")
        Assume.assumeTrue("missing $f", f.isFile)
        return f.readBytes()
    }

    private fun crc(data: ShortArray): Long {
        val b = ByteArray(data.size * 2)
        for (i in data.indices) { b[2 * i] = data[i].toByte(); b[2 * i + 1] = (data[i].toInt() shr 8).toByte() }
        return CRC32().apply { update(b) }.value
    }

    /** LibRaw raw_pattern (relative to the visible origin, which is raw (0, 0) on the X-T2). */
    private val xt2Pattern = intArrayOf(
        0, 2, 1, 2, 0, 1, 1, 1, 0, 1, 1, 2, 1, 1, 2, 1, 1, 0,
        2, 0, 1, 0, 2, 1, 1, 1, 2, 1, 1, 0, 1, 1, 0, 1, 1, 2)

    private fun check(name: String, crc: Long, black: Float, wb: DoubleArray) {
        val bytes = sample(name)
        val t0 = System.nanoTime()
        val img = assertNotNull(RawFormatCheck.read("RAF", bytes))
        val ms = (System.nanoTime() - t0) / 1e6
        println("RAF $name decoded in ${"%.0f".format(ms)} ms")
        assertEquals(6160, img.rawWidth); assertEquals(4032, img.rawHeight)
        assertEquals(crc, crc(img.data), "mosaic CRC vs LibRaw raw_image")
        assertEquals(6, img.cfa.width); assertEquals(6, img.cfa.height)
        assertContentEquals(xt2Pattern, img.cfa.colors)
        assertTrue(img.black.all { it == black }, "black ${img.black.toList()}")
        assertEquals(16383f, img.white)
        assertEquals(listOf(18, 18, 5998, 3998), listOf(img.cropX, img.cropY, img.cropWidth, img.cropHeight))
        assertEquals(1, img.orientation)
        assertEquals("FUJIFILM", img.make); assertEquals("X-T2", img.model)

        val info = assertNotNull(RafReader.parse(bytes))
        assertEquals(RafReader.Layout.UNPACKED16, info.layout)
        assertEquals(listOf(0, 0, 6032, 4032), listOf(info.left, info.top, info.width, info.height))
        assertContentEquals(wb, info.asShot)
        val libraw = doubleArrayOf(1.1434, -0.4948, -0.121, -0.3746, 1.2042, 0.1903, -0.0666, 0.1479, 0.5235)
        for (i in 0 until 9) assertTrue(abs(info.xyzToCamera[i] - libraw[i]) < 1e-4, "matrix[$i]")
        // the white balance RawColor derives is the LibRaw camera multipliers normalised to green
        assertEquals((wb[0] / wb[1]).toFloat(), img.wb[0], 1e-4f)
        assertEquals((wb[2] / wb[1]).toFloat(), img.wb[2], 1e-4f)
    }

    /**
     * Develops the bright frame at full resolution and binned. With SPEKTRA_RAF_DUMP set, writes
     * both as float32 RGB (`int w, int h`, then pixels, little endian) for comparison with a
     * LibRaw render (tools/raf_compare.py).
     */
    @Test fun xt2Develop() {
        val img = assertNotNull(RawFormatCheck.read("RAF", sample("00001_00_10s.RAF")))
        assertTrue(RawDeveloper.supports(img))
        val opts = RawDeveloper.Options(reconstructHighlights = false)
        RawDeveloper.develop(img, 1000, options = opts)            // warm-up
        var t0 = System.nanoTime()
        val full = RawDeveloper.develop(img, 6000, options = opts)
        println("X-Trans full-resolution develop: ${(System.nanoTime() - t0) / 1_000_000} ms")
        t0 = System.nanoTime()
        val bin = RawDeveloper.develop(img, 1999, options = opts)
        println("X-Trans binned develop: ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertEquals(5998, full.width); assertEquals(3998, full.height)
        assertEquals(5998 / 3, bin.width); assertEquals(3998 / 3, bin.height)
        System.getenv("SPEKTRA_RAF_DUMP")?.takeIf { it.isNotEmpty() }?.let { dir ->
            for ((name, li) in listOf("full" to full, "bin" to bin)) {
                val buf = java.nio.ByteBuffer.allocate(8 + li.width * li.height * 12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                buf.putInt(li.width).putInt(li.height)
                for (y in 0 until li.height) for (x in 0 until li.width) buf.putFloat(li.red(x, y)).putFloat(li.green(x, y)).putFloat(li.blue(x, y))
                File(dir, "xt2_$name.f32").writeBytes(buf.array())
            }
        }
    }

    /** The app's path: [RawLoader] on the file (read whole, X-Trans developer) gives the reader + developer result. */
    @Test fun xt2ThroughTheLoader() {
        val dir = System.getenv("SPEKTRA_RAW_SAMPLES")
        Assume.assumeTrue("SPEKTRA_RAW_SAMPLES not set", !dir.isNullOrEmpty())
        val file = File(dir, "RAF/00001_00_10s.RAF")
        Assume.assumeTrue("missing $file", file.isFile)
        val img = assertNotNull(RawReader.read(file.readBytes()))
        fun bits(li: LinearImage): ShortArray {
            val b = li.halfRgba.duplicate().order(li.halfRgba.order()); b.position(0)
            return ShortArray(li.width * li.height * 4).also { b.asShortBuffer().get(it) }
        }
        for ((edge, fast) in listOf(2048 to true, 6000 to false)) {
            val got = assertNotNull(RawFile.open(file).use { RawLoader.load(it, edge, fast) }, "load $edge $fast")
            val ref = RawDeveloper.develop(img, edge, fast)
            assertEquals(ref.width to ref.height, got.image.width to got.image.height)
            assertEquals(5998 to 3998, got.fullWidth to got.fullHeight)
            assertContentEquals(bits(ref), bits(got.image), "load $edge $fast")
        }
    }

    @Test fun xt2Dark() = check("00001_00_0.1s.RAF", 0x44d0bf4dL, 1022f, doubleArrayOf(475.0, 302.0, 2174.0))

    @Test fun xt2Bright() = check("00001_00_10s.RAF", 0xb44703c2L, 1023f, doubleArrayOf(495.0, 302.0, 743.0))
}
