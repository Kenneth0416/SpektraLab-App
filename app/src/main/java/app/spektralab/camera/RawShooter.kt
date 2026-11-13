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
import app.spektralab.HeavyWork
import app.spektralab.data.RawMemoryGate
import app.spektralab.data.RawPreview
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
    private val recent = LinkedHashMap<Long, Any>()
    /** Timestamps a shot still needs. Eviction must not drop the result that makes its DNG. */
    private val pinned = HashSet<Long>()

    /** Sensor sensitivity (ISO) of the latest frame, null before the first one. */
    @Volatile var iso: Int? = null
        private set

    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
        result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { iso = it }
        val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        synchronized(lock) {
            recent[ts] = result
            evict()
            lock.notifyAll()
        }
    }

    /**
     * A budget for [count] results. Creating this does not retain any timestamp. A timestamp
     * is pinned only from the camera callback, before that frame is queued for decode.
     * [await] also pins a timestamp that completed before its image was delivered.
     */
    fun pinIncoming(count: Int): CapturePin {
        require(count > 0)
        return CapturePin(count)
    }

    /** The result of the exposure that started at [timestamp]; it may arrive a little after the image. */
    fun await(timestamp: Long, timeoutMs: Long = 800): TotalCaptureResult? = synchronized(lock) {
        // A result can complete before its image callback. Pin it too, or the wait below
        // is safe while every other preview frame still pushes it out of the cache.
        pinned.add(timestamp)
        evict()
        try {
            val end = SystemClock.elapsedRealtime() + timeoutMs
            while (true) {
                @Suppress("UNCHECKED_CAST")
                recent[timestamp]?.let { return it as TotalCaptureResult }
                val left = end - SystemClock.elapsedRealtime()
                if (left <= 0) return null
                lock.wait(left)
            }
            @Suppress("UNREACHABLE_CODE") null
        } finally {
            pinned.remove(timestamp)
            evict()
        }
    }

    /** Test seam: one completed preview result. The token stands in for a capture result. */
    internal fun completed(timestamp: Long, token: Any) = synchronized(lock) {
        recent[timestamp] = token
        evict()
        lock.notifyAll()
    }

    /** Test seam: the token currently cached for [timestamp], without waiting or pinning. */
    internal fun cached(timestamp: Long): Any? = synchronized(lock) { recent[timestamp] }

    private fun evict() {
        val it = recent.keys.iterator()
        while (recent.size > 48 && it.hasNext()) {
            val ts = it.next()
            if (ts !in pinned) it.remove()
        }
    }

    /** Releases the timestamps this shot pinned, including frames that never arrived. */
    inner class CapturePin internal constructor(private var remaining: Int) : java.io.Closeable {
        private val owned = ArrayList<Long>(remaining)
        private var closed = false

        fun completed(timestamp: Long) { synchronized(lock) {
            if (closed || remaining == 0 || timestamp in owned) return
            owned.add(timestamp)
            pinned.add(timestamp)
            remaining--
        } }

        override fun close() { synchronized(lock) {
            if (closed) return
            closed = true
            remaining = 0
            owned.forEach { pinned.remove(it) }
            owned.clear()
            evict()
        } }
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
    /** The first frame and its bounded preview are ready. Main thread. */
    var onSaved: (uri: Uri, evBias: Float, preview: RawPreview?) -> Unit = { _, _, _ -> }
    /** The shot is complete: [merged] replaces [first], with a preview of that source. Main thread. */
    var onFinished: (first: Uri, merged: Uri?, framesUsed: Float, preview: RawPreview?) -> Unit = { _, _, _, _ -> }

    private val main = ContextCompat.getMainExecutor(context)
    /** Camera callbacks only. Must not block: it pins metadata and hands the frame to [io]. */
    private val callbacks = Executors.newSingleThreadExecutor { Thread(it, "spektra-raw-callback") }
    /** DNG bytes and the memory-gate wait. One frame at a time, so the camera buffer is released. */
    private val io = Executors.newSingleThreadExecutor { Thread(it, "spektra-raw-decode") }
    /** Alignment and merging, one shot after the other (memory). */
    private val work = Executors.newSingleThreadExecutor { Thread(it, "spektra-raw-merge") }
    private val merging = AtomicInteger()
    // A lease lasts through capture AND merge; no later shot can queue more RAW arrays.
    private val admission = ShotAdmission { callbacks.shutdown(); io.shutdown(); work.shutdown() }
    val available get() = admission.available

    /** Stop new shots; queued callbacks still close their ImageProxy and drain safely. */
    fun close() {
        onProgress = {}; onSaved = { _, _, _ -> }; onFinished = { _, _, _, _ -> }
        admission.close()
    }

    /**
     * Frames worth taking now: in good light one raw frame is already clean; the more the
     * sensor has to amplify, the more frames are averaged.
     */
    fun frameCount(chars: CameraCharacteristics): Int {
        val iso = results.iso ?: return MAX_FRAMES
        val base = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.lower ?: 100
        val gain = iso.toFloat() / base.coerceAtLeast(1)
        return when { gain < 1.5f -> 1; gain < 4f -> 3; else -> MAX_FRAMES }
    }

    /**
     * @param onExposed main thread, once: the camera is done with this shot ([ok]), or it could
     *   not be taken this way and should be retried through CameraX's own saving ([retry])
     */
    fun shoot(capture: ImageCapture, chars: CameraCharacteristics, frames: Int, name: String, evBias: Float,
              onExposed: (ok: Boolean, retry: Boolean) -> Unit): Boolean {
        require(frames in 1..MAX_FRAMES)
        val lease = admission.acquire() ?: return false
        // pinIncoming does not retain timestamps. Each callback pins its own timestamp on
        // [callbacks], then queues decode on [io]. [io] may block in HeavyWork; later callbacks
        // must still pin while it does, or the 48-entry cache can drop their results.
        val shot = Shot(frames, name, evBias, onExposed, lease, results.pinIncoming(frames))
        Log.i(TAG, "RAW shot=$name requestedFrames=$frames iso=${results.iso}")
        // At most MAX_FRAMES callbacks/tasks exist: admission stays closed through merging.
        repeat(frames) { index ->
            try {
                capture.takePicture(callbacks, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) = shot.accept(image, chars)
                    override fun onError(exception: ImageCaptureException) {
                        try {
                            io.execute { shot.frame(null, exception) }
                        } catch (rejected: java.util.concurrent.RejectedExecutionException) {
                            Log.w(TAG, "RAW error arrived after decode stopped", rejected)
                        }
                    }
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

    /** Why [toDng] produced no bytes. A late metadata result is not proof the device cannot do RAW. */
    private enum class DngMiss { NONE, NOT_RAW, NO_RESULT }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun toDng(image: ImageProxy, chars: CameraCharacteristics, skip: Boolean): Pair<ByteArray?, DngMiss> = try {
        val img = image.image
        if (skip || img == null || img.format != ImageFormat.RAW_SENSOR) null to DngMiss.NOT_RAW else {
            val result = results.await(img.timestamp)
            if (result == null) null to DngMiss.NO_RESULT else {
                val out = ByteArrayOutputStream(img.width * img.height * 2 + (1 shl 20))
                DngCreator(chars, result).use { dng ->
                    dng.setOrientation(when (image.imageInfo.rotationDegrees) {
                        90 -> ExifInterface.ORIENTATION_ROTATE_90; 180 -> ExifInterface.ORIENTATION_ROTATE_180
                        270 -> ExifInterface.ORIENTATION_ROTATE_270; else -> ExifInterface.ORIENTATION_NORMAL
                    })
                    dng.writeImage(out, img)
                }
                out.toByteArray() to DngMiss.NONE
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "raw frame could not be written", t)
        null to DngMiss.NOT_RAW
    } finally {
        image.close()
    }

    /** One press of the shutter. [frame] runs on the io thread, in capture order. */
    private inner class Shot(val frames: Int, val name: String, val evBias: Float,
                             val onExposed: (Boolean, Boolean) -> Unit, val lease: ShotAdmission.Lease,
                             private val capturePin: CaptureResults.CapturePin) {
        @Volatile var dead = false
        private var arrived = 0
        private var first: Uri? = null
        private var sawSensorRaw = false
        private var missingResult = false
        // touched on the work thread only
        private var planner: BurstMerge.Planner? = null
        /** Held from the first retained frame until merge targets and planner arrays are gone. */
        private var memory: java.io.Closeable? = null

        /** Camera callback thread: pin first, then queue the blocking decode. Never waits here. */
        @androidx.annotation.OptIn(ExperimentalGetImage::class)
        fun accept(image: ImageProxy, chars: CameraCharacteristics) {
            val sensorRaw = image.image?.format == ImageFormat.RAW_SENSOR
            val timestamp = if (sensorRaw) image.image?.timestamp else null
            try {
                RawFrameDispatch.retainThenDecode(timestamp, capturePin, io) { capture(image, chars) }
            } catch (rejected: java.util.concurrent.RejectedExecutionException) {
                image.close()
                Log.w(TAG, "RAW frame arrived after decode stopped", rejected)
            }
        }

        @androidx.annotation.OptIn(ExperimentalGetImage::class)
        fun capture(image: ImageProxy, chars: CameraCharacteristics) {
            val sensorRaw = image.image?.format == ImageFormat.RAW_SENSOR
            if (sensorRaw) image.image?.timestamp?.let { capturePin.completed(it) }
            // Runs on io. Keep this permit through every retained frame and the merge.
            // Thumbnail, import and export decodes take the same lock and therefore wait.
            try {
                if (!dead && memory == null) memory = RawMemoryGate.acquire()
            } catch (e: InterruptedException) {
                image.close()
                Thread.currentThread().interrupt()
                frame(null, ImageCaptureException(ImageCapture.ERROR_UNKNOWN, "RAW memory wait interrupted", e), DngMiss.NONE)
                return
            }
            val (dng, miss) = toDng(image, chars, dead)
            frame(dng, null, if (sensorRaw && dng != null) DngMiss.NONE else if (sensorRaw) miss else DngMiss.NOT_RAW)
        }

        private var finished = false

        private fun processingDone() {
            if (finished) return
            finished = true
            capturePin.close()
            memory?.close()
            memory = null
            lease.processingDone()
        }

        fun frame(dng: ByteArray?, error: ImageCaptureException?, miss: DngMiss = DngMiss.NOT_RAW) {
            arrived++
            Log.i(TAG, "RAW shot=$name received=$arrived/$frames dngReady=${dng != null} dead=$dead error=${error?.imageCaptureError}")
            if (error != null) Log.w(TAG, "RAW frame capture failed shot=$name", error)
            if (arrived == frames) lease.capturesDone()
            if (dead) {
                if (arrived == frames) processingDone()
                return
            }
            if (miss != DngMiss.NOT_RAW) sawSensorRaw = true
            if (miss == DngMiss.NO_RESULT) missingResult = true
            if (first == null) {
                if (dng == null) {
                    // A camera error or a non-RAW buffer ends this way of capturing. A sensor frame
                    // whose own result was not ready can happen once; it must not disable RAW.
                    val giveUp = arrived == frames || error != null
                    if (giveUp) {
                        dead = true
                        if (error == null && !sawSensorRaw) unsupported = true
                        // Release now. A later callback only sees dead and must not be the
                        // sole owner of the memory lease; processingDone is idempotent.
                        processingDone()
                        val retry = error == null && (!sawSensorRaw || missingResult)
                        main.execute { onExposed(false, retry) }
                    }
                    return
                }
                val uri = save("$name.dng") { it.write(dng) }
                if (uri == null) { dead = true; processingDone(); main.execute { onExposed(false, false) }; return }
                first = uri
                if (frames > 1) { merging.incrementAndGet(); onProgress(0f) }
                // Parse once, inside this shot's existing memory lease. Publish only a
                // small development, so import never queues behind our own burst lock.
                work.execute {
                    val start = SystemClock.elapsedRealtimeNanos()
                    val raw = runCatching { DngReader.read(dng) }.getOrNull()
                    val preview = runCatching { raw?.let(RawPreview::develop) }
                        .onFailure { Log.w(TAG, "RAW preview failed shot=$name", it) }.getOrNull()
                    Log.i(TAG, "RAW shot=$name previewReady=${preview != null} previewMs=${(SystemClock.elapsedRealtimeNanos() - start) / 1_000_000}")
                    main.execute { onSaved(uri, evBias, preview) }
                    if (frames > 1) {
                        runCatching { planner = raw?.takeIf { !it.linear }?.let { BurstMerge.Planner(it) } }
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
                // The first-frame preview must be published before completion, even
                // for a single shot. Serial work also drops full RAW arrays before unlock.
                work.execute {
                    if (frames > 1) finish(uri) else {
                        processingDone()
                        main.execute { onFinished(uri, null, 1f, null) }
                    }
                }
            }
        }

        private fun finish(first: Uri) {
            var merged: Uri? = null; var used = 1f
            var preview: RawPreview? = null
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
                    if (merged != null) preview = runCatching { RawPreview.merged(m, p.frames[0]) }
                        .onFailure { Log.w(TAG, "merged RAW preview failed shot=$name", it) }.getOrNull()
                    Log.i(TAG, "RAW shot=$name mergeCompleted dimensions=${m.width}x${m.height} framesUsed=$used saved=${merged != null}")
                } else Log.w(TAG, "RAW shot=$name merge skipped: fewer than two usable planner frames")
            } catch (t: Throwable) {
                // also out of memory: the first frame stays the original
                Log.w(TAG, "burst not merged", t)
            } finally {
                planner = null
                if (merging.decrementAndGet() == 0) onProgress(null)
                processingDone()
            }
            main.execute { onFinished(first, merged, used, preview) }
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

/**
 * Pins a capture timestamp before queueing work that may block. The camera callback
 * executor and the decode executor are different threads; sharing one lets the first
 * frame's memory wait hide every later timestamp from the preview cache.
 */
internal object RawFrameDispatch {
    fun retainThenDecode(timestamp: Long?, pin: CaptureResults.CapturePin, decode: java.util.concurrent.Executor, block: () -> Unit) {
        if (timestamp != null) pin.completed(timestamp)
        decode.execute(block)
    }
}
