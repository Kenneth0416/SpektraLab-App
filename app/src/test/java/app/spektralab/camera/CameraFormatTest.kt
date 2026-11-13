package app.spektralab.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFormatTest {
    @Test fun cyclesThroughJpegRawAndBurstOnRawHardware() {
        assertEquals(CameraFormat.RAW_SINGLE, CameraFormat.cycle(CameraFormat.JPEG, rawSupported = true))
        assertEquals(CameraFormat.RAW_BURST, CameraFormat.cycle(CameraFormat.RAW_SINGLE, rawSupported = true))
        assertEquals(CameraFormat.JPEG, CameraFormat.cycle(CameraFormat.RAW_BURST, rawSupported = true))
    }

    @Test fun staysOnJpegWithoutRawHardware() {
        assertEquals(CameraFormat.JPEG, CameraFormat.cycle(CameraFormat.JPEG, rawSupported = false))
        assertEquals(CameraFormat.JPEG, CameraFormat.cycle(CameraFormat.RAW_SINGLE, rawSupported = false))
        assertEquals(CameraFormat.JPEG, CameraFormat.cycle(CameraFormat.RAW_BURST, rawSupported = false))
    }

    @Test fun rawFormatsReportMultiFrameSingleOtherwise() {
        assertFalse(CameraFormat.JPEG.raw); assertFalse(CameraFormat.JPEG.multiFrame)
        assertTrue(CameraFormat.RAW_SINGLE.raw); assertFalse(CameraFormat.RAW_SINGLE.multiFrame)
        assertTrue(CameraFormat.RAW_BURST.raw); assertTrue(CameraFormat.RAW_BURST.multiFrame)
    }

    @Test fun persistNamesRoundTrip() {
        for (format in CameraFormat.entries) assertEquals(format, CameraFormat.fromName(format.persistName))
    }

    @Test fun unknownOrMissingNameHasNoFormat() {
        assertNull(CameraFormat.fromName(null))
        assertNull(CameraFormat.fromName("jpeg "))
        assertNull(CameraFormat.fromName("raw"))
    }
}
