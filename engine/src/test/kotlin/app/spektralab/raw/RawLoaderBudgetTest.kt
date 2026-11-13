package app.spektralab.raw

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Whole-file reads must stop before allocating when the file is over the heap budget. */
class RawLoaderBudgetTest {
    private class ExplodingSource(override val size: Long) : ByteSource {
        var reads = 0
        override fun read(pos: Long, dst: ByteArray, off: Int, len: Int) {
            reads++
            error("must not read a file over the budget")
        }
        override fun readAll(): ByteArray {
            reads++
            error("must not allocate a file over the budget")
        }
    }

    @Test fun aFileOverTheHardCapIsNotRead() {
        val src = ExplodingSource(RawLoader.MAX_WHOLE_FILE + 1)
        assertNull(RawLoader.open(src))
        assertEquals(0, src.reads)
        assertFalse(RawReader.withinBudget(RawLoader.MAX_WHOLE_FILE + 1))
        assertTrue(RawReader.withinBudget(RawLoader.MAX_WHOLE_FILE))
    }

    @Test fun aCallerBudgetSmallerThanTheFileIsHonoured() {
        val src = ExplodingSource(1000)
        assertNull(RawLoader.open(src, maxWholeFile = 10))
        assertEquals(0, src.reads)
        assertNull(RawLoader.open(src, maxWholeFile = 0))
        assertEquals(0, src.reads)
    }

    @Test fun anEmptyBufferIsNotARaw() {
        assertNull(RawReader.read(ByteArray(0)))
        assertNull(RawLoader.open(ByteArraySource(ByteArray(0))))
    }
}
