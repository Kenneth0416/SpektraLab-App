package app.spektralab.raw

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LosslessJpegTest {
    /** Smooth image plus noise, with occasional large jumps so every category occurs. */
    private fun image(w: Int, h: Int, nc: Int, precision: Int, rnd: Random): IntArray {
        val max = (1 shl precision) - 1
        return IntArray(w * h * nc) { i ->
            val px = i / nc; val x = px % w; val y = px / w; val c = i % nc
            val smooth = (max * (0.2 + 0.5 * x / w + 0.2 * y / h + 0.05 * c)).toInt()
            val v = when (rnd.nextInt(20)) { 0 -> rnd.nextInt(max + 1); 1 -> if (rnd.nextBoolean()) 0 else max; else -> smooth + rnd.nextInt(-3, 4) }
            v.coerceIn(0, max)
        }
    }

    private fun roundTrip(w: Int, h: Int, nc: Int, precision: Int, predictor: Int, pt: Int = 0, restart: Int = 0, seed: Int = 1) {
        val rnd = Random(seed)
        val src = image(w, h, nc, precision, rnd).map { (it ushr pt) shl pt }.toIntArray()
        val jpg = LjpegEncoder.encode(src, w, h, nc, precision, predictor, pt, restart)
        val frame = assertNotNull(LosslessJpeg.decode(jpg), "decode p=$predictor nc=$nc bits=$precision rst=$restart")
        assertEquals(w, frame.header.width); assertEquals(h, frame.header.height); assertEquals(nc, frame.header.components)
        assertEquals(predictor, frame.header.predictor)
        assertContentEquals(src, IntArray(frame.samples.size) { frame.samples[it].toInt() and 0xffff },
            "p=$predictor nc=$nc bits=$precision pt=$pt rst=$restart")
    }

    @Test
    fun allPredictorsAndComponentCounts() {
        for (p in 1..7) for (nc in 1..4) roundTrip(23, 17, nc, 12, p, seed = p * 10 + nc)
    }

    @Test
    fun precisions() {
        for (bits in 2..16) for (p in intArrayOf(1, 4, 7)) roundTrip(19, 11, 2, bits, p, seed = bits)
    }

    @Test
    fun pointTransform() {
        roundTrip(16, 8, 1, 14, 1, pt = 2)
        roundTrip(16, 8, 3, 16, 6, pt = 4)
    }

    @Test
    fun restartIntervals() {
        // whole rows (what DNG writers emit), several rows, and intervals that start mid-row
        for (p in intArrayOf(1, 2, 5, 7)) {
            roundTrip(20, 12, 2, 14, p, restart = 20)
            roundTrip(20, 12, 2, 14, p, restart = 60)
            roundTrip(20, 12, 1, 14, p, restart = 7)
            roundTrip(20, 12, 4, 16, p, restart = 1)
        }
    }

    @Test
    fun category16() {
        // jumps of exactly 32768 need the special 16-bit category
        val w = 8; val h = 4
        val src = IntArray(w * h) { if ((it / 2) % 2 == 0) 0 else 32768 }
        val jpg = LjpegEncoder.encode(src, w, h, 1, 16, 1)
        assertContentEquals(src, LosslessJpeg.decode(jpg)!!.samples.map { it.toInt() and 0xffff }.toIntArray())
    }

    @Test
    fun rowSinkAndHeader() {
        val src = image(10, 6, 2, 12, Random(3))
        val jpg = LjpegEncoder.encode(src, 10, 6, 2, 12, 1)
        val h = assertNotNull(LosslessJpeg.readHeader(jpg))
        assertEquals(20, h.rowSamples); assertEquals(12, h.precision)
        val rows = ArrayList<Int>()
        // embedded at an offset inside a larger buffer, as in a raw file
        val file = ByteArray(100) + jpg + ByteArray(50)
        assertNotNull(LosslessJpeg.decode(file, 100, jpg.size) { y, s ->
            rows += y
            for (i in 0 until 20) assertEquals(src[y * 20 + i], s[i])
        })
        assertEquals((0 until 6).toList(), rows)
    }

    @Test
    fun rejectsOtherJpegsAndSurvivesCorruption() {
        assertNull(LosslessJpeg.decode(ByteArray(0)))
        assertNull(LosslessJpeg.decode(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xC0.toByte(), 0, 2)))
        val jpg = LjpegEncoder.encode(image(32, 32, 2, 12, Random(5)), 32, 32, 2, 12, 1, restart = 32)
        val rnd = Random(9)
        repeat(3000) {
            val bad = jpg.copyOf(if (rnd.nextInt(4) == 0) rnd.nextInt(jpg.size) else jpg.size)
            repeat(1 + rnd.nextInt(4)) { if (bad.isNotEmpty()) bad[rnd.nextInt(bad.size)] = rnd.nextInt(256).toByte() }
            LosslessJpeg.decode(bad)                 // must not throw
            LosslessJpeg.decode(bad, 0, bad.size) { _, _ -> }
        }
        // a header claiming far more samples than the data can hold is refused up front
        val huge = jpg.copyOf()
        val sof = (0 until huge.size - 1).first { huge[it] == 0xFF.toByte() && huge[it + 1] == 0xC3.toByte() }
        huge[sof + 5] = 0xFF.toByte(); huge[sof + 6] = 0xFF.toByte(); huge[sof + 7] = 0xFF.toByte(); huge[sof + 8] = 0xFF.toByte()
        assertNull(LosslessJpeg.readHeader(huge))
    }

    @Test
    fun huffmanTableForMakerFormats() {
        // Nikon-style dcraw table: 16 counts then symbols
        val t = assertNotNull(HuffmanTable.fromDcraw(intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 5, 4, 3, 6, 2, 7, 1, 0, 8, 9, 11, 10, 12)))
        // canonical codes: 5 -> 00, 4 -> 010, 3 -> 011, ...
        val w = MsbBitWriter(); w.put(0b00, 2); w.put(0b010, 3); w.put(0b011, 3); w.put(0b10101, 5); w.put(0b11, 2)
        val p = MsbBitPump(w.bytes())
        assertEquals(5, t.decodeSymbol(p)); assertEquals(4, t.decodeSymbol(p)); assertEquals(3, t.decodeSymbol(p))
        // diff: category 5 then bits 10101 = 21
        val w2 = MsbBitWriter(); w2.put(0b00, 2); w2.put(0b10101, 5); w2.put(0b00, 2); w2.put(0b01010, 5)
        val p2 = MsbBitPump(w2.bytes())
        assertEquals(21, t.decodeDiff(p2)); assertEquals(-21, t.decodeDiff(p2))
        // over-subscribed
        assertNull(HuffmanTable.fromJpeg(IntArray(16).also { it[0] = 3 }, intArrayOf(1, 2, 3)))
    }
}
