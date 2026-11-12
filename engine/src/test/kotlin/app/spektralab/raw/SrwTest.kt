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

/** MSB-first bit sink that knows how many bits it holds. */
internal class BitSink {
    private var buf = ByteArray(1 shl 16)
    var bits = 0L; private set
    fun put(value: Int, n: Int) {
        for (k in n - 1 downTo 0) {
            val byte = (bits shr 3).toInt()
            if (byte >= buf.size) buf = buf.copyOf(buf.size * 2)
            if ((value ushr k) and 1 == 1) buf[byte] = (buf[byte].toInt() or (0x80 shr (bits and 7).toInt())).toByte()
            bits++
        }
    }
    fun bytes(): ByteArray = buf.copyOf(((bits + 7) shr 3).toInt())
    /** Whole bytes, padded with zero bits to a multiple of [multiple] bytes. */
    fun padded(multiple: Int): ByteArray {
        val n = ((bits + 7) shr 3).toInt()
        return buf.copyOf((n + multiple - 1) / multiple * multiple)
    }
}

/** Synthetic Samsung SRW files: TIFF container, Samsung maker note and the four pixel layouts. */
internal object SrwSynth {
    class Spec(
        val name: String, val model: String, val compression: Int, val bits: Int, val width: Int, val height: Int, val seed: Long,
        val rowTable: Boolean = false, val cfa: IntArray = intArrayOf(1, 0, 2, 1),
        val black: IntArray = intArrayOf(0, 0, 0, 0), val levels: IntArray = intArrayOf(2500, 1024, 1024, 1900),
        val keys: IntArray = intArrayOf(305, 72, 737, 456, 282, 307, 519, 724, 13, 505, 193),
        val optFlags: Int = 0, val max: Int = 0, val orientation: Int = 1, val note: Boolean = true,
    )

    class Built(val file: ByteArray, val mosaic: ShortArray)

    private val SYMBOL_TABLE = intArrayOf(0x304, 0x307, 0x206, 0x205, 0x403, 0x600, 0x709, 0x80a, 0x90b, 0xa0c, 0xa0d, 0x501, 0x408, 0x402)

    fun build(s: Spec, dataOnly: ByteArray? = null): Built {
        val rng = Random(s.seed)
        val max = if (s.max != 0) s.max else (1 shl s.bits) - 1
        val scene = scene(s.width, s.height, max, rng)
        val rowOffsets = ArrayList<Int>()
        val (stream, mosaic) = when {
            s.compression == 32773 -> encodeV3(s, scene, rng)
            s.compression == 32772 -> encodeV2(s, scene) to ShortArray(scene.size) { scene[it].toShort() }
            s.compression == 32770 && s.rowTable -> encodeV1(s, scene, rng, rowOffsets) to ShortArray(scene.size) { scene[it].toShort() }
            else -> ByteArray(scene.size * 2) { i -> (scene[i / 2] shr (8 * (i % 2))).toByte() } to ShortArray(scene.size) { scene[it].toShort() }
        }
        return Built(container(s, dataOnly ?: stream, rowOffsets), mosaic)
    }

