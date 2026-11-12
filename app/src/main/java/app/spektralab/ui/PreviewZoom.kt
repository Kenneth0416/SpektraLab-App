package app.spektralab.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.pow

/**
 * Zoom of the editor preview. Units are fractions of the frame (not px), so the state
 * survives the pane being resized by a fold. A frame point p (0..1, y down) is shown at
 * p * [scale] + ([x], [y]); at rest the picture covers the frame: x in [1 - scale, 0].
 */
data class PreviewZoom(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    val zoomed get() = scale > 1.001f || abs(x) > 1e-4f || abs(y) > 1e-4f

    companion object {
        val NONE = PreviewZoom()
        const val MAX = 6f
        const val DOUBLE_TAP = 2.5f
        /** How far past [MAX] a pinch may stretch (factor) before it stops. */
        const val SCALE_STRETCH = 1.2f
        /** How far past an edge a pan may stretch (fraction of the frame). */
        const val PAN_STRETCH = 0.12f
    }
}

/** Allowed range of one offset axis at [scale]: the picture never uncovers the frame. */
fun offsetRange(scale: Float): ClosedFloatingPointRange<Float> = minOf(0f, 1f - scale)..0f

/** Hard clamp: scale to [1, max], offsets so no gap shows. */
fun PreviewZoom.clamped(max: Float = PreviewZoom.MAX): PreviewZoom {
    val s = scale.coerceIn(1f, max)
    val r = offsetRange(s)
    return PreviewZoom(s, x.coerceIn(r.start, r.endInclusive), y.coerceIn(r.start, r.endInclusive))
}

/**
 * Scale by [factor] about the frame point [cx], [cy] (it stays under the fingers), then
 * move by [dx], [dy]. Past [PreviewZoom.MAX] the pinch only gives a little (rubber band);
 * below 1x it is a hard stop, there is nothing to see there.
 */
fun PreviewZoom.pinch(factor: Float, cx: Float, cy: Float, dx: Float, dy: Float,
                      max: Float = PreviewZoom.MAX): PreviewZoom {
    val raw = scale * factor
    val s = when {
        raw <= 1f -> 1f
        raw > max && factor > 1f -> {
            // each step past the limit counts for a third, up to the stretch cap
            val resisted = if (scale >= max) scale * factor.pow(0.33f) else max * (raw / max).pow(0.33f)
            resisted.coerceAtMost(max * PreviewZoom.SCALE_STRETCH)
        }
        else -> raw
    }
    val k = s / scale
    // keep the content under (cx, cy) fixed: c = p*s + t  =>  t' = c - (c - t) * k
    val nx = cx - (cx - x) * k
    val ny = cy - (cy - y) * k
    return PreviewZoom(s, nx, ny).panBy(dx, dy)
}

/** Move by [dx], [dy]; beyond an edge the picture resists and stops at [PreviewZoom.PAN_STRETCH]. */
fun PreviewZoom.panBy(dx: Float, dy: Float): PreviewZoom {
    if (scale <= 1f) return PreviewZoom.NONE
    val r = offsetRange(scale)
    return copy(x = resist(x, dx, r), y = resist(y, dy, r))
}

private fun resist(v: Float, d: Float, r: ClosedFloatingPointRange<Float>): Float {
    val lim = PreviewZoom.PAN_STRETCH
    val over = when {
        v < r.start -> r.start - v
        v > r.endInclusive -> v - r.endInclusive
        else -> 0f
    }
    val outward = (v <= r.start && d < 0f) || (v >= r.endInclusive && d > 0f)
    val step = if (outward) d * 0.5f * (1f - over / lim).coerceAtLeast(0f) else d
    return (v + step).coerceIn(r.start - lim, r.endInclusive + lim)
}

/**
 * Where a released gesture comes to rest: scale back inside [1, max] about the anchor
 * (the last centroid), then offsets back inside the frame.
 */
fun PreviewZoom.settled(cx: Float = 0.5f, cy: Float = 0.5f, max: Float = PreviewZoom.MAX): PreviewZoom {
    // a hair above 1x would keep the renderer on its (costly) zoomed path for nothing
    if (scale < 1.02f) return PreviewZoom.NONE
    val s = scale.coerceIn(1f, max)
    val k = s / scale
    return PreviewZoom(s, cx - (cx - x) * k, cy - (cy - y) * k).clamped(max)
}

/** Double tap: at 1x zoom in on the tapped frame point, otherwise back to the whole frame. */
fun PreviewZoom.doubleTapTarget(cx: Float, cy: Float, target: Float = PreviewZoom.DOUBLE_TAP): PreviewZoom =
    if (zoomed) PreviewZoom.NONE
    else PreviewZoom(target, cx - cx * target, cy - cy * target).clamped()

