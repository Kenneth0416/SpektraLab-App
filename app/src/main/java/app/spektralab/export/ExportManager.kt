package app.spektralab.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.spektralab.HeavyWork
import app.spektralab.R
import app.spektralab.SpektraApp
import app.spektralab.data.LibraryItem
import app.spektralab.data.Media
import app.spektralab.gl.GlUtil
import app.spektralab.render.Framing
import app.spektralab.render.OverlayPainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

enum class PhotoFormat(val label: String, val mime: String, val ext: String) {
    JPEG("JPEG", "image/jpeg", "jpg"), PNG("PNG", "image/png", "png")
}

data class ExportOptions(
    val photoFormat: PhotoFormat = PhotoFormat.JPEG,
    /** Long edge in px; 0 = full size. */
    val photoLongEdge: Int = 0,
    val videoLongEdge: Int = 1920,
)

data class ExportState(
    val total: Int,
    val done: Int,
    /** Progress of the current item, 0..1. */
    val current: Float,
    val running: Boolean,
    val saved: List<Uri> = emptyList(),
    /** Jobs that did not print; kept (also across batches) until retried or dismissed. */
    val failures: List<ExportFailure> = emptyList(),
    /** The batch has "share" jobs. */
    val share: Boolean = false,
) {
    val failed get() = failures.size
}

/**
 * Background exports ("冲印"): every item is rendered in full through the pipeline.
 * One queue for the whole app: manual exports and camera prints append to it, and it is
 * written to [file] on every change so a process death does not lose pending prints.
 */
class ExportManager(private val app: SpektraApp) {
    private val _state = MutableStateFlow<ExportState?>(null)
    val state: StateFlow<ExportState?> = _state
    /** Finished share exports waiting for an activity in the foreground to open the chooser. */
    val pendingShare = MutableStateFlow<List<Uri>?>(null)

    // guarded by [lock]: the queue is changed from the UI, the camera callbacks and the worker
    private val lock = Any()
    /** Waiting jobs; the head is the one printing and stays until done, so a process death resumes it. */
    private val pending = ArrayDeque<ExportJob>()
    private var failures = emptyList<ExportFailure>()
    private var active = false
    /** Bumped by [cancel]: the job printing at that moment stops early. */
    @Volatile private var generation = 0
    private val resumed = java.util.concurrent.atomic.AtomicBoolean(false)

    private val file = File(app.filesDir, "export_queue.json")
    private val saver = Executors.newSingleThreadExecutor { Thread(it, "spektra-exports") }

    init {
        // restored now (the library is loaded), started by resume() once the UI is up
        if (file.exists()) runCatching {
            val (jobs, failed) = ExportQueue.resolve(ExportQueue.decode(file.readText())) { app.library.item(it) }
            synchronized(lock) {
                pending.addAll(jobs)
                failures = failed
                if (failed.isNotEmpty()) _state.value = ExportState(0, 0, 0f, false, failures = failed)
                persist()
            }
        }
    }

    fun isRunning() = _state.value?.running == true

    /**
     * Print the jobs restored from the last run. Call from the foreground UI: the
     * foreground service may only be started while the app is visible.
     */
    fun resume() {
        if (resumed.getAndSet(true)) return
        synchronized(lock) { if (!active && pending.isNotEmpty()) startLocked() }
    }

    /** Print [items]: joins the running batch, or starts one. */
    fun start(items: List<LibraryItem>, options: ExportOptions, share: Boolean) =
        submit(items.map { ExportJob(it, options, share) })

    /** Print one more item (camera shots): joins the running batch, or starts one. */
    fun enqueue(item: LibraryItem, options: ExportOptions) = submit(listOf(ExportJob(item, options)))