    fun container(s: Spec, data: ByteArray, rowOffsets: List<Int> = emptyList()): ByteArray {
        val ifd = TiffBuilder.Ifd()
        ifd.short(TiffTags.IMAGE_WIDTH, s.width).short(TiffTags.IMAGE_LENGTH, s.height).short(TiffTags.BITS_PER_SAMPLE, s.bits)
        ifd.short(TiffTags.COMPRESSION, s.compression).short(TiffTags.PHOTOMETRIC, 32803)
        ifd.ascii(TiffTags.MAKE, "SAMSUNG").ascii(TiffTags.MODEL, s.model)
        ifd.short(TiffTags.ORIENTATION, s.orientation).short(TiffTags.SAMPLES_PER_PIXEL, 1).short(TiffTags.ROWS_PER_STRIP, s.height)
        ifd.short(TiffTags.CFA_REPEAT_PATTERN_DIM, 2, 2).byte(TiffTags.CFA_PATTERN, *s.cfa)
        ifd.blobs(TiffTags.STRIP_OFFSETS, TiffTags.STRIP_BYTE_COUNTS, listOf(data))
        if (s.rowTable || s.compression == 32772 || s.compression == 32773) {
            // LibRaw only selects the SRW readers when tag 0xa010 exists; for SRW2 / SRW3 it holds nothing the decoder uses
            val table = if (rowOffsets.isEmpty()) ByteArray(8) else ByteArray(rowOffsets.size * 4)
            for ((i, o) in rowOffsets.withIndex()) for (k in 0 until 4) table[i * 4 + k] = (o shr (8 * k)).toByte()
            ifd.blobs(0xa010, 0xa0fe, listOf(table))
        }
        if (s.note) {
            val noteIfd = TiffBuilder.Ifd()
            noteIfd.long(0xa020, *s.keys)
            noteIfd.long(0xa021, *IntArray(4) { s.levels[it] + s.keys[it] })
            noteIfd.long(0xa028, *IntArray(4) { s.black[it] + s.keys[it] })
            val noteBytes = TiffBuilder(true).build(noteIfd, ByteArray(0))
            val exif = TiffBuilder.Ifd().undefined(TiffTags.MAKER_NOTE, noteBytes)
            ifd.subIfds(TiffTags.EXIF_IFD, exif)
        }
        return TiffBuilder(true).build(ifd)
    }

    /** Rewrites the maker note's value offsets from note-relative to file-absolute. */
    fun absolutizeNote(file: ByteArray): ByteArray {
        val out = file.copyOf()
        val tiff = Tiff.parse(out, 0, setOf(42))!!
        val e = tiff.exif!![TiffTags.MAKER_NOTE]!!
        val start = e.dataOffset
        val n = Bytes.u16le(out, start)
        for (i in 0 until n) {
            val p = start + 2 + 12 * i
            val size = TiffIfd.typeSize(Bytes.u16le(out, p + 2)) * Bytes.u32le(out, p + 4).toInt()
            if (size > 4) {
                val abs = Bytes.u32le(out, p + 8).toInt() + start
                for (k in 0 until 4) out[p + 8 + k] = (abs shr (8 * k)).toByte()
            }
        }
        return out
    }

