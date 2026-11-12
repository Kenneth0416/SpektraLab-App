package app.spektralab.data

import android.app.Application
import android.content.ContentProvider
import android.graphics.Bitmap
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.spektralab.HeavyWork
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RawMemoryTest {
    @Test fun rawLeaseExcludesExportForTheWholeDevelopedLifetime() {
        val pool = Executors.newFixedThreadPool(2)
        val started = CountDownLatch(1)
        val burst = RawMemoryGate.acquire()
        try {
            val export = pool.submit(Callable {
                started.countDown()
                HeavyWork.blocking { 42 }
            })
            assertTrue(started.await(5, TimeUnit.SECONDS))
            try { export.get(150, TimeUnit.MILLISECONDS); fail("export ran while RAW arrays were retained") }
            catch (_: TimeoutException) { }
            // Burst completion and the export worker are different threads in the app.
            Thread { burst.close() }.apply { start(); join() }
            assertEquals(42, export.get(5, TimeUnit.SECONDS))
        } finally { burst.close(); pool.shutdownNow() }
    }

    @Test fun callerAlreadyInsideHeavyWorkDoesNotDeadlock() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            assertEquals(7, pool.submit(Callable {
                HeavyWork.blocking { RawMemoryGate.acquire(alreadyHeld = true).use { 7 } }
            }).get(2, TimeUnit.SECONDS))
        } finally { pool.shutdownNow() }
    }

    @Test fun duplicateCloseDoesNotReleaseAnotherOwnerAndFailureReleasesPermit() {
        val old = RawMemoryGate.acquire()
        old.close()
        val current = RawMemoryGate.acquire()
        old.close()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val pending = pool.submit(Callable { RawMemoryGate.acquire().use { 42 } })
            try { pending.get(100, TimeUnit.MILLISECONDS); fail("duplicate close released the current owner") }
            catch (_: TimeoutException) { }
            current.close()
            assertEquals(42, pending.get(5, TimeUnit.SECONDS))
            try { RawMemoryGate.acquire().use { throw IllegalStateException("decode failed") } }
            catch (_: IllegalStateException) { }
            assertEquals(7, pool.submit(Callable { RawMemoryGate.acquire().use { 7 } }).get(5, TimeUnit.SECONDS))
        } finally { current.close(); pool.shutdownNow() }
    }

    @Test fun sharedImageReleasesOnlyAfterEveryOwnerCloses() {
        val gate = RawMemoryGate.acquire()
        val image = SourceImage(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888), null, memory = gate)
        val shared = image.share()
        val pool = Executors.newSingleThreadExecutor()
        try {
            image.close()
            val pending = pool.submit(Callable { RawMemoryGate.acquire().use { 1 } })
            try { pending.get(100, TimeUnit.MILLISECONDS); fail("one owner released a shared lease") }
            catch (_: TimeoutException) { }
            shared.close()
            assertEquals(1, pending.get(5, TimeUnit.SECONDS))
            // The same handle is closed by both the owner and this cleanup. The second close
            // must not drive the shared count negative or release a later lease.
            image.close()
            shared.close()
            val again = RawMemoryGate.acquire()
            val blocked = pool.submit(Callable { RawMemoryGate.acquire().use { 3 } })
            try { blocked.get(100, TimeUnit.MILLISECONDS); fail("a repeated close released the next lease") }
            catch (_: TimeoutException) { }
            again.close()
            assertEquals(3, blocked.get(5, TimeUnit.SECONDS))
        } finally { pool.shutdownNow() }
    }

    @Test fun cancelledDecodeReleasesTheGate() {
        val started = CompletableDeferred<Unit>()
        val cancel = CompletableDeferred<Unit>()
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
            withContext(Dispatchers.IO) {
                RawMemoryGate.acquire().use {
                    started.complete(Unit)
                    cancel.await()
                    throw CancellationException("source changed")
                }
            }
        }
        runBlocking {
            started.await()
            val pending = CompletableDeferred<Int>()
            val waiter = launch(Dispatchers.IO) { RawMemoryGate.acquire().use { pending.complete(5) } }
            yield()
            assertFalse(pending.isCompleted)
            job.cancel()
            assertEquals(5, kotlinx.coroutines.withTimeout(5_000) { pending.await() })
            waiter.cancel()
        }
    }

    @Test fun knownSizeReadHandlesShortReadsWithoutGrowingBuffer() {
        val expected = ByteArray(127) { it.toByte() }
        val stream = object : ByteArrayInputStream(expected) {
            override fun available(): Int = error("must not size from available")
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(3, len))
        }
        assertArrayEquals(expected, readRawBytes(stream, expected.size.toLong()))
        assertArrayEquals(expected, readRawBytes(ByteArrayInputStream(expected), -1))
    }

    @Test fun knownSizeReadRejectsTruncationAndDoesNotConsumePastDeclaredLength() {
        try { readRawBytes(ByteArrayInputStream(ByteArray(3)), 4); fail("accepted a truncated file") }
        catch (_: EOFException) { }
        val stream = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))
        assertArrayEquals(byteArrayOf(1, 2, 3), readDeclaredBytes(stream, 0, 3))
        assertEquals(4, stream.read())
    }

    @Test fun declaredSliceSkipsOffsetAndStopsAtLength() {
        val file = byteArrayOf(9, 8, 7, 1, 2, 3, 4, 6)
        val stream = ByteArrayInputStream(file)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), readDeclaredBytes(stream, 3, 4))
        try { readDeclaredBytes(ByteArrayInputStream(file), 7, 2); fail("read past the slice") }
        catch (_: EOFException) { }
        try { readDeclaredBytes(ByteArrayInputStream(file), 9, 1); fail("accepted an offset past EOF") }
        catch (_: EOFException) { }
    }

    @Test fun contentProviderSliceDoesNotReadBytesOutsideTheAsset() {
        val context = RuntimeEnvironment.getApplication()
        val backing = File.createTempFile("sliced-raw", ".bin", context.cacheDir)
        val payload = byteArrayOf(10, 20, 30, 40)
        backing.writeBytes(ByteArray(8) { 0x55 } + payload + ByteArray(8) { 0x66 })
        val reads = AtomicInteger()
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "image/x-adobe-dng"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                               selectionArgs: Array<out String>?, sortOrder: String?) : Cursor? = null
            override fun insert(uri: Uri, values: ContentValues?) = null
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun openAssetFile(uri: Uri, mode: String) =
                android.content.res.AssetFileDescriptor(
                    ParcelFileDescriptor.open(backing, ParcelFileDescriptor.MODE_READ_ONLY), 8, payload.size.toLong())
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                reads.incrementAndGet()
                throw FileNotFoundException("whole-file descriptor must not be used for a slice")
            }
        }
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply {
            authority = "raw-slice"; exported = true
        })
        ShadowContentResolver.registerProviderInternal("raw-slice", provider)
        val descriptor = context.contentResolver.openAssetFileDescriptor(Uri.parse("content://raw-slice/frame"), "r")!!
        descriptor.use {
            val bytes = readDeclaredBytes(it.createInputStream(), it.startOffset, it.declaredLength)
            assertArrayEquals(payload, bytes)
            assertEquals(8, it.startOffset)
            assertEquals(payload.size.toLong(), it.declaredLength)
            assertTrue("stat size must not become the read length", backing.length() > payload.size)
        }
        assertEquals(0, reads.get())
        backing.delete()
    }

    @Test fun heavyWorkAndRawGateNeverOverlap() {
        val inside = AtomicInteger()
        val overlapped = java.util.concurrent.atomic.AtomicBoolean(false)
        fun enter() { if (inside.incrementAndGet() > 1) overlapped.set(true) }
        val merges = (0 until 3).map {
            Thread {
                repeat(4) { RawMemoryGate.acquire().use { enter(); Thread.sleep(2); inside.decrementAndGet() } }
            }.apply { start() }
        }
        kotlinx.coroutines.runBlocking {
            repeat(8) { HeavyWork.run { enter(); kotlinx.coroutines.delay(2); inside.decrementAndGet() } }
        }
        merges.forEach { it.join() }
        assertFalse(overlapped.get())
    }
}
