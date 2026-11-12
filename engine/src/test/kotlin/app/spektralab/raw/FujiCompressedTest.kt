package app.spektralab.raw

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Encoder for the Fujifilm lossless-compressed RAF stream, built on the decoder's own coding
 * order ([FujiCompressed.Block]) so it emits exactly the codes the decoder consumes. Samples the
 * format interpolates instead of coding come out as the interpolation; [encode] returns the
 * mosaic a decoder must produce. LibRaw 0.22.2 decodes these streams to the same mosaic
 * (constants in [FujiCompressedTest]).
 */
internal object FujiCompressedEncoder {
    private class Enc(p: FujiCompressed.Params) : FujiCompressed.Block(p) {
        val targets = IntArray(lines.size) { -1 }
        val bits = MsbBitWriter()

        override fun nextCode(slot: Int, pred: Int, negative: Boolean, decBits: Int): Int {
            val t = targets[slot].let { if (it < 0) pred.coerceIn(0, p.maxValue) else it }
            var d = if (negative) pred - t else t - pred
            val tv = p.totalValues
            if (d >= tv / 2) d -= tv else if (d < -tv / 2) d += tv      // code = 2|d| (- 1) stays below tv
            val code = if (d >= 0) 2 * d else -2 * d - 1
            val threshold = p.maxBits - p.rawBits - 1
            val sample = code shr decBits
            if (sample < threshold) {
                zeros(sample); bits.put(1, 1)
                if (decBits > 0) bits.put(code and ((1 shl decBits) - 1), decBits)
            } else {
                zeros(threshold); bits.put(1, 1)
                bits.put(code - 1, p.rawBits)
            }
            return code
        }

        private fun zeros(n: Int) { var k = n; while (k > 0) { val s = minOf(k, 16); bits.put(0, s); k -= s } }
    }

    class Encoded(val stream: ByteArray, val decoded: IntArray)

    /** [mosaic] is width x height in raw coordinates, [cfa] anchored at raw (0, 0). */
    fun encode(mosaic: IntArray, width: Int, height: Int, bits: Int, cfa: CfaPattern): Encoded {
        val xtrans = cfa.width == 6
        val blockSize = 0x300
        val blocks = (width + blockSize - 1) / blockSize
        val header = FujiCompressed.Header(true, if (xtrans) 16 else 0, bits, height, blocks * blockSize, width, blockSize, blocks, height / 6)
        val p = FujiCompressed.Params(header)
        val colors = IntArray(36) { cfa.color(it / 6, it % 6) }
        val decoded = IntArray(width * height)
        val out = ShortArray(width * height)
        val streams = ArrayList<ByteArray>()
        for (b in 0 until blocks) {
            val enc = Enc(p)
            val x0 = b * blockSize
            val w = if (b + 1 == blocks) width - x0 else blockSize
            for (line in 0 until height / 6) {
                enc.targets.fill(-1)
                for (row in 0 until 6) for (col in 0 until w)
                    enc.targets[enc.slot(row, col, colors[row * 6 + col % 6])] = mosaic[(6 * line + row) * width + x0 + col]
                enc.line()
                enc.copyLine(colors, out, width, line, x0, w)
            }
            streams += enc.bits.bytes()
        }
        for (i in decoded.indices) decoded[i] = out[i].toInt() and 0xffff
        return Encoded(container(header, streams), decoded)
    }

    /** Header, big-endian block sizes padded to 16 bytes, then the blocks. */
    fun container(h: FujiCompressed.Header, blocks: List<ByteArray>): ByteArray {
        val o = ByteArrayOutputStream()
        val head = ByteBuffer.allocate(16).put(0x49).put(0x53).put(if (h.lossless) 1 else 0).put(h.rawType.toByte()).put(h.bits.toByte())
            .putShort(h.height.toShort()).putShort(h.roundedWidth.toShort()).putShort(h.width.toShort())
            .putShort(h.blockSize.toShort()).put(h.blocksInRow.toByte()).putShort(h.totalLines.toShort())
        o.write(head.array())
        val sizes = ByteBuffer.allocate((4 * blocks.size + 15) and 15.inv())
        blocks.forEach { sizes.putInt(it.size) }
        o.write(sizes.array())
        blocks.forEach { o.write(it) }
        return o.toByteArray()
    }
}

class FujiCompressedTest {
    /** The X-T2 tile one row down: the phase X-Trans compressed streams are coded for. */
    private val xt2c = IntArray(36) { RafBuilder.XT2[((it / 6 + 1) % 6) * 6 + it % 6] }

    private val w = 1512           // two strips: 768 + 744
    private val h = 48

