package app.spektralab.raw

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TiffTest {
    private fun noteIfd(): TiffBuilder.Ifd = TiffBuilder.Ifd()
        .short(1, 7)
        .ascii(2, "maker note text that is out of line")
        .long(3, 10, 20, 30)

    private fun file(le: Boolean, makerNote: ByteArray): ByteArray {
        val exif = TiffBuilder.Ifd().rational(33434, 1.0 / 250).undefined(TiffTags.MAKER_NOTE, makerNote)
        val sub = TiffBuilder.Ifd().long(TiffTags.IMAGE_WIDTH, 6000).short(TiffTags.PHOTOMETRIC, 32803)
        val ifd1 = TiffBuilder.Ifd().long(TiffTags.IMAGE_WIDTH, 160)
        val ifd0 = TiffBuilder.Ifd()
            .ascii(TiffTags.MAKE, "Testmaker").ascii(TiffTags.MODEL, "Model 1")
            .short(TiffTags.ORIENTATION, 6)
            .srational(50721, 0.5, -0.25, 0.125, -1.0, 2.0, 0.0, 0.0, 0.0, 1.0)
            .double(12345, 3.25).float(12346, 1.5)
            .subIfds(TiffTags.SUB_IFDS, sub)
            .subIfds(TiffTags.EXIF_IFD, exif)
        ifd0.next = ifd1
        return TiffBuilder(le).build(ifd0)
    }

    private fun checkNote(note: MakerNote?, kind: String) {
        assertNotNull(note, kind)
        assertEquals(kind, note.kind)
        assertEquals(7, note.ifd.int(1))
        assertEquals("maker note text that is out of line", note.ifd.string(2))
        assertContentEquals(intArrayOf(10, 20, 30), note.ifd.ints(3))
    }

    @Test
    fun bothByteOrdersChainsSubIfdsAndExif() {
        for (le in listOf(true, false)) {
            val t = assertNotNull(Tiff.parse(file(le, ByteArray(16))))
            assertEquals(le, t.littleEndian); assertEquals(42, t.magic)
            assertEquals(2, t.ifds.size)
            assertEquals(160, t.ifds[1].int(TiffTags.IMAGE_WIDTH))
            assertEquals("Testmaker", t.ifd0.string(TiffTags.MAKE)); assertEquals("Model 1", t.ifd0.string(TiffTags.MODEL))
            assertEquals(6, t.ifd0.int(TiffTags.ORIENTATION))
            assertContentEquals(doubleArrayOf(0.5, -0.25, 0.125, -1.0, 2.0, 0.0, 0.0, 0.0, 1.0), t.ifd0.doubles(50721))
            assertEquals(3.25, t.ifd0.double(12345)); assertEquals(1.5, t.ifd0.double(12346))
            val all = t.allIfds()
            assertEquals(3, all.size)
            assertEquals(6000, all.first { it.int(TiffTags.PHOTOMETRIC) == 32803 }.int(TiffTags.IMAGE_WIDTH))
            assertEquals(0.004, assertNotNull(t.exif).double(33434))
        }
    }

    @Test
    fun makerNoteVariants() {
        for (le in listOf(true, false)) {
            // Nikon type 3: "Nikon\0" 02 10 00 00, then a TIFF with its own (here opposite) byte order
            val nikon = "Nikon\u0000".toByteArray() + byteArrayOf(2, 0x10, 0, 0) + TiffBuilder(!le).build(noteIfd())
            checkNote(Tiff.parse(file(le, nikon))!!.makerNote(), "nikon")
            // Fuji: always little endian, IFD offset at +8, offsets relative to the note
            val fuji = TiffBuilder(true).build(noteIfd(), "FUJIFILM".toByteArray() + byteArrayOf(12, 0, 0, 0))
            checkNote(Tiff.parse(file(le, fuji))!!.makerNote(), "fuji")
            // new Olympus: header with byte order, offsets relative to the note
            val oly = TiffBuilder(!le).build(noteIfd(), "OLYMPUS\u0000".toByteArray() + (if (!le) "II" else "MM").toByteArray() + byteArrayOf(3, 0))
            checkNote(Tiff.parse(file(le, oly))!!.makerNote(), "olympus")
            // Pentax "AOC\0" + byte order, main-file offsets: only inline values here
            val pentaxIfd = TiffBuilder.Ifd().short(1, 7).short(5, 1, 2)
            val pentax = TiffBuilder(!le).build(pentaxIfd, "AOC\u0000".toByteArray() + (if (!le) "II" else "MM").toByteArray())
            val pn = assertNotNull(Tiff.parse(file(le, pentax))!!.makerNote())
            assertEquals("pentax", pn.kind); assertContentEquals(intArrayOf(1, 2), pn.ifd.ints(5))
            // Canon-style bare IFD in the file's byte order
            val plain = TiffBuilder(le).build(pentaxIfd, ByteArray(0))
            val cn = assertNotNull(Tiff.parse(file(le, plain))!!.makerNote())
            assertEquals("plain", cn.kind); assertEquals(7, cn.ifd.int(1))
        }
    }

    @Test
    fun otherMagicAndOffsetHeaders() {
        val rw2 = file(true, ByteArray(8)).also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(2, 0x55) }
        assertEquals(0x55, Tiff.parse(rw2)!!.magic)
        assertNull(Tiff.parse(rw2, magic = setOf(42)))
        // a TIFF embedded at an offset (CR3 CMT boxes, RAF) uses offsets relative to its header
        val embedded = ByteArray(77) + file(false, ByteArray(8))
        val t = assertNotNull(Tiff.parse(embedded, 77))
        assertEquals(77, t.base)
        assertEquals("Testmaker", t.ifd0.string(TiffTags.MAKE))
    }

    @Test
    fun hostileInput() {
        // IFD that points to itself, and an entry with an absurd count
        val b = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        b.put("II".toByteArray()); b.putShort(42); b.putInt(8)
        b.putShort(2)
        b.putShort(273); b.putShort(4); b.putInt(0x7fffffff); b.putInt(30)
        b.putShort(330); b.putShort(4); b.putInt(1); b.putInt(8)        // SubIFD = itself
        b.putInt(8)                                                      // next = itself
        val t = assertNotNull(Tiff.parse(b.array()))
        assertEquals(1, t.ifds.size)
        val e = t.ifd0[273]!!
        assertTrue(e.count <= (64 - 30) / 4)
        assertTrue(t.allIfds().size <= 1)

        val rnd = Random(11)
        val good = file(true, "Nikon\u0000".toByteArray() + byteArrayOf(2, 0x10, 0, 0) + TiffBuilder(false).build(noteIfd()))
        repeat(5000) {
            val bad = good.copyOf(if (rnd.nextInt(3) == 0) rnd.nextInt(good.size) else good.size)
            repeat(1 + rnd.nextInt(6)) { if (bad.isNotEmpty()) bad[rnd.nextInt(bad.size)] = rnd.nextInt(256).toByte() }
            // none of this may throw
            val tiff = Tiff.parse(bad) ?: return@repeat
            for (ifd in tiff.allIfds() + listOfNotNull(tiff.exif, tiff.makerNote()?.ifd)) {
                for (tag in ifd.entries.keys) {
                    ifd.doubles(tag); ifd.ints(tag); ifd.longs(tag); ifd.string(tag); ifd.bytes(tag); ifd.subIfds(tag)
                }
            }
        }
    }
}
