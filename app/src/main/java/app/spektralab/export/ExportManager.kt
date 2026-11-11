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
import app.spektralab.R
import app.spektralab.SpektraApp
import app.spektralab.data.LibraryItem
import app.spektralab.data.Media
import app.spektralab.gl.GlUtil
import app.spektralab.render.Framing
import app.spektralab.render.OverlayPainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val failed: Int = 0,
    val share: Boolean = false,
)

/** Background exports ("冲印"): every item is rendered in full through the pipeline. */
class ExportManager(private val app: SpektraApp) {
    private val _state = MutableStateFlow<ExportState?>(null)
    val state: StateFlow<ExportState?> = _state
    private var job: Job? = null
    private val extra = java.util.concurrent.ConcurrentLinkedQueue<LibraryItem>()
    /** Finished share exports waiting for an activity in the foreground to open the chooser. */
    val pendingShare = MutableStateFlow<List<Uri>?>(null)
    @Volatile private var cancel = false

    fun isRunning() = _state.value?.running == true

    fun start(items: List<LibraryItem>, options: ExportOptions, share: Boolean) {
        if (isRunning() || items.isEmpty()) return
        cancel = false
        _state.value = ExportState(items.size, 0, 0f, true, share = share)
        runCatching {
            androidx.core.content.ContextCompat.startForegroundService(app, Intent(app, ExportService::class.java))
        }
        job = app.appScope.launch {
            val saved = ArrayList<Uri>()
            var failed = 0
            val queue = ArrayDeque(items)
            var i = 0
            var finished = false
            while (!finished) {
                while (!cancel) {
                    // shots taken while printing join the batch
                    val item = queue.removeFirstOrNull() ?: extra.poll() ?: break
                    _state.value = _state.value?.copy(done = i, current = 0f)
                    val uri = runCatching { exportOne(item, options) { p -> _state.value = _state.value?.copy(current = p) } }
                        .onFailure { android.util.Log.e("SpektraExport", "export failed", it) }
                        .getOrNull()
                    if (uri != null) saved += uri else failed++
                    i++
                }
                // finish under the same lock as enqueue(), so a late shot is never dropped
                synchronized(this@ExportManager) {
                    if (cancel || extra.isEmpty()) {
                        val total = _state.value?.total ?: items.size
                        if (share && saved.isNotEmpty()) pendingShare.value = saved
                        // the service watches [state] and stops itself once it has gone foreground
                        _state.value = ExportState(total, total, 1f, false, saved, failed, share)
                        finished = true
                    }
                }
            }
        }
    }

    /** Print one more item: joins the running batch, or starts one. */
    @Synchronized fun enqueue(item: LibraryItem, options: ExportOptions) {
        if (isRunning()) {
            extra.add(item)
            _state.value = _state.value?.let { it.copy(total = it.total + 1) }
        } else start(listOf(item), options, share = false)
    }

    fun cancel() { cancel = true; extra.clear() }

    fun dismiss() { if (!isRunning()) _state.value = null }

    private suspend fun exportOne(item: LibraryItem, o: ExportOptions, progress: (Float) -> Unit): Uri? {
        val setup = withContext(Dispatchers.Default) { app.looks.get(item.look, lutSize = 48) }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val film = app.engine.film(item.look.film).name.replace(' ', '_')
        if (item.isVideo) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "SPK_${stamp}_${item.id}_$film.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SpektraLab")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val cr = app.contentResolver
            val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                cr.openFileDescriptor(uri, "rw")!!.use { pfd ->
                    app.glWorker.run {
                        VideoExporter(app, this, item, setup, o.videoLongEdge, pfd.fileDescriptor, progress) { cancel }.run()
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
