package app.spektralab.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import app.spektralab.gl.BurstGl
import app.spektralab.raw.BurstMerge
import app.spektralab.raw.DngReader
import app.spektralab.raw.DngWriter
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Camera2 capture results of the bound camera, as they complete. A raw frame needs the
 * result of its own exposure to become a DNG (white balance, noise model, lens shading),
 * and the running sensitivity tells how many frames a shot is worth.
 */
class CaptureResults : CameraCaptureSession.CaptureCallback() {
    private val lock = Object()
    private val recent = LinkedHashMap<Long, TotalCaptureResult>()

    /** Sensor sensitivity (ISO) of the latest frame, null before the first one. */
    @Volatile var iso: Int? = null
        private set

    /** Exposure time (ns) of the latest frame, null before the first one. */
    @Volatile var exposureNs: Long? = null
        private set

    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
        result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { iso = it }
        result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { exposureNs = it }
        val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        synchronized(lock) {
            recent[ts] = result
            while (recent.size > 48) recent.remove(recent.keys.first())
            lock.notifyAll()
        }
    }

    /** The result of the exposure that started at [timestamp]; it may arrive a little after the image. */
    fun await(timestamp: Long, timeoutMs: Long = 800): TotalCaptureResult? = synchronized(lock) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            recent[timestamp]?.let { return it }
            val left = end - SystemClock.elapsedRealtime()
            if (left <= 0) return null
            lock.wait(left)
        }
        @Suppress("UNREACHABLE_CODE") null
    }
}

/**
 * Takes raw pictures into memory: one frame, or a short burst that is merged into one
 * cleaner original. Every frame becomes a DNG from its own capture result. The first
 * frame is saved to the album at once, so the shot is on the lightbox immediately; a
 * merged burst replaces it when it is ready.
 */
class RawShooter(private val context: Context) {
    val results = CaptureResults()

    /** Raw frames could not be taken into memory on this device: the caller saves through CameraX instead. */
    @Volatile var unsupported = false
        private set

    /** Progress 0..1 of the bursts being merged, null when idle. */
    var onProgress: (Float?) -> Unit = {}
    /** The first frame of a shot is in the album. Main thread. */
    var onSaved: (uri: Uri, evBias: Float) -> Unit = { _, _ -> }
    /** The shot is complete: [merged] replaces [first] when the burst was merged. Main thread. */
    var onFinished: (first: Uri, merged: Uri?, framesUsed: Float) -> Unit = { _, _, _ -> }

    private val main = ContextCompat.getMainExecutor(context)
    /** Image -> DNG bytes; quick, so the camera gets its buffers back. */
    private val io = Executors.newSingleThreadExecutor()
    /** Alignment and merging, one shot after the other (memory). */
    private val work = Executors.newSingleThreadExecutor()
    private val merging = AtomicInteger()
    // A lease lasts through capture AND merge; no later shot can queue more RAW arrays.
    private val admission = ShotAdmission { io.shutdown(); work.shutdown() }
    val available get() = admission.available

    /** Stop new shots; queued callbacks still close their ImageProxy and drain safely. */
    fun close() {
        onProgress = {}; onSaved = { _, _ -> }; onFinished = { _, _, _ -> }
        admission.close()
    }

    /**
     * Frames worth taking now in low-light auto mode: one in normal light, a burst only when
     * the metered exposure says the scene is dark ([LowLight]). Reads the latest metering, so
     * it costs the shutter nothing.
     */
    fun frameCount(flashOn: Boolean = false): Int =
        LowLight.frames(results.iso, results.exposureNs, MAX_FRAMES, flashOn)

