package app.spektralab.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure maths of the editor preview zoom (frame units, y down). */
class PreviewZoomTest {
    private val eps = 1e-4f

    /** Frame point shown at screen point (sx, sy). */
    private fun PreviewZoom.contentAt(sx: Float, sy: Float) = (sx - x) / scale to (sy - y) / scale

    @Test fun pinchKeepsCentroidUnderFingers() {
        val start = PreviewZoom(2f, -0.3f, -0.6f)
        val before = start.contentAt(0.3f, 0.7f)
        val z = start.pinch(1.5f, 0.3f, 0.7f, 0f, 0f)
        assertEquals(3f, z.scale, eps)
        val after = z.contentAt(0.3f, 0.7f)
        assertEquals(before.first, after.first, eps)
        assertEquals(before.second, after.second, eps)
    }

    @Test fun pinchBelowOneStopsAtWholeFrame() {
        val z = PreviewZoom(1.2f, -0.1f, -0.2f).pinch(0.5f, 0.5f, 0.5f, 0f, 0f)
        assertEquals(PreviewZoom.NONE, z)
    }

    @Test fun pinchPastMaxResistsAndIsCapped() {
        val near = PreviewZoom(PreviewZoom.MAX, -2f, -2f)
        val z = near.pinch(1.5f, 0.5f, 0.5f, 0f, 0f)
        assertTrue(z.scale > PreviewZoom.MAX)
        assertTrue(z.scale < PreviewZoom.MAX * 1.5f)
        var s = near
        repeat(50) { s = s.pinch(1.5f, 0.5f, 0.5f, 0f, 0f) }
        assertEquals(PreviewZoom.MAX * PreviewZoom.SCALE_STRETCH, s.scale, eps)
    }

    @Test fun clampLeavesNoGap() {
        val z = PreviewZoom(2f, 0.4f, -1.7f).clamped()
        assertEquals(0f, z.x, eps)   // left edge cannot come in
        assertEquals(-1f, z.y, eps)  // bottom edge cannot come up
        assertEquals(PreviewZoom.NONE, PreviewZoom(0.5f, 0.2f, 0.2f).clamped())
    }

    @Test fun panRubberBandsAndStops() {
        val z = PreviewZoom(2f, 0f, -0.5f)
        val pushed = z.panBy(0.1f, 0f)
        assertTrue(pushed.x > 0f && pushed.x < 0.1f)
        var far = z
        repeat(100) { far = far.panBy(0.1f, 0f) }
        assertTrue(far.x <= PreviewZoom.PAN_STRETCH + eps)
        // inside the range a pan is 1:1
        assertEquals(-0.3f, z.panBy(0f, 0.2f).y, eps)
        // nothing pans at 1x
        assertEquals(PreviewZoom.NONE, PreviewZoom.NONE.panBy(0.3f, 0.3f))
    }

    @Test fun settleSpringsBackInside() {
        val z = PreviewZoom(7f, 0.05f, -6.2f).settled(0.5f, 0.5f)
        assertEquals(PreviewZoom.MAX, z.scale, eps)
        assertEquals(z, z.clamped())
        assertEquals(PreviewZoom(3f, -1f, -2f), PreviewZoom(3f, -1f, -2f).settled())
    }

    @Test fun doubleTapZoomsOnPointThenBack() {
        val z = PreviewZoom.NONE.doubleTapTarget(0.4f, 0.5f)
        assertEquals(PreviewZoom.DOUBLE_TAP, z.scale, eps)
        val c = z.contentAt(0.4f, 0.5f)
        assertEquals(0.4f, c.first, eps)
        assertEquals(0.5f, c.second, eps)
        // near a corner it is clamped instead of showing a gap
        val corner = PreviewZoom.NONE.doubleTapTarget(0f, 1f)
        assertEquals(0f, corner.x, eps)
        assertEquals(1f - PreviewZoom.DOUBLE_TAP, corner.y, eps)
        assertEquals(PreviewZoom.NONE, z.doubleTapTarget(0.1f, 0.1f))
        assertFalse(PreviewZoom.NONE.zoomed)
    }

    @Test fun windowIsVisibleRectInGlUv() {
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 1f), PreviewZoom.NONE.window(), eps)
        // 2x on the top-left quarter: x 0..0.5, y (up) 0.5..1
        assertArrayEquals(floatArrayOf(0f, 0.5f, 0.5f, 0.5f), PreviewZoom(2f, 0f, 0f).window(), eps)
        // 2x on the bottom-right quarter
        assertArrayEquals(floatArrayOf(0.5f, 0f, 0.5f, 0.5f), PreviewZoom(2f, -1f, -1f).window(), eps)
    }

    @Test fun lerpKeepsSharedAnchor() {
        val a = PreviewZoom.NONE
        val b = a.doubleTapTarget(0.3f, 0.6f)
        val mid = lerp(a, b, 0.37f)
        val c = mid.contentAt(0.3f, 0.6f)
        assertEquals(0.3f, c.first, eps)
        assertEquals(0.6f, c.second, eps)
    }
}
