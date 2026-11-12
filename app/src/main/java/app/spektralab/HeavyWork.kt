package app.spektralab

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One memory-heavy job at a time, process-wide: a photo export (full-size render, bitmap,
 * encode) and a burst merge each need hundreds of MB, and together they can run the
 * process out of memory. Waiters are served in order.
 */
object HeavyWork {
    private val mutex = Mutex()

    /** From a coroutine: suspends (does not block a thread) while another heavy job runs. */
    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }

    /** From a plain worker thread (the burst merge executor); never call on the main thread. */
    fun <T> blocking(block: () -> T): T {
        lockBlocking()
        try {
            return block()
        } finally {
            unlock()
        }
    }

    /**
     * Split form of [blocking] for a lease that must outlive one function, such as a
     * developed image handed back to the caller. The mutex is not reentrant: a thread
     * that already holds it must not lock again. Never call on the main thread.
     */
    internal fun lockBlocking() {
        kotlinx.coroutines.runBlocking { mutex.lock() }
    }

    internal fun unlock() {
        mutex.unlock()
    }
}