    /**
     * @param onExposed main thread, once: the camera is done with this shot ([ok]), or it could
     *   not be taken this way and should be retried through CameraX's own saving ([retry])
     */
    fun shoot(capture: ImageCapture, chars: CameraCharacteristics, frames: Int, name: String, evBias: Float,
              onExposed: (ok: Boolean, retry: Boolean) -> Unit): Boolean {
        require(frames in 1..MAX_FRAMES)
        val lease = admission.acquire() ?: return false
        val shot = Shot(frames, name, evBias, onExposed, lease)
        Log.i(TAG, "RAW shot=$name requestedFrames=$frames iso=${results.iso}")
        // At most MAX_FRAMES callbacks/tasks exist: admission stays closed through merging.
        repeat(frames) { index ->
            try {
                capture.takePicture(io, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) = shot.frame(toDng(image, chars, shot.dead), null)
                    override fun onError(exception: ImageCaptureException) = shot.frame(null, exception)
                })
            } catch (e: Exception) {
                // Requests that threw synchronously will never deliver callbacks. Count those
                // and the unsubmitted remainder on the same serial thread as real callbacks.
                val error = ImageCaptureException(ImageCapture.ERROR_UNKNOWN, "capture submission failed", e)
                io.execute { repeat(frames - index) { shot.frame(null, error) } }
                return true
            }
        }
        return true
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun toDng(image: ImageProxy, chars: CameraCharacteristics, skip: Boolean): ByteArray? = try {
        val img = image.image
        if (skip || img == null || img.format != ImageFormat.RAW_SENSOR) null else {
            val result = results.await(img.timestamp)
            if (result == null) null else {
                val out = ByteArrayOutputStream(img.width * img.height * 2 + (1 shl 20))
                DngCreator(chars, result).use { dng ->
                    dng.setOrientation(when (image.imageInfo.rotationDegrees) {
                        90 -> ExifInterface.ORIENTATION_ROTATE_90; 180 -> ExifInterface.ORIENTATION_ROTATE_180
                        270 -> ExifInterface.ORIENTATION_ROTATE_270; else -> ExifInterface.ORIENTATION_NORMAL
                    })
                    dng.writeImage(out, img)
                }
                out.toByteArray()
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "raw frame could not be written", t)
        null
    } finally {
        image.close()
    }

    /** One press of the shutter. [frame] runs on the io thread, in capture order. */
    private inner class Shot(val frames: Int, val name: String, val evBias: Float,
                             val onExposed: (Boolean, Boolean) -> Unit, val lease: ShotAdmission.Lease) {
        @Volatile var dead = false
        private var arrived = 0
        private var first: Uri? = null
        // touched on the work thread only
        private var planner: BurstMerge.Planner? = null

        fun frame(dng: ByteArray?, error: ImageCaptureException?) {
            arrived++
            Log.i(TAG, "RAW shot=$name received=$arrived/$frames dngReady=${dng != null} dead=$dead error=${error?.imageCaptureError}")
            if (error != null) Log.w(TAG, "RAW frame capture failed shot=$name", error)
            if (arrived == frames) lease.capturesDone()
            if (dead) return
            if (first == null) {
                if (dng == null) {
                    // nothing usable yet: a camera error ends the shot, a frame that could not be
                    // turned into a DNG means this way of capturing does not work here
                    if (arrived == 1 && error == null) unsupported = true
                    if (arrived == 1 || arrived == frames) {
                        dead = true
                        lease.processingDone()
                        main.execute { onExposed(false, error == null) }
                    }
                    return
                }
                val uri = save("$name.dng") { it.write(dng) }
                if (uri == null) { dead = true; lease.processingDone(); main.execute { onExposed(false, false) }; return }
                first = uri
                main.execute { onSaved(uri, evBias) }
                if (frames > 1) {
                    merging.incrementAndGet(); onProgress(0f)
                    work.execute {
                        runCatching { planner = DngReader.read(dng)?.takeIf { !it.linear }?.let { BurstMerge.Planner(it) } }
                            .onFailure { Log.w(TAG, "RAW planner initialization failed shot=$name", it) }
                        Log.i(TAG, "RAW shot=$name plannerFrames=${planner?.frames ?: 0}")
                    }
                }
            } else if (dng != null) {
                work.execute {
                    runCatching { DngReader.read(dng)?.let { planner?.add(it) } }
                        .onFailure { Log.w(TAG, "RAW planner add failed shot=$name", it) }
                    Log.i(TAG, "RAW shot=$name plannerFrames=${planner?.frames ?: 0}")
                    onProgress(0.5f * ((planner?.frames ?: 1) - 1) / (frames - 1))
                }
            }
            if (arrived == frames) {
                main.execute { onExposed(true, false) }
                val uri = first!!
                if (frames > 1) work.execute { finish(uri) } else {
                    lease.processingDone()
                    main.execute { onFinished(uri, null, 1f) }
                }
            }
        }

        private fun finish(first: Uri) {
            var merged: Uri? = null; var used = 1f
            try {
                Log.i(TAG, "RAW shot=$name merge planning received=$arrived requested=$frames plannerFrames=${planner?.frames ?: 0}")
                val p = planner?.takeIf { it.frames >= 2 }?.build()
                planner = null
                if (p != null) {
                    val gather = { f: Float -> onProgress(0.5f + 0.4f * f) }
                    val gpuRgb = BurstGl.gather(p, gather)
                    Log.i(TAG, "RAW shot=$name gatherBackend=${if (gpuRgb != null) "GPU" else "CPU"}")
                    val rgb = gpuRgb ?: BurstMerge.gather(p, progress = gather)
                    val m = BurstMerge.finish(p, rgb)
                    merged = save("${name}_M.dng") { DngWriter.writeLinear(it, m.width, m.height, m.rgb, p.frames[0], m.gainMaps, m.noise) }
                    used = m.framesUsed
                    Log.i(TAG, "RAW shot=$name mergeCompleted dimensions=${m.width}x${m.height} framesUsed=$used saved=${merged != null}")
                } else Log.w(TAG, "RAW shot=$name merge skipped: fewer than two usable planner frames")
            } catch (t: Throwable) {
                // also out of memory: the first frame stays the original
                Log.w(TAG, "burst not merged", t)
                planner = null
            } finally {
                planner = null
                if (merging.decrementAndGet() == 0) onProgress(null)
                lease.processingDone()
            }
            main.execute { onFinished(first, merged, used) }
        }
    }

    /** A DNG in DCIM/Silvercam; null when it could not be written. */
    private fun save(displayName: String, write: (OutputStream) -> Unit): Uri? {
        val cr = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Silvercam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = runCatching { cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return null
        return try {
            cr.openOutputStream(uri)!!.buffered(1 shl 16).use(write)
            cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (t: Throwable) {
            Log.w(TAG, "could not save $displayName", t)
            runCatching { cr.delete(uri, null, null) }
            null
        }
    }

    companion object {
        private const val TAG = "SpektraShot"
        const val MAX_FRAMES = 5
    }
}
