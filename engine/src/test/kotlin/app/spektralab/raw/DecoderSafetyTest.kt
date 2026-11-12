package app.spektralab.raw

import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

class DecoderSafetyTest {
    private fun dng(tiled: Boolean = false, bits: Int = 16, le: Boolean = true): TiffBuilder.Ifd {
        val i = TiffBuilder.Ifd().byte(50706, 1, 4, 0, 0).long(256, 4).long(257, 4)
            .short(258, bits).short(259, 1).short(262, 32803).short(277, 1)
            .short(33421, 2, 2).byte(33422, 0, 1, 1, 2)
            .srational(50721, *Mat3.IDENTITY).rational(50728, 0.5, 1.0, 0.75)
        val values = IntArray(16) { 100 + it }
        val pixels = if (bits == 16) ByteBuffer.allocate(32)
            .order(if (le) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
            .apply { values.forEach { putShort(it.toShort()) } }.array()
        else Pack.pack(values, bits, Unpack.BitOrder.MSB, 4, Unpack.rowBytes(4, bits).toInt())
        if (tiled) i.long(322, 4).long(323, 4).blobs(324, 325, listOf(pixels))
        else i.long(278, 4).blobs(273, 279, listOf(pixels))
        return i
    }

    @Test fun rejectsMissingOrTruncatedSegmentCounts() {
        for (tiled in listOf(false, true)) for (bits in listOf(8, 12, 14, 16)) for (le in listOf(false, true)) {
            val counts = if (tiled) 325 else 279
            val fullSize = (Unpack.rowBytes(4, bits) * 4).toInt()
            val valid = DngReader.read(TiffBuilder(le).build(dng(tiled, bits, le)))
            assertNotNull(valid)
            assertContentEquals(IntArray(16) { 100 + it }, valid.data.map { it.toInt() and 65535 }.toIntArray())
            for (length in listOf(0, 1, fullSize - 1, Int.MAX_VALUE)) {
                val bad = dng(tiled, bits, le).long(counts, length)
                assertNull(DngReader.read(TiffBuilder(le).build(bad)), "tiled=$tiled bits=$bits le=$le bytes=$length")
            }
            val missing = dng(tiled, bits, le).apply { tags.remove(counts) }
            assertNull(DngReader.read(TiffBuilder(le).build(missing)))
        }
    }

    @Test fun rejectsTileArithmeticOverflowAndZeroStrips() {
        for ((width, height) in listOf(Int.MAX_VALUE to 4, 4 to Int.MAX_VALUE, 0 to 4, 4 to 0)) {
            val bad = dng(tiled = true).long(322, width).long(323, height)
            assertNull(DngReader.read(TiffBuilder().build(bad)))
        }
        assertNull(DngReader.read(TiffBuilder().build(dng().long(278, 0))))
        // TIFF's unsigned all-rows sentinel is valid, not a negative strip size.
        assertNotNull(DngReader.read(TiffBuilder().build(dng().long(278, -1))))
    }

    @Test fun cyclicAndDuplicateSubIfdsAreVisitedOnce() {
        val a = TiffBuilder.Ifd().long(256, 4)
        val b = TiffBuilder.Ifd().long(256, 4)
        a.subIfds(330, *Array(64) { b })
        b.subIfds(330, *Array(64) { a })
        val all = Tiff.parse(TiffBuilder().build(a))!!.allIfds()
        assertEquals(2, all.size)
        assertEquals(2, all.map { it.offset }.distinct().size)
    }

    @Test fun rejectsOverBudgetDirectoryGraph() {
        val root = TiffBuilder.Ifd().long(256, 4)
        val children = Array(8) {
            TiffBuilder.Ifd().long(256, 4).subIfds(330, *Array(40) { TiffBuilder.Ifd().long(256, 4) })
        }
        root.subIfds(330, *children)
        assertTrue(Tiff.parse(TiffBuilder().build(root))!!.allIfds().isEmpty())
        val huge = TiffBuilder.Ifd().apply { repeat(4097) { long(it + 1, 1) } }
        assertNull(Tiff.parse(TiffBuilder().build(huge)))
    }

    @Test fun rejectsTruncatedJpegEntropyAndMissingEoi() {
        val jpg = LjpegEncoder.encode(IntArray(16) { 100 + it }, 4, 4, 1, 12, 1)
        val h = LosslessJpeg.readHeader(jpg)!!
        val eoi = byteArrayOf(0xff.toByte(), 0xd9.toByte())
        assertNull(LosslessJpeg.decode(jpg.copyOf(h.scanStart) + eoi))
        assertNull(LosslessJpeg.decode(jpg.copyOf(h.scanStart + 1) + eoi))
        assertNull(LosslessJpeg.decode(jpg.copyOf(jpg.size - 2)))
        assertNull(LosslessJpeg.decode(jpg.copyOf(jpg.size - 1)))
        assertNotNull(LosslessJpeg.decode(jpg))
        // One short code in the final byte is valid even though a 32-bit peek sees EOI.
        val one = LjpegEncoder.encode(intArrayOf(2048), 1, 1, 1, 12, 1)
        assertEquals(2048, LosslessJpeg.decode(one)!!.samples.single().toInt())
    }

    @Test fun rejectsTruncatedCompressedDng() {
        val jpg = LjpegEncoder.encode(IntArray(16) { 100 + it }, 4, 4, 1, 12, 1)
        val h = LosslessJpeg.readHeader(jpg)!!
        val truncated = jpg.copyOf(h.scanStart) + byteArrayOf(0xff.toByte(), 0xd9.toByte())
        for (payload in listOf(jpg, truncated)) {
            val i = dng().short(258, 12).short(259, 7).blobs(273, 279, listOf(payload))
            val result = DngReader.read(TiffBuilder().build(i))
            if (payload === jpg) assertNotNull(result) else assertNull(result)
        }
    }

    @Test fun lookAheadPaddingIsNotConsumptionAndResetClearsIt() {
        val pump = MsbBitPump(byteArrayOf(0x80.toByte(), 0xff.toByte(), 0xd9.toByte()), jpegStuffing = true)
        pump.peek(32)
        assertTrue(pump.overrun)
        assertFalse(pump.consumedPadding)
        assertEquals(128, pump.bits(8))
        assertFalse(pump.consumedPadding)
        pump.bits(1)
        assertTrue(pump.consumedPadding)
        pump.reset(0)
        assertEquals(128, pump.bits(8))
        assertFalse(pump.consumedPadding)
        // A terminal FF without its stuffing byte is not a valid entropy byte.
        val unfinishedStuffing = MsbBitPump(byteArrayOf(0xff.toByte()), jpegStuffing = true)
        unfinishedStuffing.bits(1)
        assertTrue(unfinishedStuffing.consumedPadding)
    }

    @Test fun jpegRestartsRequireTheExpectedSequence() {
        val samples = IntArray(64) { 100 + it }
        val jpg = LjpegEncoder.encode(samples, 8, 8, 1, 12, 1, restart = 8)
        assertNotNull(LosslessJpeg.decode(jpg))
        val marker = (0 until jpg.size - 1).first { jpg[it] == 0xff.toByte() && jpg[it + 1] == 0xd0.toByte() }
        val bad = jpg.copyOf().apply { this[marker + 1] = 0xd3.toByte() }
        assertNull(LosslessJpeg.decode(bad))
    }

    @Test fun dimensionsAndSegmentsAreRejectedBeforeLargeAllocation() {
        val cases = listOf(
            dng().long(256, 8001).long(257, 8000), // over the sample budget
            dng().long(256, 8000).long(257, 8000).long(278, 8000), // in budget but payload is tiny
            dng().long(256, 1).long(257, 20000).long(278, 1), // over the segment budget
        ).map { TiffBuilder().build(it) }
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        for (bytes in cases) {
            val before = bean?.takeIf { it.isThreadAllocatedMemorySupported && it.isThreadAllocatedMemoryEnabled }
                ?.getThreadAllocatedBytes(Thread.currentThread().id)
            assertNull(DngReader.read(bytes))
            if (before != null) {
                val allocated = bean.getThreadAllocatedBytes(Thread.currentThread().id) - before
                assertTrue(allocated < 4_000_000, "allocated $allocated bytes for an invalid tiny DNG")
            }
        }
    }

    @Test fun jpegHeaderEnforcesSampleBudgetEvenWithEnoughInputBytes() {
        val jpg = LjpegEncoder.encode(intArrayOf(2048), 1, 1, 1, 12, 1)
        val huge = jpg.copyOf(8_100_000) // enough bits to pass the old header heuristic
        val sof = (0 until jpg.size - 1).first { jpg[it] == 0xff.toByte() && jpg[it + 1] == 0xc3.toByte() }
        fun u16(at: Int, value: Int) { huge[at] = (value ushr 8).toByte(); huge[at + 1] = value.toByte() }
        u16(sof + 5, 8000); u16(sof + 7, 8001)
        assertNull(LosslessJpeg.readHeader(huge))
    }

    @Test fun neutralScaleDoesNotChangeExposureWithEitherMatrixPath() {
        val neutral = doubleArrayOf(0.5, 1.0, 0.75)
        val cc = doubleArrayOf(1.05, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.97)
        val ab = doubleArrayOf(1.1, 1.0, 0.9)
        val fm = doubleArrayOf(RawColor.D50[0], 0.0, 0.0, 0.0, RawColor.D50[1], 0.0, 0.0, 0.0, RawColor.D50[2])
        for (forward in listOf(null, fm)) {
            val expected = RawColor.fromDng(Mat3.IDENTITY, forward, neutral, cc, ab)!!
            for (scale in listOf(0.25, 2.0, 1024.0)) {
                val actual = RawColor.fromDng(Mat3.IDENTITY, forward, DoubleArray(3) { neutral[it] * scale }, cc, ab)!!
                assertContentEquals(expected.wb, actual.wb)
                for (i in 0..8) assertEquals(expected.camToSrgb[i], actual.camToSrgb[i], 1e-5f)
                // A grey whose green sensor value is .18 must stay at .18 in every channel.
                for (c in 0..2) {
                    val rgb = (0..2).sumOf { j -> actual.camToSrgb[c * 3 + j] * neutral[j] * .18 * actual.wb[j] }
                    assertEquals(.18, rgb, 1e-5)
                }
            }
        }
    }
}
