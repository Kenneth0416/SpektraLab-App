package app.spektralab.raw

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The DNG layouts real cameras and converters write: lossless JPEG tiles / strips,
 * uncompressed tiles, packed 8..14 bit, linearization tables, repeating black levels,
 * ActiveArea, SubIFD placement and both byte orders.
 */
class DngFormatsTest {
    private val w = 96
    private val h = 64
    private val colorMatrix = doubleArrayOf(0.9, -0.3, -0.1, -0.4, 1.3, 0.15, -0.05, 0.2, 0.6)

    class Layout(
        val bps: Int = 16, val compression: Int = 1,
        val tile: Pair<Int, Int>? = null, val rowsPerStrip: Int = 64,
        val le: Boolean = true, val components: Int = 2, val predictor: Int = 1, val restartRows: Int = 0,
        val linearize: Boolean = false, val subIfd: Boolean = false,
    ) {
        override fun toString() = "bps=$bps comp=$compression tile=$tile rps=$rowsPerStrip le=$le nc=$components p=$predictor rst=$restartRows lin=$linearize sub=$subIfd"
    }

    /** Linearization used with [Layout.linearize]: stored s -> 4 s + 3. */
    private fun linear(s: Int) = 4 * s + 3

    private fun scene(x: Int, y: Int): DoubleArray {
        val u = x.toDouble() / w; val v = y.toDouble() / h
        return doubleArrayOf(0.08 + 0.5 * u, 0.1 + 0.45 * v, 0.55 - 0.4 * u * v)
    }

