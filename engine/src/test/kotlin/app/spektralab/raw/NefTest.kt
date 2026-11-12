package app.spektralab.raw

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Synthetic NEF files: an in-test encoder for every pixel layout [NefReader] supports, wrapped in a
 * NEF-shaped TIFF (IFD0 + raw SubIFD + EXIF maker note with a "Nikon\0\2" inner TIFF).
 *
 * With `-Dspektra.nefsynth=DIR` (or env SPEKTRA_NEF_SYNTH_OUT) the files are also written to DIR with
 * an `expected.tsv`, which `tools/nef_synth_check.py DIR` checks against LibRaw. All cases below
 * were checked that way with LibRaw 0.22.2: LibRaw's raw_image, black, white, camera WB and
 * crop equal what this test expects, so the encoder (and not only the round trip) is right.
 */
class NefTest {
    // ------------------------------------------------------------------ file builder
    class Spec(
        val name: String,
        val model: String,
        val width: Int, val height: Int, val bps: Int, val compression: Int,
        val strips: List<ByteArray>, val rowsPerStrip: Int,
        val le: Boolean = true,
        val cfa: IntArray = intArrayOf(0, 1, 1, 2),
        val orientation: Int = 1,
        val make: String = "NIKON CORPORATION",
        val iso: Int = 0,
        val makerNote: (TiffBuilder.Ifd) -> Unit = {},
    )

    private fun build(s: Spec): ByteArray {
        val mn = TiffBuilder.Ifd()
        s.makerNote(mn)
        val inner = TiffBuilder(s.le).build(mn)
        val note = "Nikon\u0000".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(2, 0x10, 0, 0) + inner
        val exif = TiffBuilder.Ifd().undefined(TiffTags.MAKER_NOTE, note)
        if (s.iso > 0) exif.short(34855, s.iso)
        val rawIfd = TiffBuilder.Ifd()
            .long(TiffTags.NEW_SUBFILE_TYPE, 0)
            .long(TiffTags.IMAGE_WIDTH, s.width).long(TiffTags.IMAGE_LENGTH, s.height)
            .short(TiffTags.BITS_PER_SAMPLE, s.bps).short(TiffTags.COMPRESSION, s.compression)
            .short(TiffTags.PHOTOMETRIC, 32803).short(TiffTags.SAMPLES_PER_PIXEL, 1)
            .long(TiffTags.ROWS_PER_STRIP, s.rowsPerStrip)
            .short(TiffTags.CFA_REPEAT_PATTERN_DIM, 2, 2).byte(TiffTags.CFA_PATTERN, *s.cfa)
            .blobs(TiffTags.STRIP_OFFSETS, TiffTags.STRIP_BYTE_COUNTS, s.strips)
        // a tiny RGB thumbnail as IFD0, like the cameras write
        val thumb = ByteArray(16 * 16 * 3) { 0x40 }
        val ifd0 = TiffBuilder.Ifd()
            .long(TiffTags.NEW_SUBFILE_TYPE, 1).long(TiffTags.IMAGE_WIDTH, 16).long(TiffTags.IMAGE_LENGTH, 16)
            .short(TiffTags.BITS_PER_SAMPLE, 8, 8, 8).short(TiffTags.COMPRESSION, 1).short(TiffTags.PHOTOMETRIC, 2)
            .ascii(TiffTags.MAKE, s.make).ascii(TiffTags.MODEL, s.model)
            .short(TiffTags.ORIENTATION, s.orientation).short(TiffTags.SAMPLES_PER_PIXEL, 3).long(TiffTags.ROWS_PER_STRIP, 16)
            .blobs(TiffTags.STRIP_OFFSETS, TiffTags.STRIP_BYTE_COUNTS, listOf(thumb))
            .subIfds(TiffTags.SUB_IFDS, rawIfd)
            .subIfds(TiffTags.EXIF_IFD, exif)
        return TiffBuilder(s.le).build(ifd0)
    }