    /** A smooth noisy scene whose neighbouring differences stay small. */
    fun scene(w: Int, h: Int, max: Int, rng: Random): IntArray = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        val base = (0.1 + 0.5 * (x.toDouble() / w) + 0.2 * (y.toDouble() / h) + 0.05 * (if ((x + y) % 2 == 0) 1 else 0)) * max
        (base + rng.nextGaussian() * max * 0.004).toInt().coerceIn(16, max)
    }

    private fun bitLength(v: Int) = 32 - Integer.numberOfLeadingZeros(v)
    /** Smallest k with -2^(k-1) <= d <= 2^(k-1) - 1 (0 for d == 0). */
    private fun signedBits(d: Int): Int = if (d == 0) 0 else if (d > 0) bitLength(d) + 1 else bitLength(-d - 1) + 1

    private fun le32(sink: BitSink, pad: Int): ByteArray {
        val b = sink.padded(pad)
        // MSB-first stream -> 32-bit words stored little endian
        for (i in 0 until b.size / 4) { val a = b[4 * i]; val c = b[4 * i + 1]; b[4 * i] = b[4 * i + 3]; b[4 * i + 1] = b[4 * i + 2]; b[4 * i + 2] = c; b[4 * i + 3] = a }
        return b
    }

    // -------------------------------------------------------------- SRW1
    fun encodeV1(s: Spec, f: IntArray, rng: Random, rowOffsets: MutableList<Int>): ByteArray {
        val w = s.width; val h = s.height
        // the stream carries red and blue swapped
        val x = f.copyOf()
        var r0 = 0
        while (r0 < h - 1) { var c0 = 0; while (c0 < w - 1) { val a = r0 * w + c0 + 1; val d = (r0 + 1) * w + c0; val t = x[a]; x[a] = x[d]; x[d] = t; c0 += 2 }; r0 += 2 }
        val out = java.io.ByteArrayOutputStream()
        val order = intArrayOf(0, 2, 4, 6, 8, 10, 12, 14, 1, 3, 5, 7, 9, 11, 13, 15)
        for (row in 0 until h) {
            rowOffsets += out.size()
            val sink = BitSink()
            val len = IntArray(4) { if (row < 2) 7 else 4 }
            var col = 0
            while (col < w) {
                val dir = row >= 2 && rng.nextInt(3) == 0
                val diffs = IntArray(16)
                for (k in 0 until 16) {
                    val c = order[k]
                    val pred = when {
                        dir -> x[(if (c and 1 == 0) row - 1 else row - 2) * w + col + c]
                        col != 0 -> x[row * w + col - (if (c and 1 == 0) 2 else 1)]
                        else -> 128
                    }
                    diffs[k] = x[row * w + col + c] - pred
                }
                val need = IntArray(4)
                for (k in 0 until 16) { val c = order[k]; val cls = ((c and 1) shl 1) or (c shr 3); need[cls] = maxOf(need[cls], signedBits(diffs[k])) }
                val ops = IntArray(4); val target = IntArray(4)
                for (cls in 0 until 4) {
                    target[cls] = (need[cls] + (if (rng.nextInt(4) == 0) 1 else 0)).coerceAtMost(15)
                    ops[cls] = when (target[cls]) { len[cls] -> 0; len[cls] + 1 -> 1; len[cls] - 1 -> 2; else -> 3 }
                }
                sink.put(if (dir) 1 else 0, 1)
                for (cls in 0 until 4) sink.put(ops[cls], 2)
                for (cls in 0 until 4) { if (ops[cls] == 3) sink.put(target[cls], 4); len[cls] = target[cls] }
                for (k in 0 until 16) {
                    val c = order[k]; val i = len[((c and 1) shl 1) or (c shr 3)]
                    if (i > 0) sink.put(diffs[k] and ((1 shl i) - 1), i)
                }
                col += 16
            }
            out.write(le32(sink, 4))
        }
        return out.toByteArray()
    }

    // -------------------------------------------------------------- SRW2
    fun encodeV2(s: Spec, f: IntArray): ByteArray {
        val w = s.width; val h = s.height
        val code = IntArray(16) { -1 }; val codeLen = IntArray(16)
        var n = 0
        for (t in SYMBOL_TABLE) { code[t and 0xff] = n shr (10 - (t shr 8)); codeLen[t and 0xff] = t shr 8; n += 1024 shr (t shr 8) }
        val sink = BitSink()
        val vpred = IntArray(4); val hpred = IntArray(2)
        for (row in 0 until h) for (col in 0 until w) {
            val pred = if (col < 2) vpred[(row and 1) * 2 + col] else hpred[col and 1]
            val d = (((f[row * w + col] - pred + 32768) and 0xffff) - 32768)
            val len = if (d == 0) 0 else if (d > 0) bitLength(d) else bitLength(-d)
            require(len <= 13) { "difference $d needs a symbol the table lacks" }
            sink.put(code[len], codeLen[len])
            if (len > 0) sink.put(if (d > 0) d else d + (1 shl len) - 1, len)
            if (col < 2) { vpred[(row and 1) * 2 + col] = f[row * w + col]; hpred[col] = f[row * w + col] } else hpred[col and 1] = f[row * w + col]
        }
        return sink.bytes()
    }

    // -------------------------------------------------------------- SRW3
    fun encodeV3(s: Spec, target: IntArray, rng: Random): Pair<ByteArray, ShortArray> {
        val w = s.width; val h = s.height
        val bitDepth = s.bits
        val dataMax = (1 shl bitDepth) - 1
        val opt = s.optFlags
        val initVal = target[0]
        val recon = IntArray(w * h)
        val out = java.io.ByteArrayOutputStream()
        val head = BitSink()
        head.put(0, 16); head.put(0, 4); head.put(bitDepth - 1, 4); head.put(0, 8); head.put(0, 16); head.put(0, 16); head.put(0, 16)
        head.put(0, 4); head.put(opt, 4); head.put(0, 16); head.put(0, 8); head.put(0, 2); head.put(initVal, 14)
        out.write(le32(head, 16))
        val motionOffset = intArrayOf(-4, -2, -2, 0, 0, 2, 4)
        val motionAverage = intArrayOf(0, 0, 1, 0, 1, 0, 0)
        val mode = IntArray(6)
        for (row in 0 until h) {
            val sink = BitSink()
            var motion = 7
            var scale = 0
            for (i in 0 until 6) mode[i] = if (row < 2) 7 else 4
            var col = 0
            while (col < w - 15) {
                if (opt and 4 == 0 && (col and 63) == 0) {
                    when (rng.nextInt(6)) {
                        1 -> if (scale >= 2) { sink.put(1, 2); scale -= 2 } else sink.put(0, 2)
                        2 -> if (scale < 8) { sink.put(2, 2); scale += 2 } else sink.put(0, 2)
                        3 -> { sink.put(3, 2); scale = rng.nextInt(4); sink.put(scale, 12) }
                        else -> sink.put(0, 2)
                    }
                }
                fun refOf(m: Int, i: Int): Int {
                    val refRow = if ((row + i) and 1 != 0) row - 2 else row - 1
                    val refCol = col + i + motionOffset[m] + (1 - ((row + i) and 1)) * (1 - 2 * (i and 1))
                    return refRow * w + refCol
                }
                fun valid(m: Int) = m == 7 || (row >= 2 && (0 until 16).all { refOf(m, it) >= 0 && refOf(m, it) + 2 < recon.size })
                var want = if (row < 2 || rng.nextInt(3) == 0) 7 else rng.nextInt(7)
                if (!valid(want)) want = 7
                if (opt and 2 != 0) {
                    if (want != 7) want = 3
                    if (!valid(want)) want = 7
                    sink.put(if (want == 3) 1 else 0, 1); motion = want
                } else if (want == motion && rng.nextBoolean()) sink.put(1, 1)
                else { sink.put(0, 1); sink.put(want, 3); motion = want }
                val base = IntArray(16)
                if (motion == 7) {
                    for (i in 0 until 16) base[i] = if (col == 0) initVal else if (i < 2) recon[row * w + col + i - 2] else base[i - 2] // LibRaw fills in place, so it cascades
                } else {
                    for (i in 0 until 16) {
                        val ref = refOf(motion, i)
                        base[i] = if (motionAverage[motion] != 0) (recon[ref] + recon[ref + 2] + 1) shr 1 else recon[ref]
                    }
                }
                val rcols = IntArray(16) { i -> if (row % 2 != 0) col + ((i and 7) shl 1) + 1 - (i shr 3) else col + ((i and 7) shl 1) + (i shr 3) }
                val step = scale * 2 + 1
                // a pixel's prediction is what the motion step left at its position
                val predicted = IntArray(16) { base[rcols[it] - col] }
                val qWanted = IntArray(16) { i ->
                    Math.round((target[row * w + rcols[i]] - predicted[i] - scale).toDouble() / step).toInt()
                }
                val needed = IntArray(4) { g -> var m = 1; for (i in 4 * g until 4 * g + 4) m = maxOf(m, signedBits(qWanted[i])); m.coerceAtMost(bitDepth + 1) }
                // choose the flag for each set against the state as the decoder will see it
                val shadow = mode.copyOf()
                val flags = IntArray(4); val res = IntArray(4)
                for (i in 0 until 4) {
                    val cn = if (row % 2 != 0) i shr 1 else ((i shr 1) + 2) % 3
                    val cur = shadow[cn * 2]
                    // take a slightly longer field now and then; a zero length is never used
                    val wantLen = if (rng.nextInt(5) == 0) minOf(needed[i] + 1, bitDepth + 1) else needed[i]
                    flags[i] = when (wantLen) { cur -> 0; cur + 1 -> 1; cur - 1 -> 2; else -> 3 }
                    res[i] = wantLen
                    shadow[cn * 2] = shadow[cn * 2 + 1]; shadow[cn * 2 + 1] = res[i]
                }
                if (opt and 1 == 0) sink.put(0, 1) // flags follow
                for (i in 0 until 4) sink.put(flags[i], 2)
                for (i in 0 until 4) if (flags[i] == 3) sink.put(res[i], 4)
                System.arraycopy(shadow, 0, mode, 0, 6)
                for (i in 0 until 16) {
                    val len = res[i shr 2]
                    val q = qWanted[i].coerceIn(-(1 shl (len - 1)), (1 shl (len - 1)) - 1)
                    sink.put(q and ((1 shl len) - 1), len)
                    val v = predicted[i] + q * step + scale
                    recon[row * w + rcols[i]] = if (v < 0) 0 else if (v > dataMax) dataMax else v
                }
                col += 16
            }
            out.write(le32(sink, 16))
        }
        return out.toByteArray() to ShortArray(recon.size) { recon[it].toShort() }
    }
}