    /**
     * Stored sensor values for a scene seen through [colorMatrix]; [cfa] and [black] are
     * relative to the active area at ([activeTop], [activeLeft]).
     */
    private fun sensor(bits: Int, cfa: CfaPattern, black: IntArray, blackDim: Int, white: Int, activeTop: Int = 0, activeLeft: Int = 0, noise: Int = 0): IntArray {
        val toCam = Mat3.mul(colorMatrix, RawColor.SRGB_TO_XYZ_D65)
        val d65 = Mat3.mulVec(colorMatrix, RawColor.D65)
        val rnd = Random(7)
        val max = (1 shl bits) - 1
        return IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val ar = y - activeTop; val ac = x - activeLeft
            if (ar < 0 || ac < 0) return@IntArray rnd.nextInt(max + 1)              // masked border: junk
            val cam = Mat3.mulVec(toCam, scene(x, y))
            val c = cfa.color(ar, ac)
            val b = black[Math.floorMod(ar, blackDim) * blackDim + Math.floorMod(ac, blackDim)]
            (cam[c] / d65.max() * (white - b) + b + (if (noise > 0) rnd.nextInt(-noise, noise + 1) else 0)).toInt().coerceIn(0, max)
        }
    }

    private fun tileBlobs(stored: IntArray, l: Layout): Triple<List<ByteArray>, Int, Int> {
        val tw = l.tile?.first ?: w
        val th = l.tile?.second ?: l.rowsPerStrip
        val across = (w + tw - 1) / tw; val down = (h + th - 1) / th
        val blobs = ArrayList<ByteArray>()
        for (t in 0 until across * down) {
            val tx = t % across * tw; val ty = t / across * th
            // strips end at the image; tiles are always full size (padding replicates the edge)
            val rows = if (l.tile == null) minOf(th, h - ty) else th
            val s = IntArray(tw * rows) { i -> stored[minOf(ty + i / tw, h - 1) * w + minOf(tx + i % tw, w - 1)] }
            blobs += if (l.compression == 7) {
                val restart = if (l.restartRows > 0) l.restartRows * tw / l.components else 0
                // table c for component c (the last one repeated), as DNG writers do and LibRaw assumes
                LjpegEncoder.encode(s, tw / l.components, rows, l.components, l.bps, l.predictor, restart = restart,
                    tableFor = IntArray(l.components) { minOf(it, 1) })
            } else if (l.bps == 16) {
                ByteBuffer.allocate(s.size * 2).order(if (l.le) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN).apply { s.forEach { putShort(it.toShort()) } }.array()
            } else {
                val rb = Unpack.rowBytes(tw, l.bps).toInt()
                Pack.pack(s, l.bps, Unpack.BitOrder.MSB, tw, rb).copyOf(rb * rows)
            }
        }
        return Triple(blobs, tw, th)
    }

    private fun buildDng(
        stored: IntArray, l: Layout, cfa: CfaPattern = CfaPattern.RGGB, black: IntArray = intArrayOf(64), blackDim: Int = 1,
        white: Int, active: IntArray? = null, cropOrigin: IntArray? = null, cropSize: IntArray? = null, photometric: Int = 32803,
        sampleFormat: Int? = null,
    ): ByteArray {
        val raw = TiffBuilder.Ifd()
            .long(TiffTags.NEW_SUBFILE_TYPE, 0).long(TiffTags.IMAGE_WIDTH, w).long(TiffTags.IMAGE_LENGTH, h)
            .short(TiffTags.BITS_PER_SAMPLE, l.bps).short(TiffTags.COMPRESSION, l.compression).short(TiffTags.PHOTOMETRIC, photometric)
            .short(TiffTags.SAMPLES_PER_PIXEL, 1).short(TiffTags.PLANAR_CONFIG, 1)
            .short(TiffTags.CFA_REPEAT_PATTERN_DIM, cfa.height, cfa.width).byte(TiffTags.CFA_PATTERN, *cfa.colors)
            .short(TiffTags.BLACK_LEVEL_REPEAT_DIM, blackDim, blackDim).long(TiffTags.BLACK_LEVEL, *black)
            .long(TiffTags.WHITE_LEVEL, white)
        active?.let { raw.long(TiffTags.ACTIVE_AREA, *it) }
        sampleFormat?.let { raw.short(339, it) }
        cropOrigin?.let { raw.long(TiffTags.DEFAULT_CROP_ORIGIN, *it) }
        cropSize?.let { raw.long(TiffTags.DEFAULT_CROP_SIZE, *it) }
        val fileValues = if (l.linearize) {
            raw.short(TiffTags.LINEARIZATION_TABLE, *IntArray(1 shl l.bps) { linear(it) })
            stored
        } else stored
        val (blobs, tw, th) = tileBlobs(fileValues, l)
        if (l.tile != null) {
            raw.long(TiffTags.TILE_WIDTH, tw).long(TiffTags.TILE_LENGTH, th).blobs(TiffTags.TILE_OFFSETS, TiffTags.TILE_BYTE_COUNTS, blobs)
        } else {
            raw.long(TiffTags.ROWS_PER_STRIP, th).blobs(TiffTags.STRIP_OFFSETS, TiffTags.STRIP_BYTE_COUNTS, blobs)
        }
        val ifd0 = if (l.subIfd) {
            TiffBuilder.Ifd().long(TiffTags.NEW_SUBFILE_TYPE, 1).long(TiffTags.IMAGE_WIDTH, 8).long(TiffTags.IMAGE_LENGTH, 8)
                .short(TiffTags.PHOTOMETRIC, 2).subIfds(TiffTags.SUB_IFDS, raw)
        } else raw
        ifd0.byte(TiffTags.DNG_VERSION, 1, 4, 0, 0).ascii(TiffTags.MAKE, "Synth").ascii(TiffTags.MODEL, "Cam ${l.bps}")
            .srational(TiffTags.COLOR_MATRIX_1, *colorMatrix).short(TiffTags.CALIBRATION_ILLUMINANT_1, 21)
            .rational(TiffTags.AS_SHOT_NEUTRAL, *Mat3.mulVec(colorMatrix, RawColor.D65).let { n -> DoubleArray(3) { n[it] / n.max() } })
            .short(TiffTags.ORIENTATION, 1)
        return TiffBuilder(l.le).build(ifd0)
    }

    private fun maxSceneError(img: LinearImage, offX: Int, offY: Int): Double {
        var worst = 0.0
        for (y in 3 until img.height - 3) for (x in 3 until img.width - 3) {
            val t = scene(x + offX, y + offY)
            worst = max(worst, max(abs(img.red(x, y) - t[0]), max(abs(img.green(x, y) - t[1]), abs(img.blue(x, y) - t[2]))))
        }
        return worst
    }

    private val layouts = listOf(
        Layout(16, 1, rowsPerStrip = 10),
        Layout(16, 1, rowsPerStrip = 7, le = false),
        Layout(16, 1, tile = 32 to 32),
        Layout(16, 1, tile = 40 to 24, le = false),
        Layout(10, 1, rowsPerStrip = 64),
        Layout(12, 1, rowsPerStrip = 5),
        Layout(14, 1, rowsPerStrip = 64, le = false),
        Layout(12, 1, tile = 40 to 40),
        Layout(8, 1, rowsPerStrip = 16, linearize = true),
        Layout(12, 1, rowsPerStrip = 64, linearize = true),
        Layout(16, 7, tile = 32 to 32, components = 2),
        Layout(14, 7, tile = 48 to 40, components = 2, predictor = 6),
        Layout(12, 7, tile = 64 to 16, components = 1, predictor = 7, restartRows = 1),
        Layout(14, 7, tile = 32 to 32, components = 4, predictor = 4, restartRows = 4, le = false),
        Layout(16, 7, rowsPerStrip = 16, components = 2),
        Layout(12, 7, rowsPerStrip = 64, components = 2, linearize = true),
        Layout(16, 1, rowsPerStrip = 64, subIfd = true),
        Layout(16, 7, tile = 32 to 32, subIfd = true, le = false),
    )

    @Test
    fun everyLayoutDecodesToTheSameSamples() {
        for (l in layouts) {
            val storedBits = l.bps
            val white = if (l.linearize) linear((1 shl storedBits) - 1) else (1 shl storedBits) - 1
            val stored = sensor(storedBits, CfaPattern.RGGB, intArrayOf(0), 1, (1 shl storedBits) - 1, noise = if (storedBits >= 12) 2 else 0)
            val bytes = buildDng(stored, l, white = white, black = intArrayOf(if (l.linearize) 3 else 0))
            val img = assertNotNull(DngReader.read(bytes), "$l")
            val expected = IntArray(w * h) { if (l.linearize) linear(stored[it]) else stored[it] }
            assertContentEquals(expected, IntArray(w * h) { img.data[it].toInt() and 0xffff }, "$l")
            assertEquals(white.toFloat(), img.white)
            assertEquals("Synth", img.make)
            assertNotNull(RawReader.read(bytes))
            // streamed rows: same samples, for every strip layout; tiles and lossless JPEG are read whole
            val streamed = DngReader.open(ByteArraySource(bytes))
            if (l.tile == null && l.compression == 1) {
                val rows = assertNotNull(streamed?.rows, "$l")
                val got = ShortArray(w * h)
                for (y0 in 0 until h step 9) {
                    val y1 = minOf(h, y0 + 9)
                    val band = ShortArray((y1 - y0) * w)
                    rows.read(y0, y1, band)
                    band.copyInto(got, y0 * w)
                }
                assertContentEquals(img.data, got, "$l")
                assertEquals(0, streamed.data.size)
            } else assertNull(streamed, "$l")
            // and it develops to the scene
            val e = maxSceneError(RawDeveloper.develop(img, 4096), 0, 0)
            assertTrue(e < if (storedBits <= 10) 0.03 else 0.012, "$l error $e")
        }
    }

    @Test
    fun blackPatternActiveAreaAndCrop() {
        // 1 masked row and column: the CFA (RGGB) and the 2x2 black pattern start at the active area
        val black = intArrayOf(60, 70, 80, 90)
        val white = 16000
        val stored = sensor(14, CfaPattern.RGGB, black, 2, white, activeTop = 1, activeLeft = 1)
        for (l in listOf(Layout(14, 7, tile = 32 to 32), Layout(16, 1))) {
            val img = assertNotNull(DngReader.read(buildDng(stored, l, CfaPattern.RGGB, black, 2, white,
                active = intArrayOf(1, 1, h, w), cropOrigin = intArrayOf(1, 1), cropSize = intArrayOf(w - 6, h - 6))))
            // anchored at raw (0, 0) the pattern is BGGR and the black pattern shifts with it
            assertContentEquals(CfaPattern.BGGR.colors, img.cfa.colors)
            assertEquals(60f, img.blackAt(1, 1)); assertEquals(70f, img.blackAt(1, 2)); assertEquals(80f, img.blackAt(2, 1)); assertEquals(90f, img.blackAt(2, 2))
            assertEquals(2, img.cropX); assertEquals(2, img.cropY)
            assertEquals(w - 6, img.cropWidth)
            val e = maxSceneError(RawDeveloper.develop(img, 4096), img.cropX, img.cropY)
            println("black pattern + active area ($l): max error %.4f".format(e))
            assertTrue(e < 0.01, "error $e")
        }
    }

    @Test
    fun xTransDngIsReadAndDeveloped() {
        val cfa = CfaPattern.parse(6, 6, RawColorTest.XTRANS)
        val stored = sensor(14, cfa, intArrayOf(256), 1, 16383)
        val img = assertNotNull(DngReader.read(buildDng(stored, Layout(14, 7, tile = 48 to 32), cfa, intArrayOf(256), 1, 16383)))
        assertEquals(6, img.cfa.width); assertEquals(6, img.cfa.height)
        assertContentEquals(cfa.colors, img.cfa.colors)
        assertEquals(true, RawDeveloper.supports(img))
    }

    @Test
    fun unsupportedVariantsReturnNull() {
        val stored = sensor(16, CfaPattern.RGGB, intArrayOf(0), 1, 65535)
        assertNull(DngReader.read(buildDng(stored, Layout(), white = 65535, photometric = 34892)))          // LinearRaw
        assertNull(DngReader.read(buildDng(stored, Layout(), white = 65535, sampleFormat = 3)))             // half-float mosaic
        assertNotNull(DngReader.read(buildDng(stored, Layout(), white = 65535, sampleFormat = 1)))
        val dng = buildDng(stored, Layout(), white = 65535)
        // not a DNG without DNGVersion: patch the tag number
        val t = Tiff.parse(dng)!!
        val noVersion = dng.copyOf().also { b ->
            val n = Bytes.u16le(b, t.ifd0.offset)
            for (i in 0 until n) { val p = t.ifd0.offset + 2 + i * 12; if (Bytes.u16le(b, p) == TiffTags.DNG_VERSION) { b[p] = 0x13; b[p + 1] = 0x01 } }
        }
        assertNull(DngReader.read(noVersion))
        for (compression in intArrayOf(8, 34892, 52546)) {
            val patched = dng.copyOf().also { b ->
                val n = Bytes.u16le(b, t.ifd0.offset)
                for (i in 0 until n) { val p = t.ifd0.offset + 2 + i * 12; if (Bytes.u16le(b, p) == TiffTags.COMPRESSION) { b[p + 8] = compression.toByte(); b[p + 9] = (compression shr 8).toByte() } }
            }
            assertNull(DngReader.read(patched), "compression $compression")
        }
        assertNull(RawReader.read(ByteArray(1000)))
        assertNull(RawReader.read(ByteArray(0)))
    }

    @Test
    fun corruptFilesNeverThrow() {
        val stored = sensor(14, CfaPattern.RGGB, intArrayOf(64), 1, 16383, noise = 3)
        val good = buildDng(stored, Layout(14, 7, tile = 32 to 32), white = 16383)
        val rnd = Random(21)
        var decoded = 0
        repeat(400) {
            val bad = good.copyOf(if (rnd.nextInt(4) == 0) rnd.nextInt(good.size) else good.size)
            repeat(1 + rnd.nextInt(8)) { if (bad.isNotEmpty()) bad[rnd.nextInt(minOf(bad.size, if (rnd.nextBoolean()) 1024 else bad.size))] = rnd.nextInt(256).toByte() }
            if (RawReader.read(bad) != null) decoded++
        }
        println("corrupt DNGs still decoded: $decoded / 400")
    }

    /** Writes compressed DNGs + their decoded samples for tools/rawcheck.py (LibRaw cross-check). */
    @Test
    fun dumpForLibRaw() {
        val dir = System.getProperty("spektra.rawdump")?.takeIf { it.isNotEmpty() } ?: return
        File(dir).mkdirs()
        val cases = mapOf(
            "ljpeg_tiles" to Layout(14, 7, tile = 32 to 32, components = 2),
            // (not 48-wide tiles on this 96-wide frame: LibRaw's `clrs * jwide == raw_width` heuristic misreads those)
            "ljpeg_tiles_partial_p6" to Layout(16, 7, tile = 64 to 48, components = 2, predictor = 6, le = false),
            "ljpeg_tiles_4c_p7" to Layout(14, 7, tile = 32 to 32, components = 4, predictor = 7),
            "ljpeg_restart" to Layout(12, 7, tile = 64 to 16, components = 1, predictor = 1, restartRows = 1),
            "packed12_tiles" to Layout(12, 1, tile = 40 to 40),
            "packed10_lin" to Layout(10, 1, rowsPerStrip = 64, linearize = true),
        )
        for ((name, l) in cases) {
            val white = if (l.linearize) linear((1 shl l.bps) - 1) else (1 shl l.bps) - 1
            val stored = sensor(l.bps, CfaPattern.GRBG, intArrayOf(32), 1, (1 shl l.bps) - 1, noise = 3)
            val bytes = buildDng(stored, l, CfaPattern.GRBG, intArrayOf(if (l.linearize) linear(32) else 32), 1, white)
            File(dir, "$name.dng").writeBytes(bytes)
            val img = DngReader.read(bytes)!!
            File(dir, "$name.raw16").writeBytes(ByteBuffer.allocate(img.data.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { img.data.forEach { putShort(it) } }.array())
            val dev = RawDeveloper.develop(img, 4096)
            File(dir, "$name.txt").printWriter().use { p ->
                p.println("${dev.width} ${dev.height}")
                for (y in 0 until dev.height) for (x in 0 until dev.width) p.println("${dev.red(x, y)} ${dev.green(x, y)} ${dev.blue(x, y)}")
            }
        }
    }
}
