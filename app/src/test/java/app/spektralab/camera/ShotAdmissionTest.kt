package app.spektralab.camera

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class ShotAdmissionTest {
    @Test fun stalledMergeRejectsFurtherShotsUntilProcessingDrains() {
        val gate = ShotAdmission()
        val shot = gate.acquire()!!
        shot.capturesDone()
        repeat(100) { assertNull(gate.acquire()) }
        assertFalse(gate.available)
        shot.processingDone()
        assertTrue(gate.available)
        assertNotNull(gate.acquire())
        // A duplicate completion must not release the next shot's lease.
        shot.processingDone()
        assertNull(gate.acquire())
    }

    @Test fun failedShotKeepsAdmissionUntilCancelledCaptureCallbacksDrain() {
        val gate = ShotAdmission()
        val shot = gate.acquire()!!
        shot.processingDone() // first callback failed; CameraX still owns pending requests
        assertNull(gate.acquire())
        shot.capturesDone()
        assertNotNull(gate.acquire())
    }

    @Test fun closeDrainsExistingWorkAndNeverReopensAdmission() {
        var shutdowns = 0
        val gate = ShotAdmission { shutdowns++ }
        val shot = gate.acquire()!!
        gate.close()
        assertEquals(0, shutdowns)
        shot.capturesDone()
        assertEquals(0, shutdowns)
        shot.processingDone()
        shot.processingDone()
        gate.close()
        assertEquals(1, shutdowns)
        assertNull(gate.acquire())
    }

    @Test fun concurrentRequestsHaveExactlyOneOwner() {
        val gate = ShotAdmission()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val accepted = executor.invokeAll(List(64) { Callable { gate.acquire() } }).mapNotNull { it.get() }
            assertEquals(1, accepted.size)
            accepted.single().processingDone()
            accepted.single().capturesDone()
            assertTrue(gate.available)
        } finally { executor.shutdownNow() }
    }
}
