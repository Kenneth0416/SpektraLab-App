package app.spektralab

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.core.content.res.ResourcesCompat
import app.spektralab.data.Library
import app.spektralab.data.LibraryItem
import app.spektralab.data.LookJson
import app.spektralab.data.Media
import app.spektralab.engine.EngineData
import app.spektralab.engine.Look
import app.spektralab.engine.LookSetup
import app.spektralab.export.ExportManager
import app.spektralab.render.Framing
import app.spektralab.render.GlWorker
import app.spektralab.render.OverlayPainter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class SpektraApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var engine: EngineData
        private set
    lateinit var library: Library
        private set
    val glWorker = GlWorker()
    lateinit var looks: LookCache
        private set
    lateinit var thumbs: Thumbnails
        private set
    lateinit var exports: ExportManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        engine = EngineData { path -> assets.open("spektra/$path") }
        library = Library(this)
        looks = LookCache(engine)
        thumbs = Thumbnails(this)
        exports = ExportManager(this)
        runCatching { ResourcesCompat.getFont(this, R.font.martian_mono) }.getOrNull()?.let { OverlayPainter.monoTypeface = it }
    }

    companion object {
        lateinit var instance: SpektraApp
            private set
    }
}

/** LookSetup is the expensive part (spectral print stage over a 3D grid): keep a few, share LUTs. */
class LookCache(private val data: EngineData) {
    private val cache = LruCache<String, LookSetup>(6)
    private val luts = LruCache<String, app.spektralab.engine.Lut3>(8)

    @Synchronized fun get(look: Look, lutSize: Int = 36): LookSetup {
        val key = LookJson.toJson(look).toString() + "/$lutSize"
        cache.get(key)?.let { return it }
        val lutKey = LookSetup.lutKey(look, lutSize)
        return LookSetup.create(look, data, lutSize, luts.get(lutKey)).also {
            cache.put(key, it)
            luts.put(lutKey, it.outputLut)
        }
    }
}

/** Rendered library thumbnails (through the film pipeline), memory + disk cached. */
class Thumbnails(private val app: SpektraApp) {
    private val mem = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val dir = File(app.cacheDir, "thumbs").apply { mkdirs() }
    private val mutex = Mutex()

    private fun key(item: LibraryItem) =
        "${item.id}_${Integer.toHexString(item.uri.hashCode())}_${Integer.toHexString(LookJson.toJson(item.look).toString().hashCode())}"

    /** Drop memory and disk thumbnails of removed items. */
    fun forget(ids: Collection<Long>) {
        val prefixes = ids.map { "${it}_" }
        mem.snapshot().keys.filter { k -> prefixes.any { k.startsWith(it) } }.forEach { mem.remove(it) }
        dir.listFiles()?.filter { f -> prefixes.any { f.name.startsWith(it) } }?.forEach { it.delete() }
    }

    fun cached(item: LibraryItem): Bitmap? = mem.get(key(item))

    suspend fun get(item: LibraryItem): Bitmap? {
        val k = key(item)
        mem.get(k)?.let { return it }
        return mutex.withLock {
            mem.get(k)?.let { return@withLock it }
            val f = File(dir, "$k.jpg")
            val bmp = withContext(Dispatchers.IO) {
                if (f.exists()) BitmapFactory.decodeFile(f.path) else null
            } ?: render(item)?.also { b ->
                withContext(Dispatchers.IO) { runCatching { f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 88, it) } } }
            }
            bmp?.also { mem.put(k, it) }
        }
    }

    private suspend fun render(item: LibraryItem): Bitmap? {
        val src = withContext(Dispatchers.IO) {
            val uri = Uri.parse(item.uri)
            runCatching {
                if (item.isVideo) Media.videoFrame(app, uri, minOf(item.durationMs * 500, 1_000_000L), 640)
                else Media.decodeImage(app, uri, 640)
            }.getOrNull()
        } ?: return null
        val setup = withContext(Dispatchers.Default) { app.looks.get(item.look) }
        val look = item.look.copy(border = "none", dateStamp = false)
        val framing = Framing.compute(src.width, src.height, look, 480)
        return runCatching {
            app.glWorker.run { renderPhoto(src, setup, item.autoEv, framing, null, item.id.toInt()) }
        }.getOrNull()
    }
}
