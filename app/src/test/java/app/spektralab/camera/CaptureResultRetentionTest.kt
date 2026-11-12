package app.spektralab.camera

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The 48-entry preview cache must not forget a result a shot has already asked to keep. */
class CaptureResultRetentionTest {
    @Test fun creatingAPinDoesNotRetainAnythingUntilATimestampArrives() {
        val results = CaptureResults()
        val pin = results.pinIncoming(3)
        val first = Any()
        results.completed(1L, first)
        repeat(60) { index -> results.completed(100L + index, Any()) }
        assertNull(results.cached(1L))
        results.completed(2L, Any())
        pin.completed(2L)
        repeat(60) { index -> results.completed(200L + index, Any()) }
        org.junit.Assert.assertNotNull(results.cached(2L))
        pin.close()
    }

    @Test fun pinnedResultSurvivesMoreThanFortyEightLaterFrames() {
        val results = CaptureResults()
        val pin = results.pinIncoming(1)
        val kept = Any()
        results.completed(1L, kept)
        pin.completed(1L)
        repeat(60) { index -> results.completed(100L + index, Any()) }
        assertSame(kept, results.cached(1L))
        pin.close()
        repeat(60) { index -> results.completed(200L + index, Any()) }
        assertNull(results.cached(1L))
    }

    @Test fun closingOneShotDoesNotUnpinAnother() {
        val results = CaptureResults()
        val first = results.pinIncoming(1)
        val second = results.pinIncoming(1)
        val a = Any(); val b = Any()
        results.completed(11L, a); first.completed(11L)
        results.completed(12L, b); second.completed(12L)
        first.close()
        repeat(60) { index -> results.completed(400L + index, Any()) }
        assertNull(results.cached(11L))
        assertSame(b, results.cached(12L))
        second.close()
    }

    @Test fun unpinnedCacheStillForgetsTheOldestFrame() {
        val results = CaptureResults()
        results.completed(1L, Any())
        repeat(48) { index -> results.completed(2L + index, Any()) }
        assertNull(results.cached(1L))
    }
}
