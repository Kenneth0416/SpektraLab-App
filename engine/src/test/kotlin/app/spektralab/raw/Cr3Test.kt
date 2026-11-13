package app.spektralab.raw

import java.io.File
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Synthetic CR3 files from [CrxTestEncoder] / [Cr3TestBuilder].
 *
 * Lossless frames must round-trip exactly. For the C-RAW (wavelet) and encoding-type-3
 * frames there is no reference encoder: the expected CRC32 of the mosaic is what
 * LibRaw 0.22.2 (rawpy 0.28) decodes from the very same bytes (set
 * `SPEKTRA_CR3_SYNTH_OUT=<dir>` to write the files, then run
 * `tools/cr3_reference.py` on the files). The lossless ones were checked the same way.
 */
class Cr3Test {
    private fun crc(data: ShortArray): Long {
        val b = ByteArray(data.size * 2)
        for (i in data.indices) { b[2 * i] = data[i].toByte(); b[2 * i + 1] = (data[i].toInt() shr 8).toByte() }
        return CRC32().apply { update(b) }.value
    }

    private fun dump(name: String, bytes: ByteArray) {
        val dir = System.getenv("SPEKTRA_CR3_SYNTH_OUT") ?: return
        File(dir).mkdirs()
        File(dir, "$name.CR3").writeBytes(bytes)
    }

    /** Position of plane p inside the 2x2 cell for a CMP1 cfa layout (row, col). */
    private fun planePos(layout: Int, p: Int): Pair<Int, Int> {
        val order = when (layout) {           // plane at (0,0), (0,1), (1,0), (1,1)
            0 -> intArrayOf(0, 1, 2, 3); 1 -> intArrayOf(1, 0, 3, 2); 2 -> intArrayOf(2, 3, 0, 1); else -> intArrayOf(3, 2, 1, 0)
        }
        val pos = order.indexOf(p)
        return (pos / 2) to (pos % 2)
    }

    /** Lossless spec whose planes are cut from [raw] (rows of the full frame). */
    private fun losslessSpec(version: Int, raw: Array<IntArray>, tileW: Int, tileH: Int, nBits: Int, layout: Int, partial: BooleanArray): CrxTestEncoder.Spec {
        val h = raw.size; val w = raw[0].size
        lateinit var tiles: List<CrxTestEncoder.Tile>
        val spec = CrxTestEncoder.Spec(version, w, h, tileW, tileH, nBits, layout, 0, 0, planePartial = partial) { t, p, _, row, width ->
            val cols = (w / 2 + tileW / 2 - 1) / (tileW / 2)
            val x0 = (t % cols) * (tileW / 2); val y0 = (t / cols) * (tileH / 2)
            val (dy, dx) = planePos(layout, p)
            IntArray(width) { raw[2 * (y0 + row) + dy][2 * (x0 + it) + dx] - (1 shl (nBits - 1)) }
        }
        tiles = CrxTestEncoder.tiles(spec)
        return spec
    }

    private fun image(seed: Int, w: Int, h: Int, max: Int): Array<IntArray> = Cr3TestData.plane(seed, w, h, max)

    @Test
    fun losslessSingleTileRoundTrip() {
        val raw = image(1, 202, 122, 16383)
        val spec = losslessSpec(0x100, raw, 202, 122, 14, 0, booleanArrayOf(true, true, true, true))
        val meta = Cr3TestBuilder.Meta(orientation = 6, area = intArrayOf(5, 3, 199, 120))
        val bytes = Cr3TestBuilder.file(spec, meta)
        dump("lossless_v100_1tile", bytes)
        val img = assertNotNull(RawFormatCheck.read("CR3", bytes))
        assertEquals(202, img.rawWidth); assertEquals(122, img.rawHeight)
        assertContentEquals(raw.flatMap { it.toList() }.map { it.toShort() }.toShortArray(), img.data)
        assertEquals(0xd019fad9L, crc(img.data))                       // LibRaw raw_image
        assertEquals(CfaPattern.RGGB.colors.toList(), img.cfa.colors.toList())
        assertEquals(listOf(512f, 512f, 512f, 512f), img.black.toList())
        assertEquals(14000f, img.white)
        // SensorInfo 5, 3 .. 199, 120 (inclusive, LibRaw raw_inset_crops) with the odd origin moved to 6, 4
        // (LibRaw's visible area would be 6, 4, 193 x 116)
        assertEquals(listOf(6, 4, 194, 117), listOf(img.cropX, img.cropY, img.cropWidth, img.cropHeight))
        assertEquals(6, img.orientation)
        assertEquals(2000f / 1024, img.wb[0], 1e-5f)
        assertEquals(1500f / 1024, img.wb[2], 1e-5f)
        assertEquals("Canon EOS R6", img.model)
    }

