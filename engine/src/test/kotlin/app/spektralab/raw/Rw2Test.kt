package app.spektralab.raw

import java.io.File
import java.util.Random
import java.util.zip.CRC32
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A 128-bit packet written most significant bit first (the order Panasonic's codecs read it in). */
internal class Page128 {
    private val bits = IntArray(128)
    private var at = 0
    fun put(value: Int, n: Int) {
        for (k in n - 1 downTo 0) bits[at++] = (value ushr k) and 1
    }
    val used get() = at
    /** Bit s of the stream is bit 127 - s of the little-endian 128-bit number. */
    fun bytes(): ByteArray {
        val b = ByteArray(16)
        for (s in 0 until 128) if (bits[s] == 1) { val bit = 127 - s; b[bit shr 3] = (b[bit shr 3].toInt() or (1 shl (bit and 7))).toByte() }
        return b
    }
}

/**
 * Synthetic Panasonic RW2 files: a real RW2 container (II 0x55 header, IFD0 with the Panasonic raw
 * tags) around pixel data written by in-test encoders for each codec. The files are deterministic,
 * and [Rw2Test.libRawChecksums] pins what LibRaw 0.22.2 (rawpy) decodes from exactly these bytes.
 */
internal object Rw2Synth {
    class Spec(
        val name: String, val model: String, val encoding: Int, val bpp: Int, val width: Int, val height: Int,
        val seed: Long, val make: String = "Panasonic",
        val borders: IntArray = intArrayOf(2, 4, 4, 6), // top, left, bottom margin, right margin
        val cfa: Int = 4, val black: IntArray = intArrayOf(0, 0, 0), val wb: IntArray? = intArrayOf(570, 263, 438), val redBlue: IntArray? = null,
        val orientation: Int = 1, val limit: Int = 0,
    )

    class Built(val file: ByteArray, val mosaic: ShortArray)

    fun build(s: Spec): Built {
        val rng = Random(s.seed)
        val pixels = s.width * s.height
        val (stream, mosaic) = when (s.encoding) {
            4 -> encodeV4(s, rng)
            5 -> { val v = smooth(s, rng); packRotated(packBits(v, s.bpp, if (s.bpp == 12) 10 else 9, s.width)) to ShortArray(pixels) { v[it].toShort() } }
            6 -> encodeV6(s, rng)
            7 -> { val v = smooth(s, rng); packBits(v, 14, 9, s.width) to ShortArray(pixels) { v[it].toShort() } }
            else -> error("encoding")
        }
        return Built(container(s, stream), mosaic)
    }

    fun container(s: Spec, data: ByteArray): ByteArray {
        val ifd = TiffBuilder.Ifd()
        ifd.undefined(1, "0300".toByteArray())
        ifd.short(2, s.width).short(3, s.height)
        ifd.short(4, s.borders[0]).short(5, s.borders[1]).short(6, s.height - s.borders[2]).short(7, s.width - s.borders[3])
        ifd.short(9, s.cfa).short(0x0a, s.bpp)
        val limit = if (s.limit != 0) s.limit else (1 shl s.bpp) - 1
        ifd.short(0x0e, limit).short(0x0f, limit).short(0x10, limit)
        ifd.short(0x1c, s.black[0]).short(0x1d, s.black[1]).short(0x1e, s.black[2])
        s.wb?.let { ifd.short(0x24, it[0]).short(0x25, it[1]).short(0x26, it[2]) }
        s.redBlue?.let { ifd.short(0x11, it[0]).short(0x12, it[1]) }
        ifd.short(0x2d, s.encoding)
        ifd.ascii(TiffTags.MAKE, s.make).ascii(TiffTags.MODEL, s.model).short(TiffTags.ORIENTATION, s.orientation)
        ifd.blobs(0x118, 0x117, listOf(data))
        val out = TiffBuilder(true).build(ifd)
        out[2] = 0x55
        return out
    }

    /**
     * A smooth, noisy, roughly neutral scene in [bpp]-bit range, row-major: each CFA colour is
     * scaled by the inverse of its white balance gain, so camera white balance makes it grey.
     */
    fun smooth(s: Spec, rng: Random): IntArray {
        val max = (1 shl s.bpp) - 1
        val cfa = when (s.cfa) { 1 -> CfaPattern.RGGB; 2 -> CfaPattern.GRBG; 3 -> CfaPattern.GBRG; else -> CfaPattern.BGGR }
        val wb = s.wb ?: intArrayOf(1, 1, 1)
        return IntArray(s.width * s.height) { i ->
            val x = i % s.width; val y = i / s.width
            val gain = wb[cfa.color(y, x)].toDouble() / wb[1]
            val base = (0.12 + 0.45 * (x.toDouble() / s.width) + 0.15 * (y.toDouble() / s.height)) * max / gain
            (base + rng.nextGaussian() * max * 0.004).toInt().coerceIn(16, max)
        }
    }

    /** [perPacket] samples of [bits] bits per 16-byte packet, least significant bit first. */
    fun packBits(v: IntArray, bits: Int, perPacket: Int, width: Int): ByteArray {
        require(width % perPacket == 0)
        val out = ByteArray(v.size / perPacket * 16)
        for (p in 0 until v.size / perPacket) {
            var bit = 0
            for (k in 0 until perPacket) {
                val x = v[p * perPacket + k]
                for (b in 0 until bits) { if ((x ushr b) and 1 == 1) out[p * 16 + (bit shr 3)] = (out[p * 16 + (bit shr 3)].toInt() or (1 shl (bit and 7))).toByte(); bit++ }
            }
        }
        return out
    }

    /** Pads to whole 0x4000 blocks and applies the file layout (block bytes 0x2008.. first, then 0..0x2008). */
    fun packRotated(packets: ByteArray): ByteArray {
        val blocks = (packets.size + 0x3fff) / 0x4000
        val buf = packets.copyOf(blocks * 0x4000)
        val out = ByteArray(buf.size)
        for (b in 0 until blocks) {
            val o = b * 0x4000
            System.arraycopy(buf, o + 0x2008, out, o, 0x4000 - 0x2008)
            System.arraycopy(buf, o, out, o + 0x4000 - 0x2008, 0x2008)
        }
        return out
    }

    // -------------------------------------------------------------- classic codec
    private fun step(pred: Int, sh: Int, j: Int): Int {
        if (j == 0) return pred
        var p = pred - (0x80 shl sh)
        if (p < 0 || sh == 4) p = p and ((1 shl sh) - 1)
        return p + (j shl sh)
    }

    fun encodeV4(s: Spec, rng: Random): Pair<ByteArray, ShortArray> {
        val target = smooth(s, rng)
        // a few spikes so the larger shifts and the clamping paths are exercised
        for (k in 0 until target.size / 200) target[rng.nextInt(target.size)] = rng.nextInt(4096)
        val packets = target.size / 14
        val stream = ByteArray(packets * 16)
        val recon = ShortArray(target.size)
        for (p in 0 until packets) {
            val page = Page128()
            val pred = IntArray(2); val nonz = IntArray(2)
            var sh = 0
            for (i in 0 until 14) {
                val t = target[p * 14 + i].coerceAtMost(4095)
                val c = i and 1
                if (i % 3 == 2) {
                    val code = rng.nextInt(4)
                    page.put(code, 2); sh = 4 shr (3 - code)
                }
                if (nonz[c] != 0) {
                    var best = 0; var bestErr = Int.MAX_VALUE
                    for (j in 0..255) { val e = Math.abs(step(pred[c], sh, j) - t); if (e < bestErr) { bestErr = e; best = j } }
                    page.put(best, 8)
                    pred[c] = step(pred[c], sh, best)
                } else {
                    nonz[c] = t shr 4
                    page.put(nonz[c], 8)
                    if (nonz[c] != 0 || i > 11) { page.put(t and 15, 4); pred[c] = (nonz[c] shl 4) or (t and 15) }
                }
                recon[p * 14 + i] = pred[c].toShort()
            }
            check(page.used <= 128)
            System.arraycopy(page.bytes(), 0, stream, p * 16, 16)
        }
        return packRotated(stream) to recon
    }

    // -------------------------------------------------------------- S1 page codec
    /** Random but valid pages (every field combination decodes); the expected mosaic comes from LibRaw. */
    fun encodeV6(s: Spec, rng: Random): Pair<ByteArray, ShortArray> {
        val twelve = s.bpp == 12
        val per = if (twelve) 14 else 11
        val pages = s.width * s.height / per
        val stream = ByteArray(pages * 16)
        val lowCode = if (twelve) 8 else 10
        for (p in 0 until pages) {
            val page = Page128()
            val first = if (twelve) 12 else 14
            val base = if (twelve) 0x300 else 0xC00
            page.put(base + rng.nextInt(base / 3), first); page.put(base + rng.nextInt(base / 3), first)
            val groups = if (twelve) 4 else 3
            for (g in 0 until groups) {
                page.put(rng.nextInt(4), 2)
                repeat(3) { page.put(rng.nextInt(1 shl lowCode).coerceAtMost((1 shl lowCode) - 1) / (1 + rng.nextInt(4)), lowCode) }
            }
            if (!twelve) page.put(0, 4) // unused tail
            System.arraycopy(page.bytes(), 0, stream, p * 16, 16)
        }
        return stream to ShortArray(0)
    }
}

class Rw2Test {
    private fun crc(m: ShortArray): Long {
        val c = CRC32()
        val b = ByteArray(m.size * 2)
        for (i in m.indices) { b[2 * i] = m[i].toByte(); b[2 * i + 1] = (m[i].toInt() shr 8).toByte() }
        c.update(b)
        return c.value
    }

    private val specs = listOf(
        // 14 px / 16 B classic packets: one partial block, and two blocks
        Rw2Synth.Spec("v4_small", "DMC-LX3", 4, 12, 140, 64, 1),
        Rw2Synth.Spec("v4_blocks", "DMC-GF2", 4, 12, 280, 120, 2, black = intArrayOf(5, 7, 9), orientation = 6),
        Rw2Synth.Spec("v5_12", "DC-GH5s", 5, 12, 200, 72, 3, black = intArrayOf(60, 64, 62), cfa = 1),
        Rw2Synth.Spec("v5_14", "DC-GH5s", 5, 14, 180, 330, 4, black = intArrayOf(240, 256, 250), cfa = 2),
        Rw2Synth.Spec("v6_14", "DC-S1", 6, 14, 220, 48, 5, black = intArrayOf(1020, 1024, 1030), limit = 16383, cfa = 3),
        Rw2Synth.Spec("v6_12", "DC-S1", 6, 12, 280, 48, 6, black = intArrayOf(250, 256, 260), limit = 4095),
        Rw2Synth.Spec("v7_14", "DC-S1R", 7, 14, 180, 48, 7, black = intArrayOf(1020, 1024, 1030), limit = 16383),
    )

    /** CRC32 of the mosaic (little-endian uint16) that LibRaw 0.22.2 / rawpy decodes from the synthetic files. */
    private val libRawChecksums = mapOf(
        "v4_small" to 1943160795L, "v4_blocks" to 3220644733L,
        "v5_12" to 1770811596L, "v5_14" to 3632682573L,
        "v6_14" to 1070464778L, "v6_12" to 1569558468L,
        "v7_14" to 2646676265L,
    )

    private fun dumpDir(): File? = System.getProperty("spektra.rawdump")?.takeIf { it.isNotBlank() }?.let { File(it).also { d -> d.mkdirs() } }

    @Test
    fun syntheticFilesDecodeToWhatTheEncodersWrote() {
        val mismatches = ArrayList<String>()
        for (s in specs) {
            val built = Rw2Synth.build(s)
            dumpDir()?.let { d ->
                File(d, s.name + ".rw2").writeBytes(built.file)
                if (built.mosaic.isNotEmpty()) File(d, s.name + ".raw16").writeBytes(ByteArray(built.mosaic.size * 2) { i -> (built.mosaic[i / 2].toInt() shr (8 * (i % 2))).toByte() })
            }
            val img = assertNotNull(Rw2Reader.read(built.file), s.name)
            if (s.orientation == 1 && s.encoding != 6) dumpDir()?.let { d ->
                val dev = RawDeveloper.develop(img, 4096)
                File(d, s.name + ".developed.txt").printWriter().use { p ->
                    p.println("${dev.width} ${dev.height} ${img.cropX} ${img.cropY}")
                    for (y in 0 until dev.height) for (x in 0 until dev.width) p.println("${dev.red(x, y)} ${dev.green(x, y)} ${dev.blue(x, y)}")
                }
            }
            dumpDir()?.let { d -> File(d, s.name + ".ours16").writeBytes(ByteArray(img.data.size * 2) { i -> (img.data[i / 2].toInt() shr (8 * (i % 2))).toByte() }) }
            assertEquals(s.width, img.rawWidth, s.name)
            assertEquals(s.height, img.rawHeight, s.name)
            if (built.mosaic.isNotEmpty()) assertContentEquals(built.mosaic, img.data.copyOf(s.width * s.height), s.name)
            if (libRawChecksums.getValue(s.name) != crc(img.data)) mismatches += "${s.name}: ${crc(img.data)}"
            // geometry and levels
            assertEquals(s.borders[1], img.cropX, s.name)
            assertEquals(s.borders[0], img.cropY, s.name)
            assertEquals(s.width - s.borders[1] - s.borders[3], img.cropWidth, s.name)
            assertEquals(s.height - s.borders[0] - s.borders[2], img.cropHeight, s.name)
            assertEquals(s.orientation, img.orientation, s.name)
            assertEquals("Panasonic", img.make); assertEquals(s.model, img.model)
        }
        assertTrue(mismatches.isEmpty(), "differs from LibRaw's checksum: $mismatches")
    }

    @Test
    fun classicCodecLevelsFollowLibRaw() {
        // no black in the file: the camera table's 15; RawFormat 4 adds 15 to a file black
        val a = Rw2Reader.meta(Rw2Synth.build(specs[0]).file)!!
        assertContentEquals(floatArrayOf(15f, 15f, 15f), a.blackRgb)
        assertEquals(4095f, a.white)
        val b = Rw2Reader.meta(Rw2Synth.build(specs[1]).file)!!
        assertContentEquals(floatArrayOf(20f, 22f, 24f), b.blackRgb)
        assertEquals(0xfff.toFloat(), b.white)
        val c = Rw2Reader.meta(Rw2Synth.build(specs[2]).file)!!
        assertContentEquals(floatArrayOf(60f, 64f, 62f), c.blackRgb) // RawFormat 5: as stored
        val d = Rw2Reader.meta(Rw2Synth.build(specs[4]).file)!!
        assertEquals(16319f, d.white) // linearity limit 16383 - 64
        val e = Rw2Reader.meta(Rw2Synth.build(specs[5]).file)!!
        assertEquals(4079f, e.white)  // 4095 - 16
    }

    /** Expected values: rawpy 0.28 (LibRaw 0.22.2) on synthetic files with exactly these tags. */
    @Test
    fun tableFallbacksAndWhiteBalanceMatchLibRaw() {
        fun meta(model: String, enc: Int, bpp: Int, w: Int, black: IntArray, wb: IntArray? = intArrayOf(570, 263, 438), rb: IntArray? = null, limit: Int = 0, make: String = "Panasonic") =
            Rw2Reader.meta(Rw2Synth.container(Rw2Synth.Spec("x", model, enc, bpp, w, 64, 1, make = make, black = black, wb = wb, redBlue = rb, limit = limit), ByteArray(0x8000)))!!
        // no file black: the table's -15 is a fallback; the table's saturation overrides the bit depth
        meta("DMC-G1", 4, 12, 140, intArrayOf(0, 0, 0)).let { assertContentEquals(floatArrayOf(15f, 15f, 15f), it.blackRgb); assertEquals(3988f, it.white) }
        // file black wins and gets +15 for RawFormat 4
        meta("DMC-G1", 4, 12, 140, intArrayOf(7, 8, 9)).let { assertContentEquals(floatArrayOf(22f, 23f, 24f), it.blackRgb); assertEquals(3988f, it.white) }
        // all three levels must be set, otherwise the table is used
        meta("DMC-FZ28", 4, 12, 140, intArrayOf(0, 8, 9)).let { assertContentEquals(floatArrayOf(15f, 15f, 15f), it.blackRgb); assertEquals(3990f, it.white) }
        meta("DC-GH5s", 5, 12, 200, intArrayOf(0, 0, 0)).let { assertContentEquals(floatArrayOf(15f, 15f, 15f), it.blackRgb); assertEquals(4095f, it.white) }
        meta("DC-GH5s", 5, 14, 180, intArrayOf(1, 2, 3), limit = 16383).let { assertContentEquals(floatArrayOf(1f, 2f, 3f), it.blackRgb); assertEquals(16383f, it.white) }
        // Leica badges of Panasonic bodies behave like the Panasonic camera (LibRaw aliases them)
        for ((make, model) in listOf("LEICA" to "D-LUX 4", "LEICA" to "V-LUX 2", "LEICA CAMERA AG" to "D-LUX (Typ 109)", "LEICA" to "D-LUX 3")) {
            meta(model, 4, 12, 140, intArrayOf(5, 7, 9), make = make).let { assertContentEquals(floatArrayOf(20f, 22f, 24f), it.blackRgb, model); assertEquals(4095f, it.white, model) }
        }
        // white balance: levels, else red / blue balance over 256, else the matrix's daylight white
        meta("DMC-LX3", 4, 12, 140, intArrayOf(0, 0, 0), wb = null, rb = intArrayOf(512, 384)).wb.let { assertEquals(2.0f, it.wb[0], 1e-5f); assertEquals(1.5f, it.wb[2], 1e-5f) }
        meta("DMC-LX3", 4, 12, 140, intArrayOf(0, 0, 0), wb = null).wb.let { assertEquals(2.302f / 0.9159f, it.wb[0], 0.01f); assertEquals(1.3338f / 0.9159f, it.wb[2], 0.01f) }
    }

    @Test
    fun cfaFollowsTheTag() {
        assertEquals(CfaPattern.BGGR.toString(), Rw2Reader.meta(Rw2Synth.build(specs[0]).file)!!.cfa.toString())
        assertEquals(CfaPattern.RGGB.toString(), Rw2Reader.meta(Rw2Synth.build(specs[2]).file)!!.cfa.toString())
        assertEquals(CfaPattern.GRBG.toString(), Rw2Reader.meta(Rw2Synth.build(specs[3]).file)!!.cfa.toString())
        assertEquals(CfaPattern.GBRG.toString(), Rw2Reader.meta(Rw2Synth.build(specs[4]).file)!!.cfa.toString())
    }

    @Test
    fun developsToSaneColour() {
        val img = Rw2Reader.read(Rw2Synth.build(specs[0]).file)!!
        assertTrue(RawDeveloper.supports(img))
        assertTrue(img.wb[0] > 1f && img.wb[2] > 1f) // R and B boosted relative to G for this WB
    }

    @Test
    fun unsupportedAndBrokenInputReturnsNull() {
        val good = Rw2Synth.build(specs[0]).file
        assertNotNull(Rw2Reader.read(good))
        // wrong magic / not TIFF / empty
        assertNull(Rw2Reader.read(good.copyOf().also { it[2] = 42 }))
        assertNull(Rw2Reader.read(ByteArray(0)))
        assertNull(Rw2Reader.read(ByteArray(1000)))
        // truncated pixel data
        assertNull(Rw2Reader.read(good.copyOf(good.size - 0x4000)))
        // encodings that are not implemented / verified
        for (enc in intArrayOf(0, 2, 3, 8, 9)) {
            val s = Rw2Synth.Spec("x", "DMC-LX3", enc, 12, 140, 64, 1)
            val f = Rw2Synth.container(s, ByteArray(0x8000))
            assertNull(Rw2Reader.read(f), "encoding $enc")
        }
        // width not a multiple of the packet size
        assertNull(Rw2Reader.read(Rw2Synth.container(Rw2Synth.Spec("x", "DMC-LX3", 4, 12, 150, 64, 1), ByteArray(0x20000))))
        // unknown camera has no colour matrix
        assertNull(Rw2Reader.read(Rw2Synth.build(Rw2Synth.Spec("x", "NOT-A-CAMERA", 4, 12, 140, 64, 1)).file))
        // a different maker
        assertNull(Rw2Reader.read(Rw2Synth.build(Rw2Synth.Spec("x", "DMC-LX3", 4, 12, 140, 64, 1, make = "Canon")).file))
        // borders outside the frame
        assertNull(Rw2Reader.read(Rw2Synth.build(Rw2Synth.Spec("x", "DMC-LX3", 4, 12, 140, 64, 1, borders = intArrayOf(-1, 0, 0, 0))).file))
    }

    @Test
    fun corruptedBytesNeverThrow() {
        val rng = Random(99)
        val good = Rw2Synth.build(specs[1]).file
        repeat(300) {
            val f = good.copyOf()
            repeat(1 + rng.nextInt(20)) { f[rng.nextInt(minOf(f.size, 400))] = rng.nextInt(256).toByte() }
            Rw2Reader.read(f) // any result is fine, an exception is not
            Rw2Reader.read(f.copyOf(rng.nextInt(f.size)))
        }
    }

    // ------------------------------------------------------------ real camera files (optional)
    private fun sample(vararg path: String): File? {
        val dir = System.getenv("SPEKTRA_RAW_SAMPLES") ?: return null
        return File(dir, path.joinToString("/")).takeIf { it.isFile }
    }

    @Test
    fun realLumixHeaderMatchesLibRaw() {
        val f = sample("_metadata_only", "exiftool_Panasonic.rw2")
        assumeTrue("SPEKTRA_RAW_SAMPLES not set or sample missing", f != null)
        val bytes = f!!.readBytes()
        // values from rawpy 0.28 / LibRaw 0.22.2 on this DMC-LX3 file
        val m = assertNotNull(Rw2Reader.meta(bytes))
        assertEquals("DMC-LX3", m.model)
        assertEquals(3724, m.width); assertEquals(2754, m.height)
        assertEquals(4, m.encoding); assertEquals(12, m.bitsPerSample)
        assertEquals(listOf(8, 6, 3648, 2736), listOf(m.cropX, m.cropY, m.cropW, m.cropH))
        assertEquals("CfaPattern(2x2, BGGR)", m.cfa.toString())
        assertContentEquals(floatArrayOf(15f, 15f, 15f), m.blackRgb)
        assertEquals(4095f, m.white)
        assertEquals(1, m.orientation)
        // 570 / 263 / 438 as-shot
        assertEquals(570f / 263f, m.wb.wb[0], 1e-6f)
        assertEquals(438f / 263f, m.wb.wb[2], 1e-6f)
        // the file is a 12 KB header-only extract: no pixels, so no image
        assertNull(Rw2Reader.read(bytes))
    }
}