/** Straight interpolation; keeps an anchor fixed if both ends keep it (c = p*s + t is affine). */
fun lerp(a: PreviewZoom, b: PreviewZoom, t: Float) = PreviewZoom(
    a.scale + (b.scale - a.scale) * t, a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

/**
 * The visible part of the print for the renderer: (x0, y0, width, height) in canvas uv
 * with y up (GL texture convention). (0, 0, 1, 1) is the whole frame.
 */
fun PreviewZoom.window(): FloatArray {
    val w = 1f / scale
    return floatArrayOf(-x / scale, 1f + y / scale - w, w, w)
}

/** A still finger shows the original only after this long, so taps and pinches never flash it. */
private const val HOLD_MS = 220L

/**
 * All preview touch in one detector, so the gestures cannot fight over a pointer:
 * pinch (zoom about the centroid + pan), one-finger pan while zoomed, double tap, and
 * press-and-hold for the original. Positions handed out are frame units (0..1, y down).
 *  - [onZoom]: new state during a gesture.
 *  - [onRelease]: fingers up after a zoom / pan; anchor of the last pinch, fling velocity per s.
 */
fun Modifier.previewGestures(
    zoom: () -> PreviewZoom,
    onZoom: (PreviewZoom) -> Unit,
    onRelease: (anchor: Offset, velocity: Offset) -> Unit,
    onDoubleTap: (Offset) -> Unit,
    onHold: (Boolean) -> Unit,
): Modifier = pointerInput(Unit) {
    var lastTapUp = 0L
    var lastTapPos = Offset.Zero
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val w = size.width.toFloat().coerceAtLeast(1f)
        val h = size.height.toFloat().coerceAtLeast(1f)
        val vc = viewConfiguration
        // the second tap of a double tap never arms the hold
        val second = down.uptimeMillis - lastTapUp in vc.doubleTapMinTimeMillis..vc.doubleTapTimeoutMillis &&
            (down.position - lastTapPos).getDistance() < 48.dp.toPx()
        lastTapUp = 0L
        val holdAt = down.uptimeMillis + HOLD_MS
        var now = down.uptimeMillis
        var holding = false
        var still = true          // one finger, not past touch slop
        var travel = Offset.Zero
        var transformed = false
        var pinchedLast = false   // the gesture ended in a pinch: no fling
        var anchor = Offset(down.position.x / w, down.position.y / h)
        val velocity = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
        try {
            while (true) {
                val canHold = !second && still && !holding
                val event = if (canHold) {
                    val wait = holdAt - now
                    val e = if (wait > 0) withTimeoutOrNull(wait) { awaitPointerEvent() } else null
                    if (e == null) { holding = true; onHold(true); continue }
                    e
                } else awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                now = event.changes.maxOf { it.uptimeMillis }
                if (pressed.isEmpty()) {
                    val wasHold = holding
                    if (holding) { holding = false; onHold(false) }
                    when {
                        second && still -> onDoubleTap(Offset(down.position.x / w, down.position.y / h))
                        still && !wasHold -> { lastTapUp = now; lastTapPos = down.position }
                    }
                    if (transformed) {
                        val v = if (pinchedLast) Offset.Zero else velocity.calculateVelocity().let { Offset(it.x / w, it.y / h) }
                        onRelease(anchor, v)
                    }
                    break
                }
                if (pressed.size > 1) still = false
                else if (still) {
                    travel += pressed[0].positionChange()
                    if (travel.getDistance() > vc.touchSlop) still = false
                }
                // a second finger or a slide cancels the original before it fights the zoom
                if (!still && holding) { holding = false; onHold(false) }
                if (pressed.size > 1 || (!still && zoom().zoomed)) {
                    val previous = event.calculateCentroid(useCurrent = false)
                    val current = event.calculateCentroid(useCurrent = true)
                    val pan = event.calculatePan()
                    if (previous.isSpecified) {
                        onZoom(zoom().pinch(event.calculateZoom(), previous.x / w, previous.y / h, pan.x / w, pan.y / h))
                    }
                    if (current.isSpecified) anchor = Offset(current.x / w, current.y / h)
                    transformed = true
                    pinchedLast = pressed.size > 1
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
                if (pressed.size == 1) velocity.addPosition(pressed[0].uptimeMillis, pressed[0].position)
                else velocity.resetTracking()
            }
        } finally {
            // gesture cancelled (e.g. the pane recomposed away): never leave the original stuck on
            if (holding) onHold(false)
        }
    }
}
