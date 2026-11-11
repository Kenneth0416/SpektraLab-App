package app.spektralab.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import app.spektralab.raw.BurstMerge
import app.spektralab.raw.DngReader
import app.spektralab.raw.DngWriter
import java.io.File

/**
 * Turns a burst of raw frames into the one original that is kept in the album: the frames
 * merged into a Linear DNG, or — when they cannot be merged — the first frame untouched.
 */
object BurstStore {
    /** Frames per shot: about 2.2x less noise when everything holds still. */
    const val FRAMES = 5

    class Saved(val uri: Uri, /** effective number of frames in the picture; 1 = not merged */ val framesUsed: Float)

    fun dir(context: Context) = File(context.cacheDir, "burst").apply { mkdirs() }

    /** Frames left behind by a merge that never finished (app killed). */
    fun clear(context: Context) { dir(context).listFiles()?.forEach { it.delete() } }

    /** Blocking and heavy (seconds, hundreds of MB): call off the main thread, one at a time. */
    fun mergeToAlbum(context: Context, files: List<File>, progress: (Float) -> Unit): Saved? {
        if (files.isEmpty()) return null
        val name = files[0].name.substringBeforeLast('_')
        val merged = if (files.size < 2) null else try {
            val ref = DngReader.read(files[0].readBytes())
            if (ref == null || ref.linear) null else {
                val frames = arrayListOf(ref)
                for (f in files.drop(1)) DngReader.read(f.readBytes())
                    ?.takeIf { !it.linear && it.cropWidth == ref.cropWidth && it.cropHeight == ref.cropHeight }?.let { frames += it }
                if (frames.size < 2) null else ref to BurstMerge.merge(frames, progress = progress)
            }
        } catch (t: Throwable) {
            null                                                   // also out of memory: keep the single frame
        }

        val cr = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.dng")
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/SpektraLab")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            cr.openOutputStream(uri)!!.buffered(1 shl 16).use { out ->
                if (merged != null) {
                    val (ref, m) = merged
                    DngWriter.writeLinear(out, m.width, m.height, m.rgb, ref, m.gainMaps, m.noise)
                } else files[0].inputStream().use { it.copyTo(out) }
            }
            cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            Saved(uri, merged?.second?.framesUsed ?: 1f)
        } catch (t: Throwable) {
            runCatching { cr.delete(uri, null, null) }
            null
        }
    }
}