    @Test
    fun losslessTiledNonPartialRoundTrip() {
        // 2 x 2 tiles of an odd-sized plane, GRBG, 12 bit; planes 1 and 3 use the no-reference line coder
        val raw = image(2, 2 * 59, 2 * 49, 4095)
        val spec = losslessSpec(0x100, raw, 64, 52, 12, 1, booleanArrayOf(true, false, true, false))
        val bytes = Cr3TestBuilder.file(spec)
        dump("lossless_v100_4tiles", bytes)
        val img = assertNotNull(RawFormatCheck.read("CR3", bytes))
        assertContentEquals(raw.flatMap { it.toList() }.map { it.toShort() }.toShortArray(), img.data)
        assertEquals(0xe418249bL, crc(img.data))                       // LibRaw raw_image
        assertEquals(CfaPattern.GRBG.colors.toList(), img.cfa.colors.toList())
        assertEquals(listOf(0, 0, 118, 98), listOf(img.cropX, img.cropY, img.cropWidth, img.cropHeight))   // no sensor info: full frame
        assertEquals(3500f, img.white)                                  // LibRaw scales the 14-bit SpecularWhiteLevel (14000) to 12 bit
    }

    @Test
    fun losslessHeaderV200RoundTrip() {
        val raw = image(3, 2 * 61, 2 * 30, 16383)
        val spec = losslessSpec(0x200, raw, 64, 60, 14, 3, booleanArrayOf(false, true, false, true))
        val bytes = Cr3TestBuilder.file(spec, Cr3TestBuilder.Meta(colourInCtmd = true, wb = intArrayOf(1900, 1030, 1018, 1600)))
        dump("lossless_v200_2tiles_bggr", bytes)
        val img = assertNotNull(RawFormatCheck.read("CR3", bytes))
        assertContentEquals(raw.flatMap { it.toList() }.map { it.toShort() }.toShortArray(), img.data)
        assertEquals(0xb161d167L, crc(img.data))                       // LibRaw raw_image
        assertEquals(CfaPattern.BGGR.colors.toList(), img.cfa.colors.toList())
        // colour data from the CTMD track
        assertEquals(1900f / 1030, img.wb[0], 1e-5f)
        assertEquals(1600f / 1030, img.wb[2], 1e-5f)
    }

    private fun cRawSpec(version: Int, w: Int, h: Int, tw: Int, th: Int, levels: Int, layout: Int, seed: Int,
                         extra: Int = 0, enc: Int = 0, median: Int = 14): CrxTestEncoder.Spec =
        CrxTestEncoder.Spec(version, w, h, tw, th, 14, layout, enc, levels, medianBits = median,
            planePartial = booleanArrayOf(true, false, true, true),
            bandQUpdate = { it == 0 || it == 2 },
            bandQParam = { b -> if (b == 0) 4 else 8 + b },
            bandEmpty = { t, p, b -> b > 0 && (t + p + b) % 7 == 0 },
            qStepBase = 2, qStepMult = 12,
            qp = { t, r, c -> (t + r * 3 + c * 5) % 9 },
            extraSize = extra) { t, p, b, row, width -> Cr3TestData.coeffs(seed * 31 + t * 4 + p, b, row, width) }

    private fun checkAgainstLibRaw(name: String, spec: CrxTestEncoder.Spec, expectedCrc: Long) {
        val bytes = Cr3TestBuilder.file(spec)
        dump(name, bytes)
        val img = assertNotNull(RawFormatCheck.read("CR3", bytes), name)
        System.getenv("SPEKTRA_CR3_SYNTH_OUT")?.let { dir ->
            val b = ByteArray(img.data.size * 2)
            for (i in img.data.indices) { b[2 * i] = img.data[i].toByte(); b[2 * i + 1] = (img.data[i].toInt() shr 8).toByte() }
            File(dir, "$name.mine").writeBytes(b)
        }
        assertEquals(spec.width, img.rawWidth)
        assertEquals(expectedCrc, crc(img.data), "$name: mosaic differs from LibRaw (crc %08x)".format(crc(img.data)))
    }

    @Test
    fun cRawV100Tiles2x2Levels3() = checkAgainstLibRaw("craw_v100_4tiles_l3", cRawSpec(0x100, 170, 140, 96, 80, 3, 0, 1), 0x7e83133aL)

    @Test
    fun cRawV200Tiles2x1Levels2() = checkAgainstLibRaw("craw_v200_2tiles_l2", cRawSpec(0x200, 180, 100, 92, 100, 2, 2, 2, extra = 4), 0x9f1b15b5L)

