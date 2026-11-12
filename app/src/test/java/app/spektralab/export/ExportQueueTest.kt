package app.spektralab.export

import app.spektralab.HeavyWork
import app.spektralab.data.LibraryItem
import app.spektralab.engine.Look
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Plain JVM: the queue file, restore and failure reasons, and the heavy-work lock. */
class ExportQueueTest {
    private fun item(id: Long) = LibraryItem(id, "content://media/$id", false, false, 4000, 3000, 0, 0, 0L, 0f, Look.DEFAULT, "IMG_$id")

    @Test fun roundTripKeepsOptionsAndShare() {
        val a = ExportJob(item(1), ExportOptions(PhotoFormat.PNG, 2048, 3840), share = true)
        val b = ExportJob(item(2), ExportOptions())
        val f = ExportFailure(ExportJob(item(3), ExportOptions(photoLongEdge = 3400)), ExportQueue.STORAGE)
        val saved = ExportQueue.decode(ExportQueue.encode(listOf(a, b), listOf(f)))
        assertEquals(listOf(ExportQueue.Stored(1, a.options, true), ExportQueue.Stored(2, b.options, false)), saved.pending)
        assertEquals(listOf(ExportQueue.Stored(3, f.job.options, false, ExportQueue.STORAGE)), saved.failed)
    }

    @Test fun restoreDropsItemsNoLongerInTheLibrary() {
        val text = ExportQueue.encode(listOf(ExportJob(item(1), ExportOptions()), ExportJob(item(2), ExportOptions())),
            listOf(ExportFailure(ExportJob(item(3), ExportOptions()), ExportQueue.SOURCE), ExportFailure(ExportJob(item(4), ExportOptions()), ExportQueue.MEMORY)))
        val library = mapOf(2L to item(2).copy(name = "edited"), 4L to item(4))
        val (jobs, failed) = ExportQueue.resolve(ExportQueue.decode(text)) { library[it] }
        assertEquals(listOf(2L), jobs.map { it.item.id })
        assertEquals("edited", jobs[0].item.name)
        assertEquals(listOf(4L to ExportQueue.MEMORY), failed.map { it.job.item.id to it.reason })
    }

    @Test fun brokenFileIsAnEmptyQueue() {
        assertTrue(ExportQueue.decode("not json").pending.isEmpty())
        val saved = ExportQueue.decode("""{"pending":[{"share":true},{"id":5,"format":"WEBP"}]}""")
        assertEquals(listOf(ExportQueue.Stored(5, ExportOptions(), false)), saved.pending)
        assertTrue(saved.failed.isEmpty())
    }

    @Test fun failureReasons() {
        assertEquals(ExportQueue.MEMORY, ExportQueue.reason(OutOfMemoryError()))
        assertEquals(ExportQueue.MEMORY, ExportQueue.reason(IllegalArgumentException("image too large for GPU")))
        assertEquals(ExportQueue.STORAGE, ExportQueue.reason(IOException("write failed", IOException("write failed: ENOSPC (No space left on device)"))))
        assertEquals(ExportQueue.CODEC, ExportQueue.reason(IllegalStateException("no recordable EGL config: video export unsupported on this GPU")))
        assertEquals(ExportQueue.SOURCE, ExportQueue.reason(FileNotFoundException("content://media/1")))
        assertEquals(ExportQueue.SOURCE, ExportQueue.reason(IllegalStateException("no video track")))
        assertEquals(ExportQueue.OTHER, ExportQueue.reason(IllegalStateException("encoding failed")))
        assertEquals(ExportQueue.OTHER, ExportQueue.reason(null))
    }

    @Test fun heavyWorkNeverOverlapsBetweenThreadsAndCoroutines() {
        val inside = AtomicInteger()
        val overlapped = AtomicBoolean(false)
        fun enter() { if (inside.incrementAndGet() > 1) overlapped.set(true) }
        val merges = (0 until 3).map {
            thread { repeat(5) { HeavyWork.blocking { enter(); Thread.sleep(2); inside.decrementAndGet() } } }
        }
        runBlocking {
            repeat(15) { HeavyWork.run { enter(); delay(2); inside.decrementAndGet() } }
        }
        merges.forEach { it.join() }
        assertFalse(overlapped.get())
    }
}
