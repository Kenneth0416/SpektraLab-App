package app.spektralab.data

import android.util.Half
import app.spektralab.raw.LinearImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
class QuickSourcesTest {
    private fun image(w: Int, h: Int, value: (Int, Int) -> Float): LinearImage {
        val buf = ByteBuffer.allocateDirect(w * h * 8).order(ByteOrder.nativeOrder())
        for (y in 0 until h) for (x in 0 until w) {
            val o = (y * w + x) * 8
            buf.putShort(o, Half.toHalf(value(x, y))); buf.putShort(o + 2, Half.toHalf(2 * value(x, y)))
            buf.putShort(o + 4, Half.toHalf(0f)); buf.putShort(o + 6, Half.toHalf(1f))
        }
        return LinearImage(w, h, buf)
    }

    @Test fun shrinkBoxAveragesByAnIntegerFactor() {
        // 9x6 -> long edge at most 3: factor 3, each output pixel the mean of a 3x3 block
        val small = QuickSources.shrink(image(9, 6) { x, y -> (x / 3 + 10 * (y / 3)).toFloat() + if (x % 3 == 1) 0.5f else -0.25f }, 3)
        assertEquals(3, small.width); assertEquals(2, small.height)
        assertEquals(0f, small.red(0, 0), 1e-3f)
        assertEquals(12f, small.red(2, 1), 1e-3f)
        assertEquals(24f, small.green(2, 1), 1e-3f)
        assertEquals(0f, small.blue(1, 1), 1e-3f)
    }

    @Test fun shrinkLeavesSmallImagesAlone() {
        val img = image(4, 3) { _, _ -> 1f }
        assertSame(img, QuickSources.shrink(img, 800))
    }
}