    @Test
    fun cRawV200SingleTileLevels1() = checkAgainstLibRaw("craw_v200_1tile_l1", cRawSpec(0x200, 154, 90, 154, 90, 1, 1, 3), 0xa619e376L)

    /**
     * Header v0x200 with more than one tile row: LibRaw's crxDecodeLineWithIQuantization then indexes
     * its QP step table with a negative row (getSubbandRow subtracts rowEndAddOn), i.e. reads heap
     * memory before the table, so there is no defined result to match: null.
     */
    @Test
    fun cRawV200MultipleTileRowsIsNull() {
        val bytes = Cr3TestBuilder.file(cRawSpec(0x200, 2 * 70, 2 * 66, 2 * 38, 2 * 34, 3, 0, 4))
        dump("craw_v200_4tiles_l3", bytes)
        assertNull(Cr3Reader.read(bytes))
    }

    @Test
    fun encodingType3Lossless() = checkAgainstLibRaw("enc3_v200_lossless",
        cRawSpec(0x200, 2 * 60, 2 * 40, 2 * 60, 2 * 40, 0, 0, 5, enc = 3, median = 13), 0xccc79a71L)

    @Test
    fun encodingType3CRaw() = checkAgainstLibRaw("enc3_v200_l3",
        cRawSpec(0x200, 2 * 72, 2 * 48, 2 * 72, 2 * 48, 3, 0, 6, enc = 3, median = 14), 0x52fcc13aL)

    /** Lossless with the comp header's rounded-bits field, tiled; planes 1 and 3 without it. */
    private fun roundedSpec(rb: Int) = CrxTestEncoder.Spec(0x200, 2 * 70, 2 * 40, 2 * 36, 2 * 40, 14, 0, 0, 0,
        planePartial = booleanArrayOf(true, false, true, false), roundedBits = rb) { t, p, _, row, width ->
        Cr3TestData.coeffs(t * 4 + p + 11, 1, row, width) }

    @Test
    fun losslessRoundedBits1() = checkAgainstLibRaw("lossless_rounded1", roundedSpec(1), 0xb6c0fd74L)

    @Test
    fun losslessRoundedBits3() = checkAgainstLibRaw("lossless_rounded3", roundedSpec(3), 0xb8ca11b1L)

    /** Full-size frame whose plane pixels come from [px] (raw x, y), one tile column per [tileW] raw pixels. */
    private fun functionSpec(w: Int, h: Int, tileW: Int, layout: Int, px: (Int, Int) -> Int) =
        CrxTestEncoder.Spec(0x100, w, h, tileW, h, 14, layout, 0, 0) { t, p, _, row, width ->
            val x0 = t * (tileW / 2)
            val (dy, dx) = planePos(layout, p)
            IntArray(width) { px(2 * (x0 + it) + dx, 2 * row + dy) - 8192 }
        }

    /** Optical-black columns at ~2048 with a per-colour offset, a gradient elsewhere. */
    private fun obImage(left: Int, right: Int, w: Int): (Int, Int) -> Int = { x, y ->
        val ob = 2048 + 3 * ((y and 1) * 2 + (x and 1)) + ((x * 7 + y * 13) % 9) - 4
        if (x < left || x >= w - right) ob else 2048 + ((x shr 3) + (y shr 2)) % 3000 + (x * y) % 5
    }

    /**
     * Raw sizes in LibRaw's Canon table: margins from the table, black measured on the optical
     * black (the colour data says 512, LibRaw ignores it), CFA from the table's filters byte.
     */
    @Test
    fun canonTableSizeEosRp() {
        val w = 4032; val h = 2656
        val px = obImage(112, 0, w)
        val bytes = Cr3TestBuilder.file(functionSpec(w, h, 2016, 0, px), Cr3TestBuilder.Meta(model = "Canon EOS RP"))
        dump("table_4032x2656", bytes)
        val img = assertNotNull(RawFormatCheck.read("CR3", bytes))
        for (y in listOf(0, 1, 1000, h - 1)) for (x in listOf(0, 1, 111, 112, 2015, 2016, w - 1)) assertEquals(px(x, y), img.data[y * w + x].toInt())
        assertEquals(0xdc906ca5L, crc(img.data))                       // LibRaw raw_image
        assertEquals(listOf(112, 44, 3910, 2612), listOf(img.cropX, img.cropY, img.cropWidth, img.cropHeight))
        // LibRaw black_level_per_channel R, G, B, G2 = 2106, 2108, 2114, 2111 (the right mask strip,
        // columns 4024.., lies in image data here, hence above the ~2050 optical black)
        assertEquals(listOf(2106f, 2108f, 2111f, 2114f), img.black.toList())
        assertEquals(CfaPattern.RGGB.colors.toList(), img.cfa.colors.toList())
    }

