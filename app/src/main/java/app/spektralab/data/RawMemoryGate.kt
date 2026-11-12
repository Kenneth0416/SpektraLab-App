package app.spektralab.data

import java.io.Closeable
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** RAW parsing needs full sensor arrays even for a thumbnail. Share one budget with bursts. */
internal object RawMemoryGate {
    private val permit = Semaphore(1, true)

    /** Blocking: acquire only on an IO/worker thread, never the UI thread. */
    fun acquire(): Closeable {
        permit.acquire()
        val closed = AtomicBoolean()
        return Closeable { if (closed.compareAndSet(false, true)) permit.release() }
    }
}
