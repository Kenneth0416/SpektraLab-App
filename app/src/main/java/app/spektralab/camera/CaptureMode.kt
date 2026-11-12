package app.spektralab.camera

/** What a press of the shutter saves. Stored by [key]; the default is a single raw frame. */
enum class CaptureMode(val key: String) {
    /** One JPEG original. */
    JPEG("jpeg"),
    /** One raw DNG. */
    RAW("raw"),
    /** Raw: one frame in normal light, a merged multi-frame burst only when the scene is dark. */
    AUTO("auto");

    companion object {
        val DEFAULT = RAW
        fun fromKey(key: String?): CaptureMode = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** Whether a raw file is kept: the user did not ask for JPEG and the device can stream raw. */
fun CaptureMode.savesRaw(deviceHasRaw: Boolean): Boolean = deviceHasRaw && this != CaptureMode.JPEG

/**
 * Decides, from the exposure the camera is already metering, how many raw frames a shot
 * is worth. Pure so it can be tested; the shutter only reads two cached numbers.
 */
object LowLight {
    /**
     * Light level as sensitivity x exposure time (ISO-seconds). A scene of fixed brightness
     * under a fixed aperture keeps this product constant whatever mix of ISO and shutter
     * time auto-exposure picks, so it measures how dark the scene is without caring which
     * way the camera compensated. Typical phone values: daylight about 0.1-1, a bright
     * room 2-5, an evening room 10-30, a dim street 50 and more.
     *
     * UNTUNED: chosen from those typical values (ISO 300 at 1/30 s = 10), not measured on
     * the phone. Below it a single raw frame is already clean enough for film emulation.
     */
    const val BURST_ISO_SECONDS = 10f

    /** Darker than this (ISO 1200 at 1/30 s = 40) the full burst is worth its time. */
    const val FULL_BURST_ISO_SECONDS = 40f

    /** Frames at the middle step, between one and the maximum. */
    const val MID_FRAMES = 3

    /**
     * @param iso latest metered sensitivity, null before the first capture result
     * @param exposureNs latest metered exposure time in nanoseconds, null likewise
     * @param flashOn the flash will fire; the flash lights the scene, and it cannot fire per frame
     * Unknown exposure gives one frame: a burst is never taken on a guess.
     */
    fun frames(iso: Int?, exposureNs: Long?, maxFrames: Int, flashOn: Boolean = false): Int {
        if (flashOn || iso == null || exposureNs == null || iso <= 0 || exposureNs <= 0) return 1
        val isoSeconds = iso * (exposureNs / 1e9f)
        return when {
            isoSeconds < BURST_ISO_SECONDS -> 1
            isoSeconds < FULL_BURST_ISO_SECONDS -> MID_FRAMES.coerceAtMost(maxFrames)
            else -> maxFrames
        }
    }
}