    /** Queue the failed jobs again, with their own options; returns how many could be. */
    fun retry(): Int = synchronized(lock) {
        // the item as it is now: its look may have changed, it may have been removed
        val jobs = failures.mapNotNull { f -> app.library.item(f.job.item.id)?.let { f.job.copy(item = it) } }
        failures = emptyList()
        if (jobs.isEmpty()) {
            if (active) _state.update { it?.copy(failures = failures) } else _state.value = null
            persist()
        } else submitLocked(jobs)
        jobs.size
    }

    private fun submit(jobs: List<ExportJob>) = synchronized(lock) { submitLocked(jobs) }

    private fun submitLocked(jobs: List<ExportJob>) {
        if (jobs.isEmpty()) return
        pending.addAll(jobs)
        // update {}: the worker changes progress from another thread
        if (active) _state.update { it?.copy(total = it.total + jobs.size, share = it.share || jobs.any { j -> j.share }, failures = failures) }
        else startLocked()
        persist()
    }

    private fun startLocked() {
        active = true
        _state.value = ExportState(pending.size, 0, 0f, true, failures = failures, share = pending.any { it.share })
        runCatching {
            androidx.core.content.ContextCompat.startForegroundService(app, Intent(app, ExportService::class.java))
        }
        app.appScope.launch { work() }
    }

    private suspend fun work() {
        val saved = ArrayList<Uri>()
        val shared = ArrayList<Uri>()
        var done = 0
        while (true) {
            val job = synchronized(lock) {
                pending.firstOrNull() ?: run {
                    // drained: finish under the lock, so a job submitted now starts a new batch instead of being dropped
                    if (shared.isNotEmpty()) pendingShare.update { (it ?: emptyList()) + shared }
                    // the service watches [state] and stops itself once it has gone foreground
                    _state.update { s -> ExportState(s?.total ?: done, s?.total ?: done, 1f, false, saved.toList(), failures, s?.share == true) }
                    active = false
                    return
                }
            }
            val gen = generation
            _state.update { it?.copy(done = done, current = 0f) }
            // never at the same time as a burst merge (memory)
            val result = runCatching {
                HeavyWork.run { exportOne(job.item, job.options, { generation != gen }) { p -> _state.update { it?.copy(current = p) } } }
            }
            result.exceptionOrNull()?.let { android.util.Log.e("SpektraExport", "export failed", it) }
            val uri = result.getOrNull()
            synchronized(lock) {
                if (pending.firstOrNull() === job) pending.removeFirst()
                when {
                    uri != null -> { saved += uri; if (job.share) shared += uri }
                    // stopped by the user: not a failure to retry
                    generation != gen -> {}
                    else -> failures = failures + ExportFailure(job, ExportQueue.reason(result.exceptionOrNull()))
                }
                done++
                _state.update { it?.copy(done = done, saved = saved.toList(), failures = failures) }
                persist()
            }
        }
    }

    /** Stop after the current item and drop the rest of the queue. */
    fun cancel() = synchronized(lock) {
        generation++
        pending.clear()
        persist()
    }

    /** Forget the finished batch and its failures. */
    fun dismiss() = synchronized(lock) {
        failures = emptyList()
        if (active) _state.update { it?.copy(failures = failures) } else _state.value = null
        persist()
    }

    /** Call under [lock]; the write itself is off the caller's thread, in order. */
    private fun persist() {
        val text = ExportQueue.encode(pending.toList(), failures)
        saver.execute {
            runCatching {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(text)
                tmp.renameTo(file)
            }.onFailure { android.util.Log.w("SpektraExport", "queue not saved", it) }
        }
    }