class SrwTest {
    private fun crc(m: ShortArray): Long {
        val c = CRC32()
        val b = ByteArray(m.size * 2)
        for (i in m.indices) { b[2 * i] = m[i].toByte(); b[2 * i + 1] = (m[i].toInt() shr 8).toByte() }
        c.update(b)
        return c.value
    }

    private val specs = listOf(
        SrwSynth.Spec("srw1_nx10", "NX10", 32770, 12, 4704, 64, 1, rowTable = true, black = intArrayOf(0, 0, 0, 0)),
        SrwSynth.Spec("srw2_mini14", "NX mini", 32772, 14, 5664, 64, 2, max = 8000),
        SrwSynth.Spec("srw2_mini12", "NX mini", 32772, 12, 5664, 48, 3, black = intArrayOf(7, 9, 11, 13)),
        SrwSynth.Spec("raw14_mini", "NX mini", 32769, 14, 5664, 48, 4),
        SrwSynth.Spec("srw2_neutral", "NX mini", 32772, 14, 5664, 96, 8, max = 2400, levels = intArrayOf(3352, 1000, 1000, 1436)),
        SrwSynth.Spec("srw3_nx1", "NX1", 32773, 14, 6496, 436, 5, cfa = intArrayOf(0, 1, 1, 2)),
        SrwSynth.Spec("srw3_nx500_opt7", "NX500", 32773, 14, 6496, 440, 6, optFlags = 7),
        SrwSynth.Spec("srw3_nx1_opt3", "NX1", 32773, 14, 6496, 438, 7, optFlags = 3, black = intArrayOf(128, 128, 128, 128)),
    )