    private fun scene(seed: Int, max: Int): IntArray {
        val rnd = Random(seed)
        return IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val base = 1023 + (x * 7 + y * 31) % 4000 + (if ((x / 40 + y / 12) % 2 == 0) 3000 else 0)
            (base + rnd.nextInt(-200, 201) + (if (rnd.nextInt(500) == 0) 9000 else 0)).coerceIn(0, max)
        }
    }

    private fun crc(data: IntArray): Long {
        val b = ByteArray(data.size * 2)
        for (i in data.indices) { b[2 * i] = data[i].toByte(); b[2 * i + 1] = (data[i] shr 8).toByte() }
        return CRC32().apply { update(b) }.value
    }

    private fun rafWith(stream: ByteArray, model: String, xtrans: Boolean, bits: Int, mosaic: IntArray): ByteArray {
        // the raw-section builder writes 16-bit words; swap in the compressed stream
        val b = RafBuilder(w, h, mosaic, model = model, xtrans = if (xtrans) xt2c else null, bps = bits,
            black = if (xtrans) IntArray(36) { 1023 } else IntArray(4) { 1023 }, visible = 1500 to 48)
        return b.buildWithPixels(stream)
    }

    @Test
    fun xTransRoundTrip() {
        val m = scene(1, 16383)
        val cfa = CfaPattern(6, 6, xt2c)
        val e = FujiCompressedEncoder.encode(m, w, h, 14, cfa)
        val r = assertNotNull(FujiCompressed.decode(e.stream, 0, e.stream.size, cfa))
        assertEquals(w, r.width); assertEquals(h, r.height)
        assertContentEquals(e.decoded, IntArray(w * h) { r.data[it].toInt() and 0xffff })
        assertContentEquals(m, e.decoded)                // every pixel is coded at this phase
        println("X-Trans compressed: stream ${e.stream.size} bytes for ${w * h} px")
        // at the uncompressed files' phase a sixth of the pixels would come from interpolated slots: refused
        assertTrue(FujiCompressed.allCoded(xt2c))
        assertTrue(!FujiCompressed.allCoded(RafBuilder.XT2))
        assertNull(FujiCompressed.decode(e.stream, 0, e.stream.size, CfaPattern(6, 6, RafBuilder.XT2)))
    }

    @Test
    fun bayerRoundTrip() {
        val m = scene(2, 16383)
        val e = FujiCompressedEncoder.encode(m, w, h, 14, CfaPattern.RGGB)
        val r = assertNotNull(FujiCompressed.decode(e.stream, 0, e.stream.size, CfaPattern.RGGB))
        assertContentEquals(e.decoded, IntArray(w * h) { r.data[it].toInt() and 0xffff })
        assertContentEquals(m, e.decoded)        // Bayer streams code every pixel
    }

    @Test
    fun readerDecodesCompressedRaf() {
        val m = scene(3, 16383)
        val e = FujiCompressedEncoder.encode(m, w, h, 14, CfaPattern(6, 6, xt2c))
        val img = assertNotNull(RawFormatCheck.read("RAF", rafWith(e.stream, "X-T2", true, 14, m)))
        assertEquals(RafReader.Layout.COMPRESSED, RafReader.parse(rafWith(e.stream, "X-T2", true, 14, m))!!.layout)
        assertContentEquals(e.decoded, IntArray(w * h) { img.data[it].toInt() and 0xffff })
    }

    @Test
    fun corruptStreamsAreRejected() {
        val m = scene(4, 16383)
        val cfa = CfaPattern(6, 6, xt2c)
        val e = FujiCompressedEncoder.encode(m, w, h, 14, cfa)
        // truncated: the second strip runs out of data
        assertNull(FujiCompressed.decode(e.stream.copyOf(e.stream.size - 2000), 0, e.stream.size, cfa))
        // lossy flag, bad signature, wrong CFA kind
        assertNull(FujiCompressed.decode(e.stream.copyOf().also { it[2] = 0 }, 0, e.stream.size, cfa))
        assertNull(FujiCompressed.decode(e.stream.copyOf().also { it[0] = 0 }, 0, e.stream.size, cfa))
        assertNull(FujiCompressed.decode(e.stream, 0, e.stream.size, CfaPattern.RGGB))
        // random corruption never throws
        val rnd = Random(9)
        repeat(60) {
            val b = e.stream.copyOf()
            repeat(1 + rnd.nextInt(6)) { b[32 + rnd.nextInt(b.size - 32)] = rnd.nextInt(256).toByte() }
            FujiCompressed.decode(b, 0, b.size, cfa)
        }
    }

    /**
     * Random strips: codes the encoder never writes (long zero runs, the raw escape, odd
     * gradients). Only the Bayer one is pinned: LibRaw stops on the X-Trans one with a data error.
     */
    private fun randomStream(xtrans: Boolean, seed: Int): ByteArray {
        val rnd = Random(seed)
        val header = FujiCompressed.Header(true, if (xtrans) 16 else 0, 14, h, 2 * 0x300, w, 0x300, 2, h / 6)
        return FujiCompressedEncoder.container(header, List(2) { rnd.nextBytes(400_000) })
    }

    /**
     * Synthetic compressed RAFs decoded by LibRaw 0.22.2: with SPEKTRA_RAF_DUMP=dir the files are
     * written there and `tools/raf_synthetic_check.py dir` prints LibRaw's raw_image CRC.
     */
    @Test
    fun matchesLibRaw() {
        val dump = System.getenv("SPEKTRA_RAF_DUMP")?.takeIf { it.isNotEmpty() }
        val xt = CfaPattern(6, 6, xt2c)
        val cases = LinkedHashMap<String, Pair<ByteArray, Long>>()
        val m1 = scene(5, 16383)
        val e1 = FujiCompressedEncoder.encode(m1, w, h, 14, xt)
        cases["compressedXTrans"] = rafWith(e1.stream, "X-T2", true, 14, m1) to crc(e1.decoded)
        val m2 = scene(6, 16383)
        val e2 = FujiCompressedEncoder.encode(m2, w, h, 14, CfaPattern.RGGB)
        cases["compressedBayer"] = rafWith(e2.stream, "GFX 50S", false, 14, m2) to crc(e2.decoded)
        val m3 = IntArray(w * h) { (it * 13) % 4096 }
        val e3 = FujiCompressedEncoder.encode(m3, w, h, 12, xt)
        cases["compressedXTrans12"] = rafWith(e3.stream, "X-T2", true, 12, m3) to crc(e3.decoded)
        val m4 = IntArray(w * h) { (scene(8, 16383)[it] * 4 + it % 3) }
        val e4 = FujiCompressedEncoder.encode(m4, w, h, 16, CfaPattern.RGGB)
        cases["compressedBayer16"] = rafWith(e4.stream, "GFX 100", false, 16, m4) to crc(e4.decoded)
        val random = listOf("randomBayer" to false).associate { (name, xtrans) -> name to (xtrans to randomStream(xtrans, name.length)) }
        dump?.let { dir ->
            for ((name, pair) in cases) File(dir, "$name.RAF").writeBytes(pair.first)
            for ((name, r) in random) File(dir, "$name.RAF").writeBytes(rafWith(r.second, if (r.first) "X-T2" else "GFX 50S", r.first, 14, IntArray(w * h)))
        }
        for ((name, pair) in cases) {
            assertEquals(LIBRAW[name], pair.second, "$name: LibRaw raw_image CRC")
            val img = assertNotNull(RawFormatCheck.read("RAF", pair.first), name)
            assertEquals(pair.second, crc(IntArray(w * h) { img.data[it].toInt() and 0xffff }), name)
        }
        // random streams, decoded without the strictness check (LibRaw keeps going on bad codes)
        for ((name, r) in random) {
            val (xtrans, s) = r
            val cfa = if (xtrans) xt else CfaPattern.RGGB
            val d = assertNotNull(FujiCompressed.decode(s, 0, s.size, cfa, strict = false), name)
            assertEquals(LIBRAW[name], crc(IntArray(w * h) { d.data[it].toInt() and 0xffff }), "$name: LibRaw raw_image CRC")
        }
    }

    /** Opt-in (SPEKTRA_RAF_BENCH=1): decode time of a full-size (6048 x 4032) X-Trans stream. */
    @Test
    fun fullSizeDecodeTime() {
        org.junit.Assume.assumeTrue(System.getenv("SPEKTRA_RAF_BENCH") == "1")
        val fw = 6048; val fh = 4032
        val rnd = Random(1)
        val m = IntArray(fw * fh) { i -> (1023 + (i % fw) % 3000 + (i / fw) % 2000 + rnd.nextInt(-300, 301)).coerceIn(0, 16383) }
        val cfa = CfaPattern(6, 6, xt2c)
        val e = FujiCompressedEncoder.encode(m, fw, fh, 14, cfa)
        repeat(3) {
            val t0 = System.nanoTime()
            val r = assertNotNull(FujiCompressed.decode(e.stream, 0, e.stream.size, cfa))
            println("compressed 6048x4032 decode: ${(System.nanoTime() - t0) / 1_000_000} ms (${e.stream.size / 1_000_000} MB)")
            if (it == 0) assertContentEquals(m, IntArray(fw * fh) { i -> r.data[i].toInt() and 0xffff })
        }
    }

    companion object {
        /** LibRaw 0.22.2 raw_image CRC32 (little-endian uint16) of the files written by [matchesLibRaw]. */
        val LIBRAW: Map<String, Long> = mapOf(
            "compressedXTrans" to 0x94fe76e0L,
            "compressedBayer" to 0x8c3e701dL,
            "compressedXTrans12" to 0x9eae6a52L,
            "compressedBayer16" to 0x875aae1bL,
            "randomBayer" to 0x065bcf0dL,
        )
    }
}
