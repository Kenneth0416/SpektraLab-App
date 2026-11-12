package app.spektralab.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How an imported file is recognised as a camera raw from its MIME type (the extension and magic bytes are the other two signals). */
class RawImportDetectionTest {
    @Test fun vendorRawTypesAreRaw() {
        for (t in listOf("image/x-adobe-dng", "image/dng", "image/x-canon-cr2", "image/x-canon-cr3", "image/x-nikon-nef",
                "image/x-nikon-nrw", "image/x-sony-arw", "image/x-sony-sr2", "image/x-olympus-orf", "image/x-panasonic-rw2",
                "image/x-panasonic-raw", "image/x-fuji-raf", "image/x-pentax-pef", "image/x-samsung-srw", "IMAGE/X-SONY-ARW"))
            assertTrue(t, Media.isRawMime(t))
    }

    @Test fun otherTypesAreNot() {
        for (t in listOf("", "image/jpeg", "image/heic", "image/png", "image/tiff", "application/octet-stream", "video/mp4",
                "image/x-icon", "image/x-portable-pixmap"))
            assertFalse(t, Media.isRawMime(t))
    }

    @Test fun thePickerDoesNotHideRawsWithAnUnknownType() {
        assertTrue("application/octet-stream" in Media.PICKER_TYPES)
        assertTrue("image/*" in Media.PICKER_TYPES && "video/*" in Media.PICKER_TYPES)
    }
}
