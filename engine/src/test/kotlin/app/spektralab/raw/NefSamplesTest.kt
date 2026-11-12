package app.spektralab.raw

import org.junit.Assume
import java.io.File
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real Nikon files from `$SPEKTRA_RAW_SAMPLES/NEF` (skipped when unset or missing). All expected
 * values were read with LibRaw 0.22.2 (rawpy 0.28.0, `tools/nef_reference.py`): the CRC32 is over
 * `raw_image` (full frame incl. margins, not black-subtracted) as little-endian uint16.
 */
class NefSamplesTest {
    private class Expect(
        val file: String, val w: Int, val h: Int, val crc: Long,
        val cfaAtCrop: String, val black: Float, val white: Float,
        val crop: IntArray, val orientation: Int, val wb: DoubleArray, val xyz0: Double,
    )

    private val expected = listOf(
        // D7500: 14-bit lossless NEF compressed, II, WB from tag 0x0c (ColorBalance 0226 is not decrypted by LibRaw)
        Expect("blauwaldt_goexif_nikon_d7500_DSC_0404.NEF", 5600, 3728, 0xfc7c72c8, "RGGB", 400f, 16383f,
            intArrayOf(16, 8, 5568, 3712), 6, doubleArrayOf(2.2275390625, 1.0, 1.619140625), 0.8813),
        // Z fc: 12-bit lossless, black 1008/4
        Expect("RAW_NIKON_ZFC.NEF", 5600, 3728, 0x591483cd, "RGGB", 252f, 4095f,
            intArrayOf(16, 8, 5568, 3712), 1, doubleArrayOf(1.908203125, 1.0, 1.361328125), 1.164),
        // D700: 14-bit lossless, MM, WB from the encrypted ColorBalance 0209 block, visible area 2..4286
        Expect("jeremytorres_big_endian.NEF", 4288, 2844, 0x8a111eb8, "RGGB", 0f, 16383f,
            intArrayOf(2, 0, 4284, 2844), 8, doubleArrayOf(446.0, 256.0, 433.0), 0.8139),
        // D70s: 12-bit lossy type 1 with curve and split row, MM, plain ColorBalance 0103
        Expect("imagut_001.nef", 3040, 2014, 0x70a07425, "BGGR", 0f, 4095f,
            intArrayOf(0, 0, 3039, 2014), 1, doubleArrayOf(536.0, 256.0, 460.0), 0.7732),
    )

    private fun sample(name: String): File {
        val dir = System.getenv("SPEKTRA_RAW_SAMPLES")
        Assume.assumeTrue("SPEKTRA_RAW_SAMPLES not set", !dir.isNullOrEmpty())
        val f = File(dir, "NEF/$name")
        Assume.assumeTrue("missing $f", f.isFile)
        return f
    }

    private fun crc(data: ShortArray): Long {
        val b = ByteArray(data.size * 2)
        for (i in data.indices) { b[2 * i] = data[i].toByte(); b[2 * i + 1] = (data[i].toInt() ushr 8).toByte() }
        return CRC32().apply { update(b) }.value
    }

    @Test
    fun matchesLibRaw() {
        for (e in expected) {
            val bytes = sample(e.file).readBytes()
            val t0 = System.nanoTime()
            val img = assertNotNull(RawFormatCheck.read("NEF", bytes), e.file)
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("${e.file}: decoded in $ms ms")
            assertEquals(e.w, img.rawWidth, e.file); assertEquals(e.h, img.rawHeight, e.file)
            assertEquals(e.crc, crc(img.data), "${e.file} mosaic CRC")
            val cfa = (0 until 4).joinToString("") { "RGB"[img.cfa.color(img.cropY + it / 2, img.cropX + it % 2)].toString() }
            assertEquals(e.cfaAtCrop, cfa, "${e.file} CFA at crop origin")
            for (v in img.black) assertEquals(e.black, v, "${e.file} black")
            assertEquals(e.white, img.white, "${e.file} white")
            assertEquals(e.crop.toList(), listOf(img.cropX, img.cropY, img.cropWidth, img.cropHeight), "${e.file} crop")
            assertEquals(e.orientation, img.orientation, "${e.file} orientation")
            // wb = green / channel multiplier ratio as RawColor derives it from the as-shot multipliers
            val m = e.wb
            assertEquals((m[0] / m[1]).toFloat(), img.wb[0], 1e-5f, "${e.file} wb R")
            assertEquals(1f, img.wb[1], 1e-6f)
            assertEquals((m[2] / m[1]).toFloat(), img.wb[2], 1e-5f, "${e.file} wb B")
            val entry = assertNotNull(CameraMatrices.find(img.make, img.model))
            assertEquals(e.xyz0, entry.xyzToCamera!![0][0], 1e-4)
        }
    }

    /**
     * Developed colour against a LibRaw render (camera WB, sRGB, linear, half size): mean R/G and B/G
     * over the central quarter, unclipped pixels only. LibRaw white-balances in camera space and
     * [RawColor] with Bradford in XYZ, so a few percent difference is expected away from daylight.
     */
    @Test
    fun developsLikeLibRaw() {
        val ref = mapOf(
            "RAW_NIKON_ZFC.NEF" to (0.8998 to 0.4950),
            "blauwaldt_goexif_nikon_d7500_DSC_0404.NEF" to (0.9030 to 0.9261),
            "jeremytorres_big_endian.NEF" to (1.0669 to 0.9809),
            "imagut_001.nef" to (0.7594 to 0.8680),
        )
        for ((name, rg) in ref) {
            val img = assertNotNull(RawFormatCheck.read("NEF", sample(name).readBytes()))
            val out = RawDeveloper.develop(img, maxLongEdge = maxOf(img.cropWidth, img.cropHeight) / 2, fast = true,
                options = RawDeveloper.Options(reconstructHighlights = false, chromaDenoise = false))
            var r = 0.0; var g = 0.0; var b = 0.0
            for (y in out.height / 4 until out.height * 3 / 4) for (x in out.width / 4 until out.width * 3 / 4) {
                val pr = out.red(x, y); val pg = out.green(x, y); val pb = out.blue(x, y)
                if (maxOf(pr, pg, pb) >= 0.9f) continue
                r += pr; g += pg; b += pb
            }
            println("$name developed R/G ${r / g} B/G ${b / g} (LibRaw ${rg.first} / ${rg.second})")
            assertEquals(rg.first, r / g, rg.first * 0.02, "$name R/G")
            assertEquals(rg.second, b / g, rg.second * 0.02, "$name B/G")
        }
    }

    /** Truncated NEF fixtures (headers only) and every other format's samples: null, never an exception. */
    @Test
    fun othersAndTruncatedAreNull() {
        val dir = System.getenv("SPEKTRA_RAW_SAMPLES")
        Assume.assumeTrue("SPEKTRA_RAW_SAMPLES not set", !dir.isNullOrEmpty() && File(dir).isDirectory)
        val files = File(dir).walk().filter { it.isFile && it.length() < 80_000_000 &&
            it.extension.lowercase() in setOf("nef", "cr2", "cr3", "arw", "dng", "orf", "raf", "rw2", "pef") &&
            (it.parentFile.name != "NEF") }.toList()
        for (f in files) assertNull(NefReader.read(f.readBytes()), f.name)
    }

    @Test
    fun coolpixCygmIsNull() {
        val bytes = sample("jeremytorres_little_endian_no_jpeg.NEF").readBytes()
        assertNull(NefReader.read(bytes))
    }
}
