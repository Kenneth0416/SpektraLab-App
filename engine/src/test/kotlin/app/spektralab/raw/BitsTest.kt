package app.spektralab.raw

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BitsTest {
    @Test
    fun msbPump() {
        val b = byteArrayOf(0b10110011.toByte(), 0b01010101, 0xFF.toByte(), 0x00, 0x12)
        val p = MsbBitPump(b)
        assertEquals(0b101, p.bits(3)); assertEquals(0b10011, p.bits(5)); assertEquals(0x55, p.bits(8))
        assertEquals(0xFF00, p.bits(16)); assertEquals(0x12, p.peek(8)); p.skip(8)
        assertEquals(0, p.bits(32))                                     // zeros past the end, no exception
        assertTrue(p.overrun)
    }

    @Test
    fun jpegStuffingAndMarkers() {
        val b = byteArrayOf(0xAB.toByte(), 0xFF.toByte(), 0x00, 0xCD.toByte(), 0xFF.toByte(), 0xD3.toByte(), 0x77)
        val p = MsbBitPump(b, jpegStuffing = true)
        assertEquals(0xABFFCD, p.bits(24))
        assertEquals(0, p.bits(8))                                       // stops at the marker
        assertEquals(4, p.marker)
        p.reset(6); assertEquals(0x77, p.bits(8))
    }

    @Test
    fun lsbAndWordPumps() {
        val b = byteArrayOf(0x34, 0x12, 0x78, 0x56)
        val l = LsbBitPump(b)
        assertEquals(0x4, l.bits(4)); assertEquals(0x23, l.bits(8)); assertEquals(0x1, l.bits(4)); assertEquals(0x5678, l.bits(16))
        val w16 = MsbWordBitPump(b, wordBytes = 2)
        assertEquals(0x1, w16.bits(4)); assertEquals(0x234, w16.bits(12)); assertEquals(0x5678, w16.bits(16))
        val w32 = MsbWordBitPump(b, wordBytes = 4)
        assertEquals(0x56781234, w32.bits(32))
        assertEquals(0, w32.bits(20))
    }

    @Test
    fun unpackMatchesReferencePacker() {
        val rnd = Random(4)
        for (order in Unpack.BitOrder.values()) for (bits in intArrayOf(8, 10, 12, 14, 16, 7, 1)) for (width in intArrayOf(1, 5, 32, 33)) {
            val height = 3
            val samples = IntArray(width * height) { rnd.nextInt(1 shl bits) }
            val natural = Unpack.rowBytes(width, bits).toInt()
            val rowBytes = if (order == Unpack.BitOrder.MSB32) (natural + 3) / 4 * 4 + 4 else if (order == Unpack.BitOrder.MSB16) (natural + 1) / 2 * 2 + 2 else natural + 3
            val packed = Pack.pack(samples, bits, order, width, rowBytes)
            // into a wider destination, at an offset
            val dst = ShortArray(2 + (width + 3) * height)
            assertTrue(Unpack.packed(packed, 0, rowBytes, width, height, bits, order, dst, 2, width + 3))
            val got = IntArray(width * height) { dst[2 + (it / width) * (width + 3) + it % width].toInt() and 0xffff }
            assertContentEquals(samples, got, "$order $bits bit, width $width")
        }
    }

    @Test
    fun unpackContinuousStreamAndTruncation() {
        val samples = IntArray(7 * 3) { (it * 37) and 0x3ff }
        val packed = Pack.pack(samples, 10, Unpack.BitOrder.MSB, samples.size, Unpack.rowBytes(samples.size, 10).toInt())
        val dst = ShortArray(samples.size)
        assertTrue(Unpack.packed(packed, 0, 0, 7, 3, 10, Unpack.BitOrder.MSB, dst))
        assertContentEquals(samples, dst.map { it.toInt() }.toIntArray())
        // a truncated source decodes what is there and reports it
        val short = ShortArray(64 * 4)
        val src = ByteArray(64 * 2 * 3 - 1)
        assertFalse(Unpack.u16(src, 0, 0, 64, 4, true, short))
        assertFalse(Unpack.packed(src, 0, 0, 64, 4, 12, Unpack.BitOrder.MSB, short))
        assertFalse(Unpack.packed(src, src.size + 10, 100, 64, 4, 12, Unpack.BitOrder.LSB, short))
    }

    @Test
    fun byteHelpers() {
        val b = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        assertEquals(0x0201, Bytes.u16le(b, 0)); assertEquals(0x0102, Bytes.u16be(b, 0))
        assertEquals(0x04030201L, Bytes.u32le(b, 0)); assertEquals(0x01020304L, Bytes.u32be(b, 0))
        assertEquals(0x0807060504030201L, Bytes.u64(b, 0, true))
        assertTrue(Bytes.matches("FUJIFILMCCD".toByteArray(), 0, "FUJIFILM")); assertFalse(Bytes.matches(b, 6, "abc"))
    }
}
