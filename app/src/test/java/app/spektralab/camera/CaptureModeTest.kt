package app.spektralab.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureModeTest {
    private val max = 5

    @Test fun defaultIsSingleRaw() {
        assertEquals(CaptureMode.RAW, CaptureMode.DEFAULT)
        assertEquals(CaptureMode.RAW, CaptureMode.fromKey(null))
        assertEquals(CaptureMode.RAW, CaptureMode.fromKey("nonsense"))
        for (m in CaptureMode.entries) assertEquals(m, CaptureMode.fromKey(m.key))
    }

    @Test fun rawChoicesDegradeToJpegWithoutRawSupport() {
        assertFalse(CaptureMode.RAW.savesRaw(false))
        assertFalse(CaptureMode.AUTO.savesRaw(false))
        assertFalse(CaptureMode.JPEG.savesRaw(true))
        assertTrue(CaptureMode.RAW.savesRaw(true))
        assertTrue(CaptureMode.AUTO.savesRaw(true))
    }

    @Test fun brightSceneIsOneFrame() {
        assertEquals(1, LowLight.frames(50, 2_000_000L, max))      // daylight, ISO 50 at 1/500
        assertEquals(1, LowLight.frames(400, 16_000_000L, max))    // bright room, ~6 ISO-seconds
    }

    @Test fun dimSceneIsMidBurstAndDarkSceneIsFull() {
        assertEquals(3, LowLight.frames(800, 33_000_000L, max))    // ~26
        assertEquals(5, LowLight.frames(3200, 33_000_000L, max))   // ~106
    }

    @Test fun sameLightGivesSameAnswerWhateverTheMix() {
        // ISO x time is constant: the camera trading gain for time must not flip the decision
        assertEquals(LowLight.frames(1600, 20_000_000L, max), LowLight.frames(400, 80_000_000L, max))
    }

    @Test fun thresholdsAreHalfOpenAtTheBoundary() {
        val ns = 1_000_000_000L // one second, so ISO equals ISO-seconds
        assertEquals(3, LowLight.frames(LowLight.BURST_ISO_SECONDS.toInt(), ns, max))
        assertEquals(1, LowLight.frames(LowLight.BURST_ISO_SECONDS.toInt() - 1, ns, max))
        assertEquals(5, LowLight.frames(LowLight.FULL_BURST_ISO_SECONDS.toInt(), ns, max))
    }

    @Test fun unknownOrInvalidExposureNeverBursts() {
        assertEquals(1, LowLight.frames(null, null, max))
        assertEquals(1, LowLight.frames(3200, null, max))
        assertEquals(1, LowLight.frames(null, 100_000_000L, max))
        assertEquals(1, LowLight.frames(0, 100_000_000L, max))
        assertEquals(1, LowLight.frames(3200, 0L, max))
    }

    @Test fun flashFiringMeansOneFrame() {
        assertEquals(1, LowLight.frames(3200, 100_000_000L, max, flashOn = true))
    }

    @Test fun neverExceedsMaxFrames() {
        assertEquals(2, LowLight.frames(800, 33_000_000L, 2))
        assertEquals(2, LowLight.frames(3200, 100_000_000L, 2))
    }
}