    /** CRC32 of the mosaic (little-endian uint16) that LibRaw 0.22.2 / rawpy decodes from these synthetic files. */
    private val libRawChecksums = mapOf(
        "srw1_nx10" to 2419425442L, "srw2_mini14" to 185238570L, "srw2_mini12" to 626565802L, "raw14_mini" to 3916385091L,
        "srw3_nx1" to 3770442874L, "srw2_neutral" to 790015471L, "srw3_nx500_opt7" to 2843489766L, "srw3_nx1_opt3" to 3529393733L,
    )

    /** Frame sizes with hard-wired geometry in LibRaw; the files carry no pixels, only the headers LibRaw sizes them from. */
    private val geometrySpecs = listOf(
        SrwSynth.Spec("geo_nx1000_14", "NX1000", 32772, 14, 5632, 3720, 11),
        SrwSynth.Spec("geo_nx200_12", "NX200", 32772, 12, 5632, 3720, 12, cfa = intArrayOf(0, 1, 1, 2)),
        SrwSynth.Spec("geo_nx3000", "NX3000", 32772, 14, 5600, 3720, 13),
        SrwSynth.Spec("geo_nx300", "NX300", 32772, 14, 5600, 3714, 14),
        SrwSynth.Spec("geo_nx30_5568", "NX30", 32772, 14, 5568, 3714, 15),
        SrwSynth.Spec("geo_nx11", "NX11", 32770, 12, 4704, 3448, 16, rowTable = true),
        SrwSynth.Spec("geo_nx500", "NX500", 32773, 14, 6496, 4336, 17),
        SrwSynth.Spec("geo_nxmini", "NX mini", 32772, 12, 5664, 3720, 18),
    )

