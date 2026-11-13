package app.spektralab.camera

/**
 * What the shutter keeps as the original. JPEG goes through CameraX's own saving, so the
 * film effect stays on the live preview and consecutive shots are never blocked. RAW modes
 * take the sensor's own data (single frame, or a merged burst) and detach the preview effect.
 */
enum class CameraFormat(val raw: Boolean, val multiFrame: Boolean) {
    JPEG(false, false), RAW_SINGLE(true, false), RAW_BURST(true, true);

    /** Value stored in the camera's sharedPreferences ("cameraFormat"). */
    val persistName: String
        get() = when (this) { JPEG -> "jpeg"; RAW_SINGLE -> "raw_single"; RAW_BURST -> "raw_burst" }

    companion object {
        /** The stored value, null when the preference is absent or unknown. */
        fun fromName(name: String?): CameraFormat? = when (name) {
            "jpeg" -> JPEG; "raw_single" -> RAW_SINGLE; "raw_burst" -> RAW_BURST; else -> null
        }

        /** The next format when the badge is tapped: JPEG → RAW → RAW burst → JPEG.
         * Without RAW hardware the badge keeps the camera on JPEG. */
        fun cycle(current: CameraFormat, rawSupported: Boolean): CameraFormat {
            if (!rawSupported) return JPEG
            return when (current) { JPEG -> RAW_SINGLE; RAW_SINGLE -> RAW_BURST; RAW_BURST -> JPEG }
        }
    }
}
