package app.spektralab.raw

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * Cross-format check used by every reader test: a file one reader decodes must be claimed by
 * that reader through [RawReader.read] (same frame, make and model) and refused by every other
 * registered reader. Several maker formats are TIFF with the same magic and only differ by
 * Make, and DNG must never be claimed by a maker reader or the other way round.
 */
object RawFormatCheck {
    /** Drop-in for `XxxReader.read(bytes)`: the reader's result, after checking that only [format] claims [bytes]. */
    fun read(format: String, bytes: ByteArray): RawImage? {
        val f = RawReader.formats.firstOrNull { it.name == format } ?: fail("no registered format $format")
        val own = f.read(bytes) ?: return null
        claims(format, bytes, own)
        return own
    }

    /** Asserts that [RawReader.read] gives [format]'s image for [bytes] and that no other reader accepts it. */
    fun claims(format: String, bytes: ByteArray, own: RawImage? = null) {
        val (name, img) = assertNotNull(RawReader.readFormat(bytes), "$format: RawReader.read returned null")
        assertEquals(format, name, "file of $format claimed by $name")
        if (own != null) {
            assertEquals(own.make, img.make, format); assertEquals(own.model, img.model, format)
            assertEquals(own.rawWidth, img.rawWidth, format); assertEquals(own.rawHeight, img.rawHeight, format)
            assertEquals(listOf(own.cropX, own.cropY, own.cropWidth, own.cropHeight), listOf(img.cropX, img.cropY, img.cropWidth, img.cropHeight), format)
            assertContentEquals(own.data, img.data, format)
        }
        for (other in RawReader.formats) {
            if (other.name == format) continue
            val got = try { other.read(bytes) } catch (e: Exception) { null }
            assertNull(got, "$format file also accepted by the ${other.name} reader")
        }
    }
}

class RawReaderTest {
    /** What LibRaw 0.22.2 reports for each real sample (raw size; make / model as the reader names them). */
    private class Expected(val format: String, val make: String, val model: String, val rawWidth: Int, val rawHeight: Int)

    private val expected = mapOf(
        "ARW/00001_00_0.1s.ARW" to Expected("ARW", "SONY", "ILCE-7SM2", 4288, 2848),
        "ARW/00001_00_10s.ARW" to Expected("ARW", "SONY", "ILCE-7SM2", 4288, 2848),
        "ARW/RAW_SONY_ILCE-7M4.ARW" to Expected("ARW", "SONY", "ILCE-7M4", 4736, 3132),
        "CR2/jeremytorres_little_endian.CR2" to Expected("CR2", "Canon", "Canon EOS 5D Mark II", 5792, 3804),
        "CR2/rubyraw_IMG_2112.CR2" to Expected("CR2", "Canon", "Canon EOS 7D", 5360, 3516),
        "CR3/RAW_CANON_R6.CR3" to Expected("CR3", "Canon", "Canon EOS R6", 3584, 2386),
        "DNG/RAW_RICOH_GR3X.DNG" to Expected("DNG", "RICOH IMAGING COMPANY, LTD.", "RICOH GR IIIx", 6112, 4064),
        "DNG/hdrplus_0006_20160722_115157_431_payload_N000.dng" to Expected("DNG", "google", "sailfish", 4048, 3036),
        "DNG/rawnerf_bikes_IMG_3384.dng" to Expected("DNG", "Apple", "iPhone X", 4032, 3024),
        "NEF/RAW_NIKON_ZFC.NEF" to Expected("NEF", "NIKON CORPORATION", "NIKON Z fc", 5600, 3728),
        "NEF/blauwaldt_goexif_nikon_d7500_DSC_0404.NEF" to Expected("NEF", "NIKON CORPORATION", "NIKON D7500", 5600, 3728),
        "NEF/imagut_001.nef" to Expected("NEF", "NIKON CORPORATION", "NIKON D70s", 3040, 2014),
        "NEF/jeremytorres_big_endian.NEF" to Expected("NEF", "NIKON CORPORATION", "NIKON D700", 4288, 2844),
        "ORF/RAW_OLYMPUS_E3.ORF" to Expected("ORF", "OLYMPUS IMAGING CORP.", "E-3", 3720, 2800),
        "RAF/00001_00_0.1s.RAF" to Expected("RAF", "FUJIFILM", "X-T2", 6160, 4032),
        "RAF/00001_00_10s.RAF" to Expected("RAF", "FUJIFILM", "X-T2", 6160, 4032),
    )

    /**
     * Samples no reader decodes; the app falls back to the system decoder for them:
     * - the Coolpix E5700 NEF has a 4-colour CMYG CFA, which the developer does not handle;
     * - the Canon 6D lossy linear DNG stores LinearRaw as lossy (baseline) JPEG tiles, Compression 34892,
     *   and the DNG reader has no baseline JPEG decoder (it reads uncompressed, lossless JPEG and deflate only).
     */
    private val unsupported = setOf("NEF/jeremytorres_little_endian_no_jpeg.NEF", "DNG/photoprism_canon_eos_6d_lossy_linear.dng")

    private fun samples(): File? = System.getenv("SPEKTRA_RAW_SAMPLES")?.let(::File)?.takeIf { it.isDirectory }

