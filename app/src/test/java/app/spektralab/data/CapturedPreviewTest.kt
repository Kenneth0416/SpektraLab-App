package app.spektralab.data

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.spektralab.HeavyWork
import app.spektralab.engine.Half
import app.spektralab.engine.Look
import app.spektralab.raw.LinearImage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CapturedPreviewTest {
    private fun image(value: Float, w: Int = 8, h: Int = 6): LinearImage {
        val buf = ByteBuffer.allocateDirect(w * h * 8).order(ByteOrder.nativeOrder())
        for (i in 0 until w * h) for (c in 0..3) buf.putShort(i * 8 + c * 2, Half.fromFloat(if (c == 3) 1f else value))
        return LinearImage(w, h, buf)
    }
    private fun item(uri: String) = LibraryItem(901, uri, false, true, 3056, 4064, 0, 0, 1,
        0f, Look.DEFAULT, "capture.dng")

    @Test fun captureImportAndCachedPreviewCompleteWhileBurstStillOwnsRawPermit() {
        val context = RuntimeEnvironment.getApplication()
        val rawOpens = AtomicInteger()
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "image/x-adobe-dng"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun openAssetFile(uri: Uri, mode: String): android.content.res.AssetFileDescriptor {
                rawOpens.incrementAndGet()
                throw FileNotFoundException("must not decode the original while merging")
            }
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = throw FileNotFoundException("optional EXIF unavailable")
        }
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "capture-proxy"; exported = true })
        ShadowContentResolver.registerProviderInternal("capture-proxy", provider)
        val uri = Uri.parse("content://capture-proxy/first.dng")
        val cache = QuickSources(context).apply { forget(listOf(901)) }
        val preview = RawPreview(image(0.25f), 3056, 4064, 1.25f)
        val pool = Executors.newFixedThreadPool(2)
        val burst = RawMemoryGate.acquire()
        try {
            val imported = pool.submit(Callable { Media.importItem(context, uri, 901, cache, preview) }).get(3, TimeUnit.SECONDS)
            assertNotNull(imported)
            assertEquals(3056, imported!!.width); assertEquals(4064, imported.height)
            assertEquals(1.25f, imported.autoEv, 0f)
            // One optional EXIF open is allowed; a full RAW decode cannot succeed
            // against this provider and would leave import null / need extra opens.
            assertTrue(rawOpens.get() <= 1)
            val small = pool.submit(Callable { cache.load(imported) }).get(3, TimeUnit.SECONDS)!!
            assertEquals(0.25f, small.linear!!.red(2, 2), 0f)
            assertEquals(3056, small.originalWidth); assertEquals(4064, small.originalHeight)
            // Creating/closing a proxy must not accidentally release someone else's permit.
            small.close()
            val export = pool.submit(Callable { HeavyWork.blocking { 42 } })
            try { export.get(100, TimeUnit.MILLISECONDS); fail("proxy released the burst permit") }
            catch (_: TimeoutException) { }
            burst.close()
            assertEquals(42, export.get(3, TimeUnit.SECONDS))
            assertNotNull(QuickSources(context).load(imported))
        } finally { burst.close(); pool.shutdownNow(); cache.forget(listOf(901)) }
    }

    @Test fun distinctSourceUrisIncludingStringHashCollisionsNeverReusePixels() {
        val context = RuntimeEnvironment.getApplication()
        val first = item("content://capture-proxy/Aa")
        val merged = item("content://capture-proxy/BB")
        assertEquals(first.uri.hashCode(), merged.uri.hashCode())
        val cache = QuickSources(context).apply { forget(listOf(901)) }
        try {
            cache.keep(first, SourceImage(null, image(0.25f)))
            assertNull(cache.load(merged))
            cache.keep(merged, SourceImage(null, image(0.75f)))
            for (reader in listOf(cache, QuickSources(context))) {
                assertEquals(0.25f, reader.load(first)!!.linear!!.red(0, 0), 0f)
                assertEquals(0.75f, reader.load(merged)!!.linear!!.red(0, 0), 0f)
            }
        } finally { cache.forget(listOf(901)) }
        assertNull(QuickSources(context).load(first))
        assertNull(QuickSources(context).load(merged))
    }

    @Test fun proxyPersistenceAndConsumersDoNotMutateEachOthersBufferPositions() {
        val context = RuntimeEnvironment.getApplication()
        val frame = item("content://capture-proxy/cursors")
        val cache = QuickSources(context).apply { forget(listOf(901)) }
        val linear = image(0.5f)
        linear.halfRgba.position(16)
        try {
            cache.keep(frame, SourceImage(null, linear))
            assertEquals(16, linear.halfRgba.position())
            val a = cache.load(frame)!!
            val b = cache.load(frame)!!
            assertNotSame(a.linear!!.halfRgba, b.linear!!.halfRgba)
            a.linear!!.halfRgba.position(24)
            assertEquals(0, b.linear!!.halfRgba.position())
            assertEquals(0.5f, b.linear!!.red(0, 0), 0f)
            assertEquals(ByteOrder.nativeOrder(), b.linear!!.halfRgba.order())
            assertEquals(0.5f, QuickSources(context).load(frame)!!.linear!!.red(0, 0), 0f)
        } finally { cache.forget(listOf(901)) }
    }

    @Test fun mergedPreviewUsesMergedLevelsGainPlanesAndReferenceOrientation() {
        val cfa = app.spektralab.raw.CfaPattern(2, 2, intArrayOf(0, 1, 1, 2))
        val reference = app.spektralab.raw.RawImage(16, 16, ShortArray(256), cfa, FloatArray(4) { 64f }, 1023f,
            3, 5, 8, 6, floatArrayOf(2f, 1f, 1.5f), floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), orientation = 6)
        val rgb = ShortArray(8 * 6 * 3) { i -> (10000 + i / 3 * 100).toShort() }
        val maps = (0..2).map { c -> app.spektralab.raw.GainMap(0, 0, 6, 8, 1, 1,
            1, 1, 1.0, 1.0, 0.0, 0.0, floatArrayOf(1f + c * 0.1f), plane = c) }
        val preview = RawPreview.merged(app.spektralab.raw.MergedRaw(8, 6, rgb, maps, null, 3f), reference)
        assertEquals(6, preview.originalWidth); assertEquals(8, preview.originalHeight)
        assertEquals(6, preview.image.width); assertEquals(8, preview.image.height)
        for (y in 0 until 6) for (x in 0 until 8) {
            val n = (10000 + (y * 8 + x) * 100) / 65535f
            assertEquals(n * 2f, preview.image.red(5 - y, x), 0.001f)
            assertEquals(n * 1.1f, preview.image.green(5 - y, x), 0.001f)
            assertEquals(n * 1.5f * 1.2f, preview.image.blue(5 - y, x), 0.001f)
        }
    }

    @Test fun proxyStorageIsBoundedAndKeepsSensorDimensionsSeparate() {
        val context = RuntimeEnvironment.getApplication()
        val frame = item("content://capture-proxy/large")
        val cache = QuickSources(context).apply { forget(listOf(901)) }
        try {
            cache.keep(frame, SourceImage(null, image(0.25f, 1602, 1200)))
            val small = cache.load(frame)!!
            assertTrue(maxOf(small.width, small.height) <= QuickSources.EDGE)
            assertEquals(3056, small.originalWidth); assertEquals(4064, small.originalHeight)
            assertEquals(0.25f, small.linear!!.red(0, 0), 0f)
        } finally { cache.forget(listOf(901)) }
    }
}
