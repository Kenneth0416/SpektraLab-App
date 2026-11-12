package app.spektralab.export

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface

/**
 * Shooting data an exported print takes over from its original (camera, exposure, time zone, location).
 * Location is copied on purpose: the print stays on the device next to the original, like any camera app's edit.
 *
 * Deliberately NOT copied, because it describes the original's pixels and would be wrong for the print:
 * orientation (the print is upright), width/height, thumbnails, colour space (the print is sRGB),
 * MakerNote, compression/strip/tile tags and DNG tags.
 */
object PrintExif {
    private const val TAG = "SpektraExport"

    /** Copied verbatim when the original has them. Both ISO names: older readers only know one. */
    @Suppress("DEPRECATION")
    val SHOOTING_TAGS: List<String> = listOf(
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
        ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_LENS_SPECIFICATION,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_ISO_SPEED_RATINGS, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
    )

    /** Copied only together with a position: a lone altitude or time stamp says nothing. */
    val GPS_TAGS: List<String> = listOf(
        ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
    )

    private val DATE_FORMAT = Regex("""\d{4}:\d{2}:\d{2} \d{2}:\d{2}:\d{2}""")

    /** What [apply] needs from the original; [tags] holds only the tags it actually has. */
    class Original(val tags: Map<String, String>, val dateTimeOriginal: String?)

    /** Read the wanted tags from [src]. */
    fun read(src: ExifInterface): Original {
        val tags = LinkedHashMap<String, String>()
        for (t in SHOOTING_TAGS) src.getAttribute(t)?.takeIf { it.isNotBlank() }?.let { tags[t] = it }
        val hasFix = listOf(ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE_REF)
            .all { !src.getAttribute(it).isNullOrBlank() }
        if (hasFix) for (t in GPS_TAGS) src.getAttribute(t)?.takeIf { it.isNotBlank() }?.let { tags[t] = it }
        val taken = (src.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: src.getAttribute(ExifInterface.TAG_DATETIME))
            ?.trim()?.takeIf { DATE_FORMAT.matches(it) }
        return Original(tags, taken)
    }

    /**
     * Open the original and read it. A file descriptor first, which ExifInterface reads
     * by seeking (RAW and HEIC need that, and nothing is loaded whole); a plain stream if the provider has none.
     * Null when the original cannot be read: the print then just gets the basics.
     */
    fun read(cr: ContentResolver, uri: Uri): Original? {
        runCatching {
            cr.openFileDescriptor(uri, "r")?.use { return read(ExifInterface(it.fileDescriptor)) }
        }.onFailure { Log.w(TAG, "original EXIF not readable from a descriptor", it) }
        return runCatching { cr.openInputStream(uri)?.use { read(ExifInterface(it)) } }
            .onFailure { Log.w(TAG, "original EXIF not readable", it) }.getOrNull()
    }

    /**
     * Write the print's tags to [dst] (not yet saved): the original's shooting data when given, plus
     * the print's own. [takenFallback] is the item's date in EXIF format, used when the original has no date of its own.
     */
    fun apply(dst: ExifInterface, original: Original?, takenFallback: String, film: String) {
        original?.tags?.forEach { (tag, value) -> dst.setAttribute(tag, value) }
        // the original's exact string (with its sub-seconds and offset) beats the item's rounded date
        val taken = original?.dateTimeOriginal ?: takenFallback
        dst.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, taken)
        dst.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, taken)
        dst.setAttribute(ExifInterface.TAG_DATETIME, taken)
        dst.setAttribute(ExifInterface.TAG_SOFTWARE, "Silvercam")
        dst.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, film)
        // the render is upright and sRGB whatever the original was
        dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        dst.setAttribute(ExifInterface.TAG_COLOR_SPACE, "1")
    }

    /** Write the print at [printFd] (opened read-write). Never throws: metadata must not fail an export. */
    fun write(printFd: java.io.FileDescriptor, original: Original?, takenFallback: String, film: String) {
        runCatching {
            val exif = ExifInterface(printFd)
            apply(exif, original, takenFallback, film)
            exif.saveAttributes()
        }.onFailure { Log.w(TAG, "print EXIF not written", it) }
    }
}
