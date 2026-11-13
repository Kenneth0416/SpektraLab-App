package app.spektralab.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateManagerTest {
    private class Store(override var lastAttemptAt: Long = 0) : UpdateCheckStore
    private val release = AppRelease(2, "0.1.1", 29, "https://example.com/downloads/native.apk", mapOf("en" to "New features"))

    @Test fun checksOncePerDayIncludingAfterRestartAndManualBypassesLimit() = runTest {
        val store = Store()
        var now = 1000L
        var requests = 0
        fun manager() = UpdateManager(this, store, 1, 35, { requests++; release }, { now })
        val first = manager()
        first.check(); runCurrent()
        assertEquals(release, (first.status.value as UpdateStatus.Available).release)
        assertNotNull(first.prompt.value)
        first.check(); manager().check(); runCurrent()
        assertEquals(1, requests)
        val second = manager()
        second.check(manual = true); runCurrent()
        assertEquals(2, requests)
        assertTrue(second.prompt.value!!.manual)
        now += UpdateManager.DAY_MS
        second.check(); runCurrent()
        assertEquals(3, requests)
    }

    @Test fun laterSurvivesRestartButDoesNotPreventManualChecks() = runTest {
        var now = 1000L
        val store = Store()
        var requests = 0
        val manager = UpdateManager(this, store, 1, 35, { requests++; release }, { now })
        manager.check(); runCurrent()
        now += 5000
        manager.dismissPrompt()
        assertNull(manager.prompt.value)
        assertEquals(now, store.lastAttemptAt)
        val restarted = UpdateManager(this, store, 1, 35, { requests++; release }, { now })
        restarted.check(); runCurrent()
        assertEquals(1, requests)
        restarted.check(true); runCurrent()
        assertEquals(2, requests)
    }

    @Test fun sameOlderOrUnsupportedReleasesNeverPrompt() = runTest {
        for ((code, sdk, expected) in listOf(
            Triple(2L, 29, UpdateStatus.Current), Triple(1L, 29, UpdateStatus.Current),
            Triple(3L, 40, UpdateStatus.Unsupported),
        )) {
            val manager = UpdateManager(this, Store(), 2, 35, { release.copy(versionCode = code, minSdk = sdk) })
            manager.check(true); runCurrent()
            assertEquals(expected, manager.status.value)
            assertNull(manager.prompt.value)
        }
    }

    @Test fun failuresAreQuietAndThrottledAndManualRetryRecovers() = runTest {
        var requests = 0
        val manager = UpdateManager(this, Store(), 1, 35, {
            if (++requests == 1) throw IOException("Offline")
            release
        }, { 1000L })
        manager.check(); runCurrent()
        assertEquals(UpdateStatus.Failed, manager.status.value)
        assertNull(manager.prompt.value)
        manager.check(); runCurrent()
        assertEquals(1, requests)
        manager.check(true); runCurrent()
        assertEquals(2, requests)
        assertNotNull(manager.prompt.value)
    }

    @Test fun concurrentChecksShareOneRequestAndManualIntentIsRetained() = runTest {
        var requests = 0
        val response = CompletableDeferred<AppRelease>()
        val manager = UpdateManager(this, Store(), 1, 35, { requests++; response.await() })
        manager.check(); runCurrent()
        manager.check(true); manager.check(); runCurrent()
        assertEquals(UpdateStatus.Checking, manager.status.value)
        assertEquals(1, requests)
        response.complete(release); runCurrent()
        assertTrue(manager.prompt.value!!.manual)
    }

    @Test fun clockMovingBackwardsDoesNotDisableChecksIndefinitely() = runTest {
        val manager = UpdateManager(this, Store(100_000), 1, 35, { release }, { 1000L })
        manager.check(); runCurrent()
        assertNotNull(manager.prompt.value)
    }
}
