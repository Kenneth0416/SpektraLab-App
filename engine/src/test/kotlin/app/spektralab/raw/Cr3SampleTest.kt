package app.spektralab.raw

import org.junit.Assume.assumeTrue
import java.io.File
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real CR3 files from the directory in env `SPEKTRA_RAW_SAMPLES` (`<dir>/CR3/...`), skipped
 * when unset or missing. Expected values are LibRaw 0.22.2 (rawpy 0.28) output:
 * crc32 of `raw_image` as little-endian uint16, `sizes`, `black_level_per_channel`,
 * `camera_white_level_per_channel`, `camera_whitebalance` (see tools/cr3_reference.py).
 */
class Cr3SampleTest {
    private fun sample(name: String): ByteArray {
        val dir = System.getenv("SPEKTRA_RAW_SAMPLES")
        assumeTrue("SPEKTRA_RAW_SAMPLES not set", !dir.isNullOrEmpty())
        val f = File(dir, "CR3/$name")
        assumeTrue("missing $f", f.isFile)
        return f.readBytes()
    }

    private fun crc(data: ShortArray): Long {
        val b = ByteArray(data.size * 2)
        for (i in data.indices) { b[2 * i] = data[i].toByte(); b[2 * i + 1] = (data[i].toInt() shr 8).toByte() }
        return CRC32().apply { update(b) }.value
    }

    /** Canon EOS R6, C-RAW (CRX header v0x200, 3 wavelet levels, one tile, QP tables). */
    @Test
    fun canonR6CRaw() {
        val bytes = sample("RAW_CANON_R6.CR3")
        val t0 = System.nanoTime()
        val img = assertNotNull(Cr3Reader.read(bytes))
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("R6 CR3 decoded in $ms ms")
        assertEquals(3584, img.rawWidth)
        assertEquals(2386, img.rawHeight)
        assertEquals(0x55d53afeL, crc(img.data), "mosaic differs from LibRaw raw_image")
        assertEquals("Canon", img.make)
        assertEquals("Canon EOS R6", img.model)
        assertEquals(CfaPattern.RGGB.colors.toList(), img.cfa.colors.toList())
        assertEquals(listOf(512f, 512f, 512f, 512f), img.black.toList())
        assertEquals(14008f, img.white)                     // LibRaw linear_max; LibRaw maximum is 16383
        assertEquals(listOf(156, 108, 3407, 2271), listOf(img.cropX, img.cropY, img.cropWidth, img.cropHeight))
        assertEquals(1, img.orientation)
        // as-shot multipliers 1853 / 1024 / 1667 (R / G / B) -> green-normalised gains
        assertEquals(1853.0 / 1024, img.wb[0].toDouble(), 1e-5)
        assertEquals(1.0, img.wb[1].toDouble(), 1e-6)
        assertEquals(1667.0 / 1024, img.wb[2].toDouble(), 1e-5)
        assertTrue(RawDeveloper.supports(img))
    }

    private fun metadataOnly(name: String): ByteArray {
        val dir = System.getenv("SPEKTRA_RAW_SAMPLES")
        assumeTrue("SPEKTRA_RAW_SAMPLES not set", !dir.isNullOrEmpty())
        val f = File(dir, "_metadata_only/$name")
        assumeTrue("missing $f", f.isFile)
        return f.readBytes()
    }

    /** Largest raw track's CMP1 header. */
    private fun rawHeader(info: Cr3Reader.Info) = info.tracks.mapNotNull { it.header }.maxBy { it.width.toLong() * it.height }

    /**
     * Truncated fixtures (headers and maker notes only, no image data): container and metadata
     * parse, the reader returns null. Expected values: LibRaw 0.21.2 `raw-identify -v` (camera,
     * full size, as-shot WB, linearity limit) and the CMP1 bytes as LibRaw's crxParseImageHeader reads them.
     */
    @Test
    fun eosM50LosslessHeaders() {
        val b = metadataOnly("exiftool_CanonRaw.cr3")
        val info = assertNotNull(Cr3Reader.scan(b))
        val h = rawHeader(info)
        assertEquals(listOf(0x100, 6288, 4056, 3144, 4056, 14, 0, 0, 0), listOf(h.version, h.width, h.height, h.tileWidth, h.tileHeight, h.nBits, h.encType, h.levels, h.cfaLayout))
        val m = assertNotNull(Cr3Reader.metadata(b, info))
        assertEquals("Canon EOS M50", m.model)
        assertEquals(listOf(1104, 1024, 1024, 1789), m.wb?.toList())
        assertEquals(14338, m.specularWhite)
        assertNull(Cr3Reader.read(b))
    }

    @Test
    fun eosR6AndR1Headers() {
        val r6 = metadataOnly("exiv2_Canon-R6-pruned.CR3")
        val i6 = assertNotNull(Cr3Reader.scan(r6))
        val h6 = rawHeader(i6)
        assertEquals(listOf(0x200, 3584, 2386, 3, 872), listOf(h6.version, h6.width, h6.height, h6.levels, h6.mdatHdrSize))
        assertEquals("Canon EOS R6", Cr3Reader.metadata(r6, i6)?.model)
        assertEquals(listOf(156, 108, 3563, 2379), Cr3Reader.metadata(r6, i6)?.sensorInfo?.toList()?.subList(5, 9))
        assertNull(Cr3Reader.read(r6))

        val r1 = metadataOnly("pub_exif_reader_t.CR3")
        val i1 = assertNotNull(Cr3Reader.scan(r1))
        val h1 = rawHeader(i1)
        assertEquals(listOf(0x100, 6160, 4144, 6160, 0), listOf(h1.version, h1.width, h1.height, h1.tileWidth, h1.levels))
        assertEquals("Canon EOS R1", Cr3Reader.metadata(r1, i1)?.model)
        assertNull(Cr3Reader.read(r1))
    }

    /**
     * Developed colour against LibRaw's own render (rawpy postprocess: camera WB, sRGB, linear,
     * no auto-bright, half size): mean R/G and B/G over the frame were 1.651 and 0.7945 there.
     * Ours differ slightly by design (Bradford adaptation in XYZ instead of camera-space WB).
     */
    @Test
    fun canonR6DevelopsLikeLibRaw() {
        val img = assertNotNull(Cr3Reader.read(sample("RAW_CANON_R6.CR3")))
        val lin = RawDeveloper.develop(img, 850)
        var r = 0.0; var g = 0.0; var b = 0.0
        for (y in 0 until lin.height) for (x in 0 until lin.width) { r += lin.red(x, y); g += lin.green(x, y); b += lin.blue(x, y) }
        assertEquals(1.651, r / g, 1.651 * 0.04)
        assertEquals(0.7945, b / g, 0.7945 * 0.04)
    }
}