    @Test
    fun everyRealSampleIsClaimedByItsOwnReaderOnly() {
        val root = samples() ?: return
        val seen = HashSet<String>()
        root.listFiles()!!.filter { it.isDirectory && !it.name.startsWith("_") }.sortedBy { it.name }.forEach { dir ->
            dir.listFiles()!!.sortedBy { it.name }.forEach { f ->
                val key = "${dir.name}/${f.name}"
                seen += key
                val bytes = f.readBytes()
                if (key in unsupported) {
                    assertNull(RawReader.read(bytes), key)
                    return@forEach
                }
                val e = expected[key] ?: fail("sample $key has no expectation; add it here")
                RawFormatCheck.claims(e.format, bytes)
                val img = RawReader.read(bytes)!!
                assertEquals(e.make, img.make.trim(), key)
                assertEquals(e.model, img.model.trim(), key)
                assertEquals(e.rawWidth to e.rawHeight, img.rawWidth to img.rawHeight, key)
                assertEquals(true, RawDeveloper.supports(img), key)
                println("$key -> ${e.format}: ${img.rawWidth}x${img.rawHeight} crop ${img.cropX},${img.cropY} ${img.cropWidth}x${img.cropHeight} " +
                    "black ${img.black.toList()} white ${img.white} wb ${img.wb.toList()} orientation ${img.orientation}")
            }
        }
        assertEquals(expected.keys + unsupported, seen, "sample inventory")
    }

    /**
     * With SPEKTRA_RAW_SAMPLES and `-Dspektra.rawdump=DIR`: develops every real sample binned (2x2
     * Bayer cells, 3x3 X-Trans cells; no highlight reconstruction or denoise; orientation ignored) and
     * writes `<format>_<name>.f32` (int32 width, height, then float32 linear sRGB) and `.json` (crop,
     * cell, levels, colour) for `tools/develop_compare.py`, which compares them with LibRaw.
     */
    @Test
    fun dumpDevelopedSamplesForLibRaw() {
        val root = samples() ?: return
        val out = System.getProperty("spektra.rawdump")?.takeIf { it.isNotEmpty() }?.let { File(it).apply { mkdirs() } } ?: return
        val plain = RawDeveloper.Options(reconstructHighlights = false, chromaDenoise = false)
        for (key in expected.keys.sorted()) {
            val f = File(root, key)
            val img = RawReader.read(f.readBytes()) ?: continue
            val up = RawImage(img.rawWidth, img.rawHeight, img.data, img.cfa, img.black, img.white, img.cropX, img.cropY,
                img.cropWidth, img.cropHeight, img.wb, img.camToSrgb, 1, img.baselineExposure, img.gainMaps, img.make, img.model)
            val cell = if (img.cfa.isBayer) 2 else 3
            val li = RawDeveloper.develop(up, maxOf(img.cropWidth, img.cropHeight) / cell, fast = true, options = plain)
            val buf = java.nio.ByteBuffer.allocate(8 + li.width * li.height * 12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            buf.putInt(li.width).putInt(li.height)
            for (y in 0 until li.height) for (x in 0 until li.width) buf.putFloat(li.red(x, y)).putFloat(li.green(x, y)).putFloat(li.blue(x, y))
            val name = key.replace('/', '_')
            File(out, "$name.f32").writeBytes(buf.array())
            File(out, "$name.json").writeText("""{"file": "${f.absolutePath}", "crop": [${img.cropX}, ${img.cropY}, ${img.cropWidth}, ${img.cropHeight}], """ +
                """"cell": $cell, "black": [${img.black.take(4).joinToString()}], "white": ${img.white}, "wb": [${img.wb.joinToString()}], """ +
                """"camToSrgb": [${img.camToSrgb.joinToString()}], "orientation": ${img.orientation}}""")
        }
    }

    @Test
    fun strippedSamplesNeverDecodeToAnImageOfTheWrongFormat() {
        val dir = samples()?.let { File(it, "_metadata_only") }?.takeIf { it.isDirectory } ?: return
        for (f in dir.listFiles()!!.sortedBy { it.name }) {
            val got = RawReader.readFormat(f.readBytes()) ?: continue
            // a truncated file may still yield an image only from its own format's reader
            assertEquals(f.extension.uppercase(), got.first, f.name)
        }
    }

    @Test
    fun dngFixturesAreReadAsDng() {
        RawFormatCheck.claims("DNG", RawFixtures.bayerDng(64, 48, seed = 1))
        val merged = java.io.ByteArrayOutputStream().also { RawFixtures.writeMerged(it, 64, 48, RawFixtures.mergedRgb(64, 48, seed = 2)) }.toByteArray()
        RawFormatCheck.claims("DNG", merged)
    }

    @Test
    fun aMakerDngIsReadAsDngNotByTheMakerReader() {
        // the DNG fixture rewritten as a Nikon / Sony / Pentax / Samsung / Olympus / Canon / Panasonic DNG
        for ((make, model) in listOf("NIKON CORPORATION" to "NIKON D7500", "SONY" to "ILCE-7M4", "PENTAX" to "PENTAX K-3 II",
                "SAMSUNG" to "NX1", "OLYMPUS IMAGING CORP." to "E-3", "Canon" to "Canon EOS R6", "Panasonic" to "DC-S1",
                "FUJIFILM" to "X-T2", "LEICA" to "LEICA M11")) {
            val bytes = RawFixtures.bayerDng(64, 48, seed = 3, make = make, model = model)
            RawFormatCheck.claims("DNG", bytes)
        }
    }

    @Test
    fun nonRawFilesAreRefusedByEveryReader() {
        for (bytes in listOf(ByteArray(0), ByteArray(16), ByteArray(4096) { it.toByte() },
                TiffBuilder().build(TiffBuilder.Ifd().ascii(TiffTags.MAKE, "NIKON CORPORATION").long(256, 64).long(257, 48)))) {
            assertNull(RawReader.read(bytes))
        }
    }
}
