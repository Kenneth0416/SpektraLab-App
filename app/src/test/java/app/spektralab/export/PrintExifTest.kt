package app.spektralab.export

import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater

@RunWith(RobolectricTestRunner::class)
class PrintExifTest {
    @get:Rule val tmp = TemporaryFolder()

    /** SOI + EOI: enough of a JPEG for ExifInterface to hold tags. */
    private fun jpeg(name: String) = tmp.newFile(name).also { it.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())) }

    /** Valid minimal RGB PNG of [w] x [h] for testing ExifInterface's PNG eXIf writer. */
    private fun png(name: String, w: Int = 24, h: Int = 16): File {
        fun chunk(out: DataOutputStream, type: String, payload: ByteArray) {
            val tag = type.toByteArray(Charsets.US_ASCII)
            out.writeInt(payload.size)
            out.write(tag)
            out.write(payload)
            val crc = CRC32().apply { update(tag); update(payload) }.value.toInt()
            out.writeInt(crc)
        }
        val ihdr = ByteBuffer.allocate(13).apply {
            putInt(w); putInt(h); put(8); put(2); put(0); put(0); put(0)
        }.array()
        val raw = ByteArray(h * (1 + w * 3))
        val deflater = Deflater().apply { setInput(raw); finish() }
        val idat = ByteArrayOutputStream().also { bos ->
            val buf = ByteArray(1024)
            while (!deflater.finished()) bos.write(buf, 0, deflater.deflate(buf))
            deflater.end()
        }.toByteArray()
        val f = tmp.newFile(name)
        DataOutputStream(f.outputStream()).use { out ->
            out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            chunk(out, "IHDR", ihdr)
            chunk(out, "IDAT", idat)
            chunk(out, "IEND", ByteArray(0))
        }
        return f
    }

    private fun original(): File {
        val f = jpeg("orig.jpg")
        ExifInterface(f).apply {
            setAttribute(ExifInterface.TAG_MAKE, "Acme")
            setAttribute(ExifInterface.TAG_MODEL, "Cam 1")
            setAttribute(ExifInterface.TAG_LENS_MODEL, "50mm")
            setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "0.008")
            setAttribute(ExifInterface.TAG_F_NUMBER, "1.8")
            setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "400")
            setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "50/1")
            setAttribute(ExifInterface.TAG_FLASH, "16")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:05:06 07:08:09")
            setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, "123")
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+08:00")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "40/1,30/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "116/1,0/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
            setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "50/1")
            setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, "0")
            setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, "07:08:09")
            setAttribute(ExifInterface.TAG_GPS_DATESTAMP, "2024:05:06")
            // describes the original's pixels: must stay behind
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, "4000")
            setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, "3000")
            setAttribute(ExifInterface.TAG_COLOR_SPACE, "65535")
            setAttribute(ExifInterface.TAG_MAKER_NOTE, "secret")
            saveAttributes()
        }
        return f
    }

    private fun assertPrintMatchesOriginal(print: File) {
        val p = ExifInterface(print)
        assertEquals("Acme", p.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals("Cam 1", p.getAttribute(ExifInterface.TAG_MODEL))
        assertEquals("50mm", p.getAttribute(ExifInterface.TAG_LENS_MODEL))
        assertEquals(0.008, p.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0), 1e-6)
        assertEquals(1.8, p.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0), 1e-6)
        assertEquals(400, p.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0))
        assertEquals(50.0, p.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0), 1e-6)
        assertEquals(16, p.getAttributeInt(ExifInterface.TAG_FLASH, -1))
        assertEquals("123", p.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL))
        assertEquals("+08:00", p.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
        // the original's own date wins over the fallback
        assertEquals("2024:05:06 07:08:09", p.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals("2024:05:06 07:08:09", p.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED))
        val ll = p.latLong
        assertNotNull(ll)
        assertEquals(40.5, ll!![0], 1e-6)
        assertEquals(116.0, ll[1], 1e-6)
        assertEquals(50.0, p.getAltitude(0.0), 1e-6)
        assertEquals("2024:05:06", p.getAttribute(ExifInterface.TAG_GPS_DATESTAMP))
        assertNotNull(p.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP))

        assertEquals("Silvercam", p.getAttribute(ExifInterface.TAG_SOFTWARE))
        assertEquals("Kodak Gold", p.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
        assertEquals(ExifInterface.ORIENTATION_NORMAL, p.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertEquals(1, p.getAttributeInt(ExifInterface.TAG_COLOR_SPACE, 0))
        assertNull(p.getAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION))
        assertNull(p.getAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION))
        assertNull(p.getAttribute(ExifInterface.TAG_MAKER_NOTE))
    }

    @Test fun copiesShootingDataAndLeavesPixelTagsBehind() {
        val orig = original()
        val print = jpeg("print.jpg")
        val read = PrintExif.read(ExifInterface(orig))
        ExifInterface(print).apply {
            PrintExif.apply(this, read, "1999:01:01 00:00:00", "Kodak Gold")
            saveAttributes()
        }
        assertPrintMatchesOriginal(print)
    }

    @Test fun pngApplyAndSavePreservesExifAndDimensions() {
        val orig = original()
        val read = PrintExif.read(ExifInterface(orig))
        val pngFile = png("real.png", w = 24, h = 16)
        ExifInterface(pngFile).apply {
            PrintExif.apply(this, read, "1999:01:01 00:00:00", "Kodak Gold")
            saveAttributes()
        }
        assertPrintMatchesOriginal(pngFile)
        // verify PNG signature, IHDR dimensions (24x16), and eXIf chunk are intact
        val bytes = pngFile.readBytes()
        assertTrue(bytes.size > 33)
        assertEquals("IHDR", String(bytes, 12, 4, Charsets.US_ASCII))
        val bb = ByteBuffer.wrap(bytes, 16, 8)
        assertEquals(24, bb.int)
        assertEquals(16, bb.int)
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("eXIf"))
    }

    @Test fun fallbackDateAndNoLocationWhenOriginalHasNone() {
        val orig = jpeg("bare.jpg")
        ExifInterface(orig).apply { setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "50/1"); saveAttributes() }
        val print = jpeg("print2.jpg")
        ExifInterface(print).apply {
            PrintExif.apply(this, PrintExif.read(ExifInterface(orig)), "2020:02:03 04:05:06", "Film")
            saveAttributes()
        }
        val p = ExifInterface(print)
        assertEquals("2020:02:03 04:05:06", p.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        // an altitude without a position is dropped
        assertNull(p.getAttribute(ExifInterface.TAG_GPS_ALTITUDE))
        assertNull(p.latLong)
    }

    @Test fun writeNeverThrowsOnInvalidFileDescriptor() {
        PrintExif.write(FileDescriptor(), null, "2020:02:03 04:05:06", "Film")
    }
}
