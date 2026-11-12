package app.spektralab.camera

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The 48-entry preview cache must not forget a result a shot has already asked to keep. */
class CaptureResultRetentionTest {
    @Test fun blockedDecodeDoesNotPreventLaterFramesFromStayingPinned() {
        val results = CaptureResults()
        val pin = results.pinIncoming(3)
        val decode = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            RawFrameDispatch.retainThenDecode(11L, pin, decode) {
                started.countDown()
                assertTrue(release.await(2, TimeUnit.SECONDS))
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            // These callbacks pin before their decode is allowed to run behind the blocked one.
            RawFrameDispatch.retainThenDecode(12L, pin, decode) { }
            RawFrameDispatch.retainThenDecode(13L, pin, decode) { }
            results.completed(11L, Any())
            results.completed(12L, Any())
            results.completed(13L, Any())
            repeat(60) { index -> results.completed(500L + index, Any()) }
            assertNotNull(results.cached(11L))
            assertNotNull(results.cached(12L))
            assertNotNull(results.cached(13L))
        } finally {
            release.countDown()
            decode.shutdown()
            pin.close()
        }
    }

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