    private fun shorts(le: Boolean, vararg v: Int): ByteArray {
        val bb = ByteBuffer.allocate(v.size * 2).order(if (le) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        v.forEach { bb.putShort(it.toShort()) }
        return bb.array()
    }

    // ------------------------------------------------------------------ Nikon Huffman encoder
    private val trees: Array<IntArray> = arrayOf(
        intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, 0, 5, 4, 3, 6, 2, 7, 1, 0, 8, 9, 11, 10, 12, 0),
        intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, 0, 0x39, 0x5a, 0x38, 0x27, 0x16, 5, 4, 3, 2, 1, 0, 11, 12, 12),
        intArrayOf(0, 1, 4, 2, 3, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 5, 4, 6, 3, 7, 2, 8, 1, 9, 0, 10, 11, 12),
        intArrayOf(0, 1, 4, 3, 1, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, 0, 5, 6, 4, 7, 8, 3, 9, 2, 1, 0, 10, 11, 12, 13, 14),
        intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, 8, 0x5c, 0x4b, 0x3a, 0x29, 7, 6, 5, 4, 3, 2, 1, 0, 13, 14),
        intArrayOf(0, 1, 4, 2, 2, 3, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 7, 6, 8, 5, 9, 4, 10, 3, 11, 12, 2, 0, 1, 13, 14),
    )

    /** Canonical codes (code, length) for the shl == 0 symbols of a dcraw tree, keyed by bit length of the diff. */
    private fun codes(tree: IntArray): Map<Int, Pair<Int, Int>> {
        val out = HashMap<Int, Pair<Int, Int>>()
        var code = 0; var k = 16
        for (len in 1..16) {
            repeat(tree[len - 1]) {
                val sym = tree[k++]
                if (sym ushr 4 == 0 && sym !in out) out[sym] = code to len
                code++
            }
            code = code shl 1
        }
        return out
    }

    /**
     * Encodes [values] (curve indices, rawWidth x height) as nikon_load_raw reads them: the first two
     * columns predict from the previous row of the same parity (starting at [vpred]), the others
     * from two columns left. Rows from [split] on use the "after split" tree.
     */
    private fun encodeNikon(values: IntArray, w: Int, h: Int, tree: Int, vpred: IntArray, split: Int = 0): ByteArray {
        val wr = MsbBitWriter()
        val vp = vpred.copyOf()
        var c0 = codes(trees[tree])
        val hp = IntArray(2)
        for (row in 0 until h) {
            if (split != 0 && row == split) c0 = codes(trees[tree + 1])
            for (col in 0 until w) {
                val v = values[row * w + col]
                val pred = if (col < 2) vp[(row and 1) * 2 + col] else hp[col and 1]
                val diff = v - pred
                val len = if (diff == 0) 0 else 32 - Integer.numberOfLeadingZeros(kotlin.math.abs(diff))
                val (code, clen) = c0[len] ?: error("no code for length $len")
                wr.put(code, clen)
                if (len > 0) wr.put(if (diff > 0) diff else diff + (1 shl len) - 1, len)
                if (col < 2) vp[(row and 1) * 2 + col] = v
                hp[col and 1] = v
            }
        }
        repeat(8) { wr.put(0, 8) }
        return wr.bytes()
    }

    /** Maker note tag 0x96: version, vpred, optional curve, split row at +562. */
    private fun linearization(le: Boolean, ver0: Int, ver1: Int, vpred: IntArray, curve: IntArray?, split: Int): ByteArray {
        val b = ByteArray(600)
        b[0] = ver0.toByte(); b[1] = ver1.toByte()
        shorts(le, *vpred).copyInto(b, 2)
        if (curve != null) { shorts(le, curve.size).copyInto(b, 10); shorts(le, *curve).copyInto(b, 12) }
        shorts(le, split).copyInto(b, 562)
        return b
    }

    private fun smooth(w: Int, h: Int, max: Int, seed: Int, step: Int = 64): IntArray {
        val r = Random(seed)
        return IntArray(w * h) { i -> ((i % w) * 37 + (i / w) * 11 + r.nextInt(step)).mod(max) }
    }

    // ------------------------------------------------------------------ cases
    /** [camMul]: the maker-note multipliers as LibRaw reports them, R, G, B, G2. */
    class Case(val spec: Spec, val mosaic: IntArray, val black: Float, val white: Float,
               val camMul: DoubleArray, val crop: IntArray, val cfaAtCrop: String, val orientation: Int = 1)

    private fun lossless14(): Case {
        val w = 64; val h = 30
        val vals = smooth(w, h, 16384, 1, 4096).also { it[5] = 16383; it[6] = 0 }
        val vpred = intArrayOf(8000, 8100, 8200, 8300)
        val data = encodeNikon(vals, w, h, 5, vpred)
        val spec = Spec("lossless14_le.nef", "NIKON D7500", w, h, 14, 34713, listOf(data), h, le = true, orientation = 6) { mn ->
            mn.rational(0x000c, 2.25, 1.5, 1.0, 1.0)
            mn.short(0x003d, 400, 400, 400, 400)
            mn.short(0x0045, 2, 4, 60, 24)
            mn.undefined(0x0096, linearization(true, 0x46, 0x30, vpred, null, 0))
        }
        return Case(spec, vals, 400f, 16383f, doubleArrayOf(2.25, 1.0, 1.5, 1.0), intArrayOf(2, 4, 60, 24), "RGGB", 6)
    }

    private fun lossless12be(): Case {
        val w = 48; val h = 24
        val vals = smooth(w, h, 4096, 2, 1024)
        val vpred = intArrayOf(2048, 2048, 2048, 2048)
        val data = encodeNikon(vals, w, h, 2, vpred)
        val spec = Spec("lossless12_be.nef", "NIKON D7500", w, h, 12, 34713, listOf(data), h, le = false, cfa = intArrayOf(1, 0, 2, 1), orientation = 8) { mn ->
            mn.rational(0x000c, 1.75, 1.25, 1.0, 1.0)
            mn.short(0x003d, 1008, 1024, 1016, 1012)                 // RGGB order, 14-bit units
            mn.undefined(0x0096, linearization(false, 0x46, 0x30, vpred, null, 0))
        }
        // 12-bit: black / 4 per channel: R 252, G 256 (red row), G2 254 (blue row), B 253
        return Case(spec, vals, Float.NaN, 4095f, doubleArrayOf(1.75, 1.0, 1.25, 1.0), intArrayOf(0, 0, w, h), "GRBG", 8)
    }

    /** 12-bit lossy type 1: curve with 257 points, split row switching to the second tree (D70-style). */
    private fun lossy12split(): Case {
        val w = 40; val h = 26; val split = 13
        val r = Random(3)
        val idx = IntArray(w * h)
        for (row in 0 until h) for (col in 0 until w) {
            idx[row * w + col] = if (row < split) r.nextInt(4096) else 1500 + (col and 1) * 7 + r.nextInt(10)
        }
        // rows right after the split predict from rows before it: keep those small diffs too
        for (row in split - 2 until split) for (col in 0 until 2) idx[row * w + col] = 1500 + col * 7
        val vpred = intArrayOf(0, 0, 0, 0)
        val curve = IntArray(257) { (Math.pow(it / 256.0, 1.7) * 4095).toInt() }
        val data = encodeNikon(idx, w, h, 0, vpred, split)
        val spec = Spec("lossy12_split_be.nef", "NIKON D70s", w, h, 12, 34713, listOf(data), h, le = false, cfa = intArrayOf(2, 1, 1, 0)) { mn ->
            mn.undefined(0x0096, linearization(false, 0x44, 0x20, vpred, curve, split))
            mn.undefined(0x0097, "0103".toByteArray() + ByteArray(0x10) + shorts(false, 536, 256, 460, 256))
        }
        // expected output = interpolated curve at the index (nikon_read_curve)
        val full = IntArray(4096)
        val step = 4096 / 256
        val c = IntArray(0x10000) { it }
        for (i in 0 until 257) c[i * step] = curve[i]
        for (i in 0 until 4096) { val m = i % step; full[i] = (c[i - m] * (step - m) + c[i - m + step] * m) / step }
        return Case(spec, IntArray(w * h) { full[idx[it]] }, 0f, 4095f, doubleArrayOf(536.0, 256.0, 460.0, 256.0), intArrayOf(0, 0, w - 1, h), "BGGR")
    }

    /** 14-bit lossy type 2 (version 0x44 0x40): the curve covers a quarter of the range, split tree 4 after [split]. */
    private fun lossy14type2(): Case {
        val w = 36; val h = 28; val split = 10
        val r = Random(11)
        val idx = IntArray(w * h)
        for (row in 0 until h) for (col in 0 until w)
            idx[row * w + col] = if (row < split - 2) r.nextInt(4096) else 2000 + (col and 1) * 9 + r.nextInt(30)
        val vpred = intArrayOf(10, 20, 30, 40)
        val curve = IntArray(257) { (Math.pow(it / 256.0, 2.0) * 16383).toInt() }
        val data = encodeNikon(idx, w, h, 3, vpred, split)
        val spec = Spec("lossy14_type2_le.nef", "NIKON D850", w, h, 14, 34713, listOf(data), h) { mn ->
            mn.rational(0x000c, 2.0, 1.5, 1.0, 1.0)
            mn.undefined(0x0096, linearization(true, 0x44, 0x40, vpred, curve, split))
        }
        val step = 16384 / 256 / 4
        val c = IntArray(0x10000) { it }
        for (i in 0 until 257) c[i * step] = curve[i]
        val full = IntArray(4096) { i -> val m = i % step; (c[i - m] * (step - m) + c[i - m + step] * m) / step }
        return Case(spec, IntArray(w * h) { full[idx[it]] }, 0f, 16383f, doubleArrayOf(2.0, 1.0, 1.5, 1.0), intArrayOf(0, 0, w, h), "RGGB")
    }

    /** D700-style encrypted ColorBalance 0209, serial + shutter-count keys; 0x0c must be overridden. */
    private fun encryptedWb(): Case {
        val w = 40; val h = 24
        val vals = smooth(w, h, 16384, 4, 2000)
        val vpred = intArrayOf(100, 200, 300, 400)
        val data = encodeNikon(vals, w, h, 5, vpred)
        val serial = "2239306"; val count = byteArrayOf(0x00, 0x00, 0x32, 0x03)
        // version 0209: offset 11 in LibRaw's "66666>666;6A;:;555" -> G R B G2 from byte 10
        val plain = ByteArray(324).also { shorts(false, 256, 446, 433, 256).copyInto(it, 10) }
        val key = (count[0].toInt() xor count[1].toInt() xor count[2].toInt() xor count[3].toInt()) and 0xff
        val ci = XLAT0[2239306 and 0xff]; var cj = XLAT1[key]; var ck = 0x60
        val enc = ByteArray(324) { i -> cj = (cj + ci * ck) and 0xff; ck = (ck + 1) and 0xff; (plain[i].toInt() xor cj).toByte() }
        val spec = Spec("encrypted_wb_be.nef", "NIKON D700", w, h, 14, 34713, listOf(data), h, le = false) { mn ->
            mn.rational(0x000c, 3.0, 2.0, 1.0, 1.0)
            mn.ascii(0x001d, serial)
            mn.undefined(0x0096, linearization(false, 0x46, 0x30, vpred, null, 0))
            mn.undefined(0x0097, "0209".toByteArray() + ByteArray(0x118) + enc)
            mn.undefined(0x00a7, count)
        }
        return Case(spec, vals, 0f, 16383f, doubleArrayOf(446.0, 256.0, 433.0, 256.0), intArrayOf(2, 0, w - 4, h), "RGGB")
    }

    /** Coolpix NRW: WB and black in the "NRW 0200" block of tag 0x14, 12-bit data through 32-bit words (load_flags 24). */
    private fun nrw(model: String, iso: Int, black: Float): Case {
        val w = 40; val h = 24
        val vals = smooth(w, h, 4096, 12, 4096)
        val block = ByteArray(80)
        "NRW 0200".toByteArray().copyInto(block)
        shorts(true, 200).copyInto(block, 32)
        val bb = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(300).putInt(256).putInt(258).putInt(230)
        bb.array().copyInto(block, 56)
        val spec = Spec("nrw_${model.substringAfter(' ').lowercase()}.nrw", model, w, h, 12, 1,
            listOf(pack(vals, 12, Unpack.BitOrder.MSB32, w, w * 3 / 2)), h, make = "NIKON", iso = iso) { mn ->
            mn.undefined(0x0014, block)
        }
        // cam_mul = (2 * 300, 256, 2 * 230, 258): R, G, B, G2
        return Case(spec, vals, black, 4095f, doubleArrayOf(600.0, 256.0, 460.0, 258.0), intArrayOf(0, 0, w, h), "RGGB")
    }

    /** D2H-style plain ColorBalance 0102 (RGGB shorts at +6), 12-bit lossless Huffman, 6-pixel left margin. */
    private fun colorBalance102(): Case {
        val w = 48; val h = 24
        val vals = smooth(w, h, 4096, 13, 2048)
        val vpred = intArrayOf(1000, 1000, 1000, 1000)
        val data = encodeNikon(vals, w, h, 2, vpred)
        val spec = Spec("cb0102_d2h.nef", "NIKON D2H", w, h, 12, 34713, listOf(data), h, le = false) { mn ->
            mn.undefined(0x0096, linearization(false, 0x46, 0x30, vpred, null, 0))
            mn.undefined(0x0097, "0102".toByteArray() + ByteArray(6) + shorts(false, 500, 256, 260, 400))
        }
        return Case(spec, vals, 0f, 4095f, doubleArrayOf(500.0, 256.0, 400.0, 260.0), intArrayOf(6, 0, w - 14, h), "RGGB")
    }

    /** Compression 34713 with two bytes per pixel: big-endian words shifted right by 4 (LibRaw load_flags 4). */
    private fun unpacked34713(): Case {
        val w = 36; val h = 24                // 32 would also match the 10-pixel padded size
        val vals = smooth(w, h, 4096, 14, 4096)
        val spec = Spec("unpacked_34713.nef", "NIKON D850", w, h, 14, 34713, listOf(shorts(false, *IntArray(w * h) { (vals[it] shl 4) or 5 })), h) { mn ->
            mn.rational(0x000c, 2.0, 1.5, 1.0, 1.0)
        }
        return Case(spec, vals, 0f, 16383f, doubleArrayOf(2.0, 1.0, 1.5, 1.0), intArrayOf(0, 0, w, h), "RGGB")
    }

    /** Compression 34713, 12-bit MSB packed with one pad byte after every 10 pixels (LibRaw load_flags 1). */
    private fun packedPad10(): Case {
        val w = 40; val h = 24
        val vals = smooth(w, h, 4096, 15, 4096)
        val groups = pack(vals, 12, Unpack.BitOrder.MSB, 10, 15)             // one "row" per 10-pixel group
        val out = ByteArray(groups.size / 15 * 16)
        for (g in 0 until groups.size / 15) groups.copyInto(out, g * 16, g * 15, g * 15 + 15)
        val spec = Spec("packed_pad10.nef", "NIKON D850", w, h, 12, 34713, listOf(out), h) { mn ->
            mn.rational(0x000c, 2.0, 1.5, 1.0, 1.0)
        }
        return Case(spec, vals, 0f, 4095f, doubleArrayOf(2.0, 1.0, 1.5, 1.0), intArrayOf(0, 0, w, h), "RGGB")
    }

    private fun packed12(): Case {
        val w = 40; val h = 24
        val vals = smooth(w, h, 4096, 5, 4096)
        // 34713 with 1.5 bytes per pixel: little-endian 12-bit pairs (LibRaw load_flags 80)
        val packed = pack(vals, 12, Unpack.BitOrder.LSB, w, w * 3 / 2)
        val spec = Spec("packed12_le.nef", "NIKON D7100", w, h, 12, 34713, listOf(packed), h) { mn ->
            mn.rational(0x000c, 2.0, 1.5, 1.0, 1.0); mn.short(0x003d, 600, 600, 600, 600)
        }
        return Case(spec, vals, 150f, 4095f, doubleArrayOf(2.0, 1.0, 1.5, 1.0), intArrayOf(0, 0, w, h), "RGGB")
    }

    private fun unpacked14(le: Boolean): Case {
        val w = 32; val h = 24
        val vals = smooth(w, h, 16384, 6, 16384)
        val spec = Spec("unpacked14_${if (le) "le" else "be"}.nef", "NIKON D850", w, h, 14, 1, listOf(shorts(le, *vals)), h, le = le) { mn ->
            mn.rational(0x000c, 2.0, 1.5, 1.0, 1.0); mn.short(0x003d, 400, 400, 400, 400)
        }
        return Case(spec, vals, 400f, 16383f, doubleArrayOf(2.0, 1.0, 1.5, 1.0), intArrayOf(0, 0, w, h), "RGGB")
    }

    private fun uncompressed12msb(): Case {
        val w = 40; val h = 24
        val vals = smooth(w, h, 4096, 7, 4096)
        val spec = Spec("uncompressed12_msb.nef", "NIKON D850", w, h, 12, 1, listOf(pack(vals, 12, Unpack.BitOrder.MSB, w, w * 3 / 2)), h) { mn ->
            mn.rational(0x000c, 2.0, 1.5, 1.0, 1.0)
        }
        return Case(spec, vals, 0f, 4095f, doubleArrayOf(2.0, 1.0, 1.5, 1.0), intArrayOf(0, 0, w, h), "RGGB")
    }

    private fun padded12(): Case {
        val w = 1350; val h = 24
        val vals = smooth(w, h, 4096, 8, 4096)
        val bpr = (w * 3 / 2 + 15) / 16 * 16
        val packed = pack(vals, 12, Unpack.BitOrder.LSB, w, bpr)
        val spec = Spec("padded12.nef", "NIKON D3400", w, h, 12, 34713, listOf(packed), h) { mn ->
            mn.rational(0x000c, 2.0, 1.5, 1.0, 1.0)
        }
        return Case(spec, vals, 0f, 4095f, doubleArrayOf(2.0, 1.0, 1.5, 1.0), intArrayOf(0, 0, w, h), "RGGB")
    }

    private fun striped12(): Case {
        val w = 40; val h = 24; val rps = 8
        val vals = smooth(w, h, 4096, 9, 4096)
        // MSB-first bits of 32-bit little-endian words; each strip is its own word stream
        val strips = (0 until h / rps).map { s ->
            pack(vals.copyOfRange(s * rps * w, (s + 1) * rps * w), 12, Unpack.BitOrder.MSB32, rps * w, rps * w * 3 / 2)
        }
        val spec = Spec("striped12.nef", "NIKON D3500", w, h, 12, 34713, strips, rps) { mn ->
            mn.rational(0x000c, 2.0, 1.5, 1.0, 1.0)
        }
        return Case(spec, vals, 0f, 4095f, doubleArrayOf(2.0, 1.0, 1.5, 1.0), intArrayOf(0, 0, w, h), "RGGB")
    }

    private fun packed14z(): Case {
        val w = 96; val h = 24
        val vals = smooth(w, h, 16384, 10, 16384)
        val line = (Math.ceil((w * 7 / 4) / 16.0).toInt()) * 16
        val out = ByteArray(line * h)
        for (row in 0 until h) {
            var bit = 0L
            val s = row * line
            // little-endian bit order: pixel k occupies bits [14k, 14k + 14)
            for (col in 0 until w) {
                val v = vals[row * w + col].toLong()
                for (j in 0 until 14) if ((v ushr j) and 1L != 0L) {
                    val p = bit + j
                    out[s + (p / 8).toInt()] = (out[s + (p / 8).toInt()].toInt() or (1 shl (p % 8).toInt())).toByte()
                }
                bit += 14
            }
        }
        val spec = Spec("packed14_z.nef", "NIKON Z 6", w, h, 14, 1, listOf(out), h) { mn ->
            mn.rational(0x000c, 2.0, 1.5, 1.0, 1.0); mn.short(0x003d, 400, 400, 400, 400)
        }
        return Case(spec, vals, 400f, 16383f, doubleArrayOf(2.0, 1.0, 1.5, 1.0), intArrayOf(0, 0, w, h), "RGGB")
    }

    private fun cases() = listOf(lossless14(), lossless12be(), lossy12split(), lossy14type2(), encryptedWb(), packed12(),
        unpacked14(true), unpacked14(false), uncompressed12msb(), padded12(), striped12(), packed14z(),
        nrw("COOLPIX P1000", 200, 200f), nrw("COOLPIX P7000", 800, 255f), colorBalance102(), unpacked34713(), packedPad10())

    private fun crc(v: IntArray): Long {
        val b = ByteArray(v.size * 2)
        for (i in v.indices) { b[2 * i] = v[i].toByte(); b[2 * i + 1] = (v[i] ushr 8).toByte() }
        return CRC32().apply { update(b) }.value
    }

    @Test
    fun syntheticRoundTrips() {
        val outDir = (System.getProperty("spektra.nefsynth")?.takeIf { it.isNotEmpty() } ?: System.getenv("SPEKTRA_NEF_SYNTH_OUT"))
            ?.let { File(it).apply { mkdirs() } }
        val tsv = StringBuilder("file\tcrc\tblack\twhite\tcam_mul\tcrop\n")
        val cases = cases()
        // per-position black, raw (0, 0) first; lossless12be has per-channel levels on a GRBG CFA:
        // G (red row) 256, R 252 / B 253, G (blue row) 254
        fun blacks(c: Case) = if (c.black.isNaN()) floatArrayOf(256f, 252f, 253f, 254f) else FloatArray(4) { c.black }
        if (outDir != null) {
            for (c in cases) {
                File(outDir, c.spec.name).writeBytes(build(c.spec))
                tsv.append("${c.spec.name}\t${crc(c.mosaic)}\t${blacks(c).joinToString(",") { it.toInt().toString() }}\t${c.white.toInt()}\t" +
                    "${c.camMul.joinToString(",")}\t${c.crop.joinToString(",")}\n")
            }
            File(outDir, "expected.tsv").writeText(tsv.toString())
        }
        for (c in cases) {
            val bytes = build(c.spec)
            val img = assertNotNull(RawFormatCheck.read("NEF", bytes), c.spec.name)
            assertContentEquals(c.mosaic, IntArray(img.data.size) { img.data[it].toInt() and 0xffff }, c.spec.name)
            assertContentEquals(blacks(c), FloatArray(4) { img.blackAt(it / 2, it % 2) }, c.spec.name)
            assertEquals(c.white, img.white, c.spec.name)
            assertEquals(c.crop.toList(), listOf(img.cropX, img.cropY, img.cropWidth, img.cropHeight), c.spec.name)
            val cfa = (0 until 4).joinToString("") { "RGB"[img.cfa.color(img.cropY + it / 2, img.cropX + it % 2)].toString() }
            assertEquals(c.cfaAtCrop, cfa, c.spec.name)
            assertEquals(c.orientation, img.orientation, c.spec.name)
            // RawImage has one green gain: the average of the two green multipliers
            val m = c.camMul
            val g = if (m[3] > 0) (m[1] + m[3]) / 2 else m[1]
            assertEquals((m[0] / g).toFloat(), img.wb[0], 1e-4f, c.spec.name)
            assertEquals((m[2] / g).toFloat(), img.wb[2], 1e-4f, c.spec.name)
        }
    }

    // ------------------------------------------------------------------ rejection
    @Test
    fun rejectsOtherFilesAndVariants() {
        assertNull(NefReader.read(ByteArray(0)))
        assertNull(NefReader.read(ByteArray(100)))
        val base = lossless14().spec
        fun with(model: String = base.model, cfa: IntArray = base.cfa, strips: List<ByteArray> = base.strips, makerNote: (TiffBuilder.Ifd) -> Unit = base.makerNote) =
            build(Spec(base.name, model, base.width, base.height, base.bps, base.compression, strips, base.rowsPerStrip, base.le, cfa, base.orientation, makerNote = makerNote))
        // not Nikon
        val other = with().also { b -> val i = String(b, Charsets.ISO_8859_1).indexOf("NIKON CORPORATION"); "CANON".toByteArray().copyInto(b, i) }
        assertNull(NefReader.read(other))
        // CYGM CFA (old Coolpix)
        assertNull(NefReader.read(with(cfa = intArrayOf(5, 3, 1, 4))))
        // High Efficiency NEF (JPEG XS codestream)
        assertNull(NefReader.read(with(model = "NIKON Z 9", strips = listOf(byteArrayOf(-1, 0x10, -1, 0x50) + ByteArray(4000)))))
        assertNull(NefReader.read(with(makerNote = { base.makerNote(it); it.short(0x0093, 13) })))
        // sRAW (YUV, 3 bytes per pixel)
        assertNull(NefReader.read(with(strips = listOf(ByteArray(base.width * base.height * 3)))))
        // unknown camera (no colour matrix)
        assertNull(NefReader.read(with(model = "NIKON Q 99")))
        // Coolpix E-series interlaced layouts
        assertNull(NefReader.read(with(model = "E5700")))
        // no linearisation tag for Huffman data
        assertNull(NefReader.read(with(makerNote = { it.rational(0x000c, 2.0, 1.5, 1.0, 1.0) })))
        // truncated Huffman data
        assertNull(NefReader.read(with(strips = listOf(base.strips[0].copyOf(base.strips[0].size / 3)))))
    }

    @Test
    fun corruptInputNeverThrows() {
        val good = build(lossless14().spec)
        val r = Random(42)
        repeat(300) {
            val b = good.copyOf()
            repeat(1 + r.nextInt(8)) { b[r.nextInt(b.size)] = r.nextInt(256).toByte() }
            NefReader.read(b)                                  // must not throw
            NefReader.read(b.copyOf(r.nextInt(b.size)))
        }
    }

    private companion object {
        val XLAT0 = IntArray(256) { NefReader.XLAT0[it].toInt() and 0xff }
        val XLAT1 = IntArray(256) { NefReader.XLAT1[it].toInt() and 0xff }

        /** [Pack.pack] without its trailing slack, so strip byte counts are exact. */
        fun pack(samples: IntArray, bits: Int, order: Unpack.BitOrder, rowWidth: Int, rowBytes: Int) =
            Pack.pack(samples, bits, order, rowWidth, rowBytes).copyOf(samples.size / rowWidth * rowBytes)
    }
}
