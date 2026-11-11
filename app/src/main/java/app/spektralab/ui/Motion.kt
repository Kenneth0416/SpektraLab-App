package app.spektralab.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

/*
 * Motion language of the app.
 *
 *  - Lightbox (library): things are physical objects on a table. Strips are tossed down and
 *    settle, crayon marks are drawn by hand, the tube flickers on.
 *  - Darkroom (editor): slow, quiet, precise. Rulers have inertia, lights fade, the print
 *    develops. Springs are critically damped except for small "mechanical" clicks.
 */
object Motion {
    /** Soft overshoot for objects that land (cards, strips, marks). */
    val settle = spring<Float>(dampingRatio = 0.62f, stiffness = 320f)
    /** Quick tactile response for presses. */
    val press = spring<Float>(dampingRatio = 0.55f, stiffness = 900f)
    /** No overshoot, for things that slide into place. */
    val glide = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 380f)
    /** Offset-typed versions for slide transitions. */
    val glideOffset = spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 380f)
    val settleOffset = spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.62f, stiffness = 420f)
    val glideSize = spring<androidx.compose.ui.unit.IntSize>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 380f)
    /** A hand drawing a line: starts decisively, eases at the end. */
    val handDraw = CubicBezierEasing(0.3f, 0.0f, 0.2f, 1.0f)
}

/** Clickable that squashes slightly under the finger and springs back. */
fun Modifier.springClick(scaleDown: Float = 0.94f, enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) scaleDown else 1f, Motion.press, label = "press")
    this.graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(source, LocalIndication.current, enabled = enabled, onClick = onClick)
}

/** 0 -> 1 once when [key] changes (after [delayMs]), e.g. for strokes that draw themselves. */
@Composable
fun drawOnProgress(key: Any?, durationMs: Int = 420, delayMs: Int = 0): State<Float> {
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        if (delayMs > 0) delay(delayMs.toLong())
        a.animateTo(1f, tween(durationMs, easing = Motion.handDraw))
    }
    return a.asState()
}

/** Draw the first [progress] of [path] as a crayon stroke. */
fun DrawScope.drawPartialPath(path: Path, progress: Float, color: Color, width: Float) {
    if (progress <= 0f) return
    val style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
    if (progress >= 1f) { drawPath(path, color, style = style); return }
    val measure = PathMeasure().apply { setPath(path, false) }
    val part = Path()
    measure.getSegment(0f, measure.length * progress, part, true)
    drawPath(part, color, style = style)
}

/**
 * Entrance for objects put on the table one after another: each slides in from
 * [fromX]/[fromY] (px) with a little extra rotation and settles with a soft bounce.
 */
fun Modifier.dealIn(index: Int, key: Any? = Unit, enabled: Boolean = true, fromX: Float = 0f, fromY: Float = 60f,
                    tilt: Float = 0f, stepMs: Int = 55): Modifier = composed {
    // [enabled] is sampled when the object first appears
    val p = remember(key) { Animatable(if (enabled) 0f else 1f) }
    LaunchedEffect(key) {
        if (p.value >= 1f) return@LaunchedEffect
        delay((index.coerceAtMost(8) * stepMs).toLong())
        p.animateTo(1f, Motion.settle)
    }
    graphicsLayer {
        val t = p.value
        alpha = t.coerceIn(0f, 1f)
        translationX = fromX * (1f - t)
        translationY = fromY * (1f - t)
        rotationZ = tilt * (1f - t)
    }
}

/** Fluorescent tube switching on: two false starts, then steady light. Value 0..1. */
@Composable
fun tubeFlicker(key: Any? = Unit): State<Float> {
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        a.animateTo(1f, keyframes {
            durationMillis = 900
            0f at 0
            0.75f at 70 using LinearEasing
            0.15f at 130
            0.2f at 260
            0.9f at 320 using LinearEasing
            0.45f at 400
            1f at 900 using FastOutSlowInEasing
        })
    }
    return a.asState()
}

/** Gentle endless float (for idle hints). Returns -1..1. */
@Composable
fun idleBob(periodMs: Int = 2600): State<Float> =
    rememberInfiniteTransition(label = "bob").animateFloat(-1f, 1f,
        infiniteRepeatable(tween(periodMs, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")

/**
 * Switching rooms. Going into the darkroom the lights go out (fade through black); coming
 * back the lightbox flashes on. The curtain is a plain overlay, so it also covers the GL
 * preview surface, which cannot be cross-faded itself.
 */
@Composable
fun <T> RoomTransition(target: T, isDark: (T) -> Boolean, content: @Composable (T) -> Unit) {
    var shown by remember { mutableStateOf(target) }
    val curtain = remember { Animatable(0f) }
    var curtainColor by remember { mutableStateOf(Pal.dark) }
    LaunchedEffect(target) {
        if (target == shown) return@LaunchedEffect
        val toDark = isDark(target)
        if (isDark(shown) == toDark) { shown = target; return@LaunchedEffect }
        curtainColor = if (toDark) Pal.dark else Pal.glow
        curtain.animateTo(1f, tween(if (toDark) 260 else 160, easing = FastOutSlowInEasing))
        shown = target
        // let the new room compose behind the curtain before lifting it
        delay(60)
        curtain.animateTo(0f, tween(if (toDark) 520 else 380, easing = FastOutSlowInEasing))
    }
    Box(Modifier.fillMaxSize()) {
        content(shown)
        if (curtain.value > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = curtain.value }.background(curtainColor))
    }
}
