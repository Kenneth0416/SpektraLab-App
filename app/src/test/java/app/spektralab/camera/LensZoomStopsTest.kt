package app.spektralab.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class LensZoomStopsTest {
    private val cameras = listOf("main" to 24f, "wide" to 12f, "tele" to 72f)

    @Test fun independentUltrawideIsSelectableOutsideLogicalZoomRange() {
        assertEquals(listOf("wide" to 0.5f),
            separateLensRatios("main", 24f, listOf(0.5f, 1f, 3f), 1f, 10f, cameras))
    }

    @Test fun logicalUltrawideDoesNotNeedASeparateSwitch() {
        assertEquals(emptyList<Pair<String, Float>>(),
            separateLensRatios("main", 24f, listOf(0.5f, 1f, 3f), 0.5f, 10f, cameras))
    }

    @Test fun canReturnToMainLensEvenInsideUltrawideDigitalZoomRange() {
        assertEquals(listOf("main" to 2f, "tele" to 6f),
            separateLensRatios("wide", 12f, listOf(1f), 1f, 10f, cameras))
    }

    @Test fun wideEndSurvivesPartialPhysicalMetadata() {
        assertEquals(listOf(0.5f, 1f, 3f), lensZoomStops(0.5f, 10f, listOf(1f, 3f)))
    }

    @Test fun sensorRoundingDoesNotHideMinimumZoom() {
        assertEquals(listOf(0.6f, 1f, 3f), lensZoomStops(0.6f, 10f, listOf(0.58f, 1f, 3f)))
    }

    @Test fun duplicatePhysicalMetadataStillUsesFallbackStops() {
        assertEquals(listOf(0.5f, 1f, 2f, 5f), lensZoomStops(0.5f, 8f, listOf(1f, 1.01f)))
    }

    @Test fun doesNotInventUnsupportedUltrawide() {
        assertEquals(listOf(1f, 3f), lensZoomStops(1f, 8f, listOf(0.5f, 1f, 3f)))
    }

    @Test fun rejectsInvalidMetadataAndKeepsExactOne() {
        assertEquals(listOf(1f, 3f), lensZoomStops(1f, 8f, listOf(Float.NaN, Float.POSITIVE_INFINITY, 1.02f, 3f)))
    }
}