    private fun dumpDir(): File? = System.getProperty("spektra.rawdump")?.takeIf { it.isNotBlank() }?.let { File(it).also { d -> d.mkdirs() } }

    @Test
    fun syntheticFilesDecodeToWhatTheEncodersWrote() {
        val mismatches = ArrayList<String>()
        for (s in specs) {
            val built = SrwSynth.build(s)
            dumpDir()?.let { d ->
                File(d, s.name + ".srw").writeBytes(built.file)
                File(d, s.name + ".raw16").writeBytes(ByteArray(built.mosaic.size * 2) { i -> (built.mosaic[i / 2].toInt() shr (8 * (i % 2))).toByte() })
            }
            val img = assertNotNull(RawFormatCheck.read("SRW", built.file), s.name)
            if (s.name == "srw2_neutral") dumpDir()?.let { d ->
                val dev = RawDeveloper.develop(img, 8192)
                File(d, s.name + ".developed.txt").printWriter().use { p ->
                    p.println("${dev.width} ${dev.height} ${img.cropX} ${img.cropY}")
                    for (y in 0 until dev.height) for (x in 0 until dev.width) p.println("${dev.red(x, y)} ${dev.green(x, y)} ${dev.blue(x, y)}")
                }
            }
            assertEquals(s.width, img.rawWidth, s.name)
            assertEquals(s.height, img.rawHeight, s.name)
            assertContentEquals(built.mosaic, img.data, s.name)
            if (libRawChecksums.getValue(s.name) != crc(img.data)) mismatches += "${s.name}: ${crc(img.data)}"
        }
        assertTrue(mismatches.isEmpty(), "differs from LibRaw's checksum: $mismatches")
    }

    /** Visible area LibRaw 0.22.2 reports for headers of these frame sizes (margins l,t and width x height, margins made even). */
    @Test
    fun geometryMatchesLibRaw() {
        data class Expect(val x: Int, val y: Int, val w: Int, val h: Int, val cfa: String)
        val expected = mapOf(
            "geo_nx1000_14" to Expect(46, 2, 5528, 3694, "GRBG"),
            "geo_nx200_12" to Expect(44, 2, 5530, 3694, "RGGB"),
            "geo_nx3000" to Expect(92, 38, 5456, 3634, "GRBG"),
            "geo_nx300" to Expect(64, 18, 5536, 3696, "GRBG"),
            "geo_nx30_5568" to Expect(0, 0, 5536, 3696, "GRBG"),
            "geo_nx11" to Expect(8, 8, 4688, 3440, "GRBG"),
            "geo_nx500" to Expect(0, 0, 6496, 4336, "GRBG"),
            "geo_nxmini" to Expect(96, 18, 5544, 3702, "RGGB"), // GBRG relative to the odd top margin 17
        )
        for (g in geometrySpecs) {
            val f = SrwSynth.container(g, ByteArray(64), (0 until g.height).map { 0 })
            dumpDir()?.let { d -> File(d, g.name + ".srw").writeBytes(f) }
            val m = assertNotNull(SrwReader.meta(f), g.name)
            val e = expected.getValue(g.name)
            assertEquals(listOf(e.x, e.y, e.w, e.h), listOf(m.cropX, m.cropY, m.cropW, m.cropH), g.name)
            assertEquals("CfaPattern(2x2, ${e.cfa})", m.cfa.toString(), g.name)
        }
    }