    @Test
    fun canonTableSizeWithMasksAndFilters() {
        // PowerShot SX50 HS size: filters 0x49 (GBRG relative to the visible origin, row 17), mask03 -16,
        // mask13 -7; LibRaw then moves the odd top margin to 18
        val w = 4176; val h = 3062
        val px = obImage(96, 8, w)
        val bytes = Cr3TestBuilder.file(functionSpec(w, h, w, 0, px), Cr3TestBuilder.Meta(model = "Canon PowerShot SX50 HS"))
        dump("table_4176x3062", bytes)
        val img = assertNotNull(RawFormatCheck.read("CR3", bytes))
        assertEquals(0xfe12d771L, crc(img.data))                       // LibRaw raw_image
        assertEquals(CfaPattern.RGGB.colors.toList(), img.cfa.colors.toList())   // LibRaw raw_pattern at row 18: RGGB
        assertEquals(listOf(96, 18, 4072, 3044), listOf(img.cropX, img.cropY, img.cropWidth, img.cropHeight))
        // LibRaw black_level_per_channel R, G, B, G2 = 2047, 2051, 2057, 2053
        assertEquals(listOf(2047f, 2051f, 2053f, 2057f), img.black.toList())
    }

    // ------------------------------------------------------------------ robustness
    @Test
    fun rejectsOtherFilesFast() {
        assertNull(Cr3Reader.read(ByteArray(0)))
        assertNull(Cr3Reader.read(ByteArray(100)))
        assertNull(Cr3Reader.read(TiffBuilder().build(TiffBuilder.Ifd().ascii(TiffTags.MAKE, "Canon"))))
        // an ISO-BMFF of another brand
        assertNull(Cr3Reader.read(Cr3TestBuilder.box("ftyp", "heic".toByteArray(), ByteArray(4)) + ByteArray(64)))
    }

    @Test
    fun unknownCameraOrMissingColourDataIsNull() {
        val raw = image(7, 64, 48, 16383)
        val spec = losslessSpec(0x100, raw, 64, 48, 14, 0, booleanArrayOf(true, true, true, true))
        assertNull(Cr3Reader.read(Cr3TestBuilder.file(spec, Cr3TestBuilder.Meta(model = "Canon EOS Imaginary"))))
        assertNotNull(RawFormatCheck.read("CR3", Cr3TestBuilder.file(spec)))
    }

    @Test
    fun corruptOrTruncatedInputNeverThrows() {
        val spec = cRawSpec(0x100, 170, 140, 96, 80, 3, 0, 1)
        val good = Cr3TestBuilder.file(spec)
        assertNotNull(RawFormatCheck.read("CR3", good))
        val r = kotlin.random.Random(42)
        for (cut in listOf(16, 100, good.size / 3, good.size / 2, good.size - 200, good.size - 1))
            assertNull(Cr3Reader.read(good.copyOf(cut)), "truncated to $cut")
        repeat(300) {
            val bad = good.copyOf()
            repeat(1 + r.nextInt(8)) { bad[r.nextInt(bad.size)] = r.nextInt(256).toByte() }
            Cr3Reader.read(bad)                                      // must return (null or an image), not throw
        }
        // header fields pushed to extremes
        val info = assertNotNull(Cr3Reader.scan(good))
        assertTrue(info.tracks.any { it.isRaw })
    }

    @Test
    fun stco32BitChunkOffsetIsNotLeftAtZero() {
        val raw = image(3, 64, 48, 1000)
        val spec = losslessSpec(0x100, raw, 64, 48, 14, 0, booleanArrayOf(true, true, true, true))
        val bytes = Cr3TestBuilder.file(spec, useStco = true)
        val track = assertNotNull(Cr3Reader.scan(bytes)).tracks.first { it.isRaw }
        assertTrue(track.chunkOffset > 0L, "stco offset was ${track.chunkOffset}")
        val img = assertNotNull(RawFormatCheck.read("CR3", bytes))
        val ref = assertNotNull(Cr3Reader.read(Cr3TestBuilder.file(spec)))
        assertContentEquals(ref.data, img.data)
    }

    @Test
    fun bandSizesMatchLibRawLayout() {
        // single tile, 3 levels: band (w, h) from crxProcessSubbands
        val s = Crx.bandSizes(0x200, 3, 85, 70, 0)
        assertEquals(listOf(11, 9), s[0].toList())                  // LL3
        assertEquals(10, s.size)
        assertEquals(listOf(42, 35), s[9].toList())                 // finest level: 85 is odd
        assertEquals(listOf(43, 35), s[8].toList())
        assertEquals(listOf(42, 35), s[7].toList())
    }
}
