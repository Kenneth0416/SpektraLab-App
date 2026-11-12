package app.spektralab.data

import android.app.Application
import android.net.Uri
import app.spektralab.raw.CfaPattern
import app.spektralab.raw.DngTag
import app.spektralab.raw.DngWriter
import app.spektralab.raw.RawImage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RawMemoryTest {
    @Test fun previewAndFullDecodeWaitForBurstAndPreserveOriginalDimensions() {
        val context = RuntimeEnvironment.getApplication()
        val file = File.createTempFile("linear-memory", ".dng", context.cacheDir)
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val source = RawImage(4, 4, ShortArray(16), CfaPattern.RGGB, FloatArray(4), 65535f,
            0, 0, 4, 4, floatArrayOf(1f, 1f, 1f), identity.map { it.toFloat() }.toFloatArray(),
            orientation = 6, tags = mapOf(50721 to DngTag(10, identity),
                50728 to DngTag(5, doubleArrayOf(1.0, 1.0, 1.0))))
        file.outputStream().use { DngWriter.writeLinear(it, 80, 60, ShortArray(80 * 60 * 3) { 16000 }, source, emptyList(), null) }
        val pool = Executors.newFixedThreadPool(2)
        val burst = RawMemoryGate.acquire()
        try {
            val started = CountDownLatch(2)
            val loads = listOf(20, 80).map { edge ->
                pool.submit(Callable {
                    started.countDown()
                    Media.loadSource(context, Uri.fromFile(file), edge, tryRaw = true)
                })
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            for (load in loads) {
                try { load.get(100, TimeUnit.MILLISECONDS); fail("decode ran while burst held memory") }
                catch (_: TimeoutException) { }
            }
            // Burst capture and completion run on different threads in the app.
            Thread { burst.close() }.apply { start(); join() }
            val preview = loads[0].get(10, TimeUnit.SECONDS)
            val full = loads[1].get(10, TimeUnit.SECONDS)
            for (image in listOf(preview, full)) {
                assertNotNull(image.linear)
                assertEquals(60, image.originalWidth)
                assertEquals(80, image.originalHeight)
            }
            assertTrue(maxOf(preview.width, preview.height) <= 20)
            assertEquals(60, full.width)
            assertEquals(80, full.height)
            assertTrue(full.linear!!.red(10, 10).isFinite())
        } finally { burst.close(); pool.shutdownNow(); file.delete() }
    }

    @Test fun duplicateCloseDoesNotReleaseAnotherOwnerAndFailureReleasesPermit() {
        val old = RawMemoryGate.acquire()
        old.close()
        val current = RawMemoryGate.acquire()
        old.close()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val pending = pool.submit(Callable { RawMemoryGate.acquire().use { 42 } })
            try { pending.get(100, TimeUnit.MILLISECONDS); fail("duplicate close released current owner") }
            catch (_: TimeoutException) { }
            current.close()
            assertEquals(42, pending.get(5, TimeUnit.SECONDS))
            try { RawMemoryGate.acquire().use { throw IllegalStateException("decode failed") } }
            catch (_: IllegalStateException) { }
            assertEquals(7, pool.submit(Callable { RawMemoryGate.acquire().use { 7 } }).get(5, TimeUnit.SECONDS))
        } finally { current.close(); pool.shutdownNow() }
    }

    @Test fun knownSizeReadHandlesShortReadsWithoutAvailableOrGrowingBuffer() {
        val expected = ByteArray(127) { it.toByte() }
        val stream = object : ByteArrayInputStream(expected) {
            override fun available(): Int = error("must not size from available")
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(3, len))
        }
        assertArrayEquals(expected, readRawBytes(stream, expected.size.toLong()))
        assertArrayEquals(expected, readRawBytes(ByteArrayInputStream(expected), -1))
    }

    @Test fun knownSizeReadRejectsTruncationAndGrowth() {
        try { readRawBytes(ByteArrayInputStream(ByteArray(3)), 4); fail("accepted truncated file") }
        catch (_: EOFException) { }
        try { readRawBytes(ByteArrayInputStream(ByteArray(4)), 3); fail("accepted changed file") }
        catch (_: IllegalStateException) { }
    }
}