    @Test
    fun blackLevelsAndWhiteBalanceFollowTheMakerNote() {
        // levels 2500 / 1024 / 1024 / 1900 (R, G1, G2, B), black 7 / 9 / 11 / 13 (LibRaw: black_level_per_channel [7, 9, 13, 11])
        val s = specs.first { it.name == "srw2_mini12" }
        val img = assertNotNull(RawFormatCheck.read("SRW", SrwSynth.build(s).file))
        val rggb = img.cfa.colors.toList()
        assertEquals(listOf(0, 1, 1, 2), rggb)
        assertContentEquals(floatArrayOf(7f, 9f, 11f, 13f), img.black) // R, G in the R row, G in the B row, B
        assertEquals(4095f, img.white)
        // WB = levels - black (ExifTool / rawspeed / rawloader); LibRaw reports the levels themselves
        val flat = SrwReader.meta(SrwSynth.build(s).file)!!.wb.wb
        assertEquals((2500f - 7f) / (1024f - 9f), flat[0], 1e-5f)
        assertEquals((1900f - 13f) / (1024f - 9f), flat[2], 1e-5f)
        // with no black stored it is exactly LibRaw's camera_whitebalance
        val z = SrwReader.meta(SrwSynth.build(specs.first { it.name == "srw2_mini14" }).file)!!
        assertEquals(2500f / 1024f, z.wb.wb[0], 1e-5f)
        assertEquals(1900f / 1024f, z.wb.wb[2], 1e-5f)
        // NX1 / NX500 without a stored black level: 1 shl (bits - 7), as LibRaw
        val nx = SrwReader.meta(SrwSynth.build(specs.first { it.name == "srw3_nx1" }).file)!!
        assertContentEquals(floatArrayOf(128f, 128f, 128f, 128f), nx.black4)
    }

    @Test
    fun makerNoteOffsetsMayBeAbsolute() {
        // some Samsung files store absolute offsets in the note: the reader accepts both (LibRaw only the relative form)
        val s = specs.first { it.name == "srw2_mini14" }
        val file = SrwSynth.absolutizeNote(SrwSynth.build(s).file)
        val m = assertNotNull(SrwReader.meta(file))
        assertEquals(2500f / 1024f, m.wb.wb[0], 1e-5f)
    }

    @Test
    fun brokenInputReturnsNull() {
        val good = SrwSynth.build(specs[1]).file
        assertNotNull(RawFormatCheck.read("SRW", good))
        assertNull(SrwReader.read(ByteArray(0)))
        assertNull(SrwReader.read(good.copyOf(good.size / 2)))
        assertNull(SrwReader.read(good.copyOf().also { it[0] = 'M'.code.toByte() }))
    }

    @Test
    fun corruptedBytesNeverThrow() {
        val rng = Random(7)
        for (spec in listOf(specs[0], specs[1], specs[4])) {
            val good = SrwSynth.build(spec).file
            repeat(40) {
                val f = good.copyOf()
                repeat(1 + rng.nextInt(30)) { f[rng.nextInt(f.size)] = rng.nextInt(256).toByte() }
                SrwReader.read(f)
                SrwReader.read(f.copyOf(rng.nextInt(f.size)))
            }
        }
    }
}
