package app.spektralab.camera

/** One RAW shot owns memory until both camera callbacks and processing have drained. */
internal class ShotAdmission(private val onClosedAndDrained: () -> Unit = {}) {
    private var closed = false
    private var active: Lease? = null

    val available: Boolean @Synchronized get() = !closed && active == null

    @Synchronized fun acquire(): Lease? {
        if (closed || active != null) return null
        return Lease().also { active = it }
    }

    @Synchronized fun close() {
        if (closed) return
        closed = true
        if (active == null) onClosedAndDrained()
    }

    inner class Lease internal constructor() {
        private var capturesDone = false
        private var processingDone = false

        fun capturesDone() = synchronized(this@ShotAdmission) { capturesDone = true; releaseIfDone() }
        fun processingDone() = synchronized(this@ShotAdmission) { processingDone = true; releaseIfDone() }

        private fun releaseIfDone() {
            if (capturesDone && processingDone && active === this) {
                active = null
                if (closed) onClosedAndDrained()
            }
        }
    }
}
