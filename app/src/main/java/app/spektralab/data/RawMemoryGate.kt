package app.spektralab.data

import app.spektralab.HeavyWork
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RAW parsing and burst planning need full sensor arrays even for a thumbnail.
 * [HeavyWork] is the one process-wide budget. A lease stays held while those arrays,
 * the developed image, or the GPU merge targets are still alive, so an export cannot
 * allocate beside them. The caller closes the returned [SourceImage] after its pixels
 * have been uploaded or are otherwise no longer needed.
 *
 * Never acquire on the main thread. The mutex is not reentrant: a caller that already
 * holds [HeavyWork] passes [alreadyHeld], or locking again deadlocks.
 */
internal object RawMemoryGate {
    fun acquire(alreadyHeld: Boolean = false): Closeable {
        if (alreadyHeld) return Closeable { }
        HeavyWork.lockBlocking()
        val closed = AtomicBoolean()
        return Closeable { if (closed.compareAndSet(false, true)) HeavyWork.unlock() }
    }
}