    private suspend fun exportOne(item: LibraryItem, o: ExportOptions, cancelled: () -> Boolean, progress: (Float) -> Unit): Uri? {
        val setup = withContext(Dispatchers.Default) { app.looks.get(item.look, lutSize = 48) }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val film = app.engine.film(item.look.film).name.replace(' ', '_')
        if (item.isVideo) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "SPK_${stamp}_${item.id}_$film.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SpektraLab")
                put(MediaStore.Video.Media.DATE_TAKEN, item.dateTaken)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val cr = app.contentResolver
            val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                cr.openFileDescriptor(uri, "rw")!!.use { pfd ->
                    app.glWorker.run {
                        VideoExporter(app, this, item, setup, o.videoLongEdge, pfd.fileDescriptor, progress, cancelled).run()
                    }
                }
                cr.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                return uri
            } catch (e: Throwable) {
                cr.delete(uri, null, null)
                throw e
            }
        } else {
            // the GPU must hold the source and the output as single textures
            val maxTex = app.glWorker.run { GlUtil.maxTextureSize }
            val cap = minOf(FULL_SIZE_CAP, maxTex)
            val limit = minOf(if (o.photoLongEdge == 0) cap else o.photoLongEdge, (maxTex / 1.07).toInt())
            val src = withContext(Dispatchers.IO) {
                // decode a bit larger than the target so the format crop keeps resolution
                // raw: full-resolution demosaic unless the sensor has pixels to spare for binning
                Media.loadSource(app, Uri.parse(item.uri), if (item.isRaw) limit else (limit * 1.5).toInt().coerceAtMost(cap), tryRaw = item.isRaw,
                    fast = maxOf(item.width, item.height) > maxTex)
            }
            progress(0.3f)
            val framing = Framing.compute(src.width, src.height, item.look, limit)
            val overlay = OverlayPainter.paint(framing, item.look, item.id.toInt() % 36 + 1, item.dateTaken)
            val out: Bitmap = app.glWorker.run { renderPhoto(src, setup, item.autoEv, framing, overlay, item.id.toInt()) }
            progress(0.8f)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "SPK_${stamp}_${item.id}_$film.${o.photoFormat.ext}")
                put(MediaStore.Images.Media.MIME_TYPE, o.photoFormat.mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SpektraLab")
                // sorted in the album by when the picture was taken, not when it was printed
                put(MediaStore.Images.Media.DATE_TAKEN, item.dateTaken)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val cr = app.contentResolver
            val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                withContext(Dispatchers.IO) {
                    val ok = cr.openOutputStream(uri)!!.use { os ->
                        when (o.photoFormat) {
                            PhotoFormat.JPEG -> out.compress(Bitmap.CompressFormat.JPEG, 95, os)
                            PhotoFormat.PNG -> out.compress(Bitmap.CompressFormat.PNG, 100, os)
                        }
                    }
                    check(ok) { "encoding failed" }
                    if (o.photoFormat == PhotoFormat.JPEG) runCatching {
                        cr.openFileDescriptor(uri, "rw")?.use { pfd ->
                            val exif = androidx.exifinterface.media.ExifInterface(pfd.fileDescriptor)
                            val taken = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(item.dateTaken))
                            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL, taken)
                            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME, taken)
                            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_SOFTWARE, "SpektraLab")
                            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_DESCRIPTION, app.engine.film(item.look.film).name)
                            exif.saveAttributes()
                        }
                    }
                    cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                }
            } catch (e: Throwable) {
                runCatching { cr.delete(uri, null, null) }
                throw e
            }
            progress(1f)
            return uri
        }
    }

    companion object {
        /** "Full size" long edge cap: keeps the half-float GPU buffers within mobile memory. */
        const val FULL_SIZE_CAP = 4096
    }

    /** Open the system share sheet; call from a resumed activity. */
    fun shareUris(context: Context, uris: List<Uri>) {
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        send.type = if (uris.size == 1) app.contentResolver.getType(uris[0]) ?: "*/*" else "*/*"
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(Intent.createChooser(send, null)) }
    }
}

/** Keeps the process alive while exports run in the background. */
class ExportService : Service() {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.export_channel), NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.export_running))
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        // always go foreground first: stopping a service started with startForegroundService
        // before this call crashes the app
        ServiceCompat.startForeground(this, 7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (!watching) {
            watching = true
            scope.launch {
                (application as SpektraApp).exports.state.first { it == null || !it.running }
                ServiceCompat.stopForeground(this@ExportService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object { const val CHANNEL = "export" }
}
