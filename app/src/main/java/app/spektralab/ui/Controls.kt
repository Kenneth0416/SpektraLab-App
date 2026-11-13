package app.spektralab.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import app.spektralab.R
import kotlinx.coroutines.launch
import kotlin.math.floor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Ruler slider: a tick scale that slides under a fixed pointer, with a
 * monospace readout. [minus]/[plus] colour the two halves of the scale (the
 * direction the picture shifts). Double tap resets to [default].
 */
@Composable
fun RulerSlider(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    step: Double,
    default: Double,
    accent: Color,
    readout: (Double) -> String,
    minus: Color? = null,
    plus: Color? = null,
    onChange: (Double) -> Unit,
) {
    // the gesture handlers below live across recompositions; always call the latest
    // callback, which closes over the current look (otherwise other edits get reverted)
    val latestOnChange by rememberUpdatedState(onChange)
    val span = range.endInclusive - range.start
    val minor = span / 40.0
    // position of the ruler under the pointer; follows the finger, coasts after a flick,
    // and glides when the value changes from outside (reset, undo, paste, recipe)
    val pos = remember { Animatable(value.toFloat()) }
    var active by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var lastEmitted by remember { mutableStateOf(value) }
    LaunchedEffect(range) { pos.updateBounds(range.start.toFloat(), range.endInclusive.toFloat()) }
    LaunchedEffect(value) {
        if (!active && abs(pos.value - value) > 1e-4) pos.animateTo(value.toFloat(), Motion.glide)
    }
    fun emit(v: Double) {
        val s = snap(v, range, step)
        if (s == lastEmitted) return
        // one tick of the detent for every graduation crossed
        if (floor((s - range.start) / minor + 1e-6) != floor((lastEmitted - range.start) / minor + 1e-6)) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        lastEmitted = s
        latestOnChange(s)
    }
    val shown = pos.value.toDouble()
    val readoutScale by animateFloatAsState(if (active) 1.18f else 1f, Motion.press, label = "readout")
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Pal.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(readout(snap(shown, range, step)), color = accent, fontFamily = Fonts.mono, fontSize = 13.sp,
                modifier = Modifier.graphicsLayer {
                    scaleX = readoutScale; scaleY = readoutScale
                    transformOrigin = TransformOrigin(1f, 0.5f)
                })
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(34.dp)
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(shown.toFloat(), range.start.toFloat()..range.endInclusive.toFloat())
                setProgress { v -> latestOnChange(snap(v.toDouble(), range, step)); true }
            }
            .pointerInput(range, step) {
                detectTapGestures(onDoubleTap = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    lastEmitted = default
                    latestOnChange(default)
                })
            }
            .pointerInput(range, step) {
                // full width of the view = 60% of the range
                val pxPerUnit = (size.width / (span * 0.6)).toFloat()
                val tracker = VelocityTracker()
                var cur = pos.value
                detectHorizontalDragGestures(
                    onDragStart = {
                        active = true
                        tracker.resetTracking()
                        cur = pos.value
                        scope.launch { pos.stop() }
                    },
                    onDragEnd = {
                        val v = -tracker.calculateVelocity().x / pxPerUnit
                        scope.launch {
                            // a flick keeps the ruler coasting, like a weighted dial
                            if (abs(v) * pxPerUnit > 400f) {
                                pos.animateDecay(v, exponentialDecay(frictionMultiplier = 2.6f)) { emit(this.value.toDouble()) }
                            }
                            val snapped = snap(pos.value.toDouble(), range, step)
                            emit(snapped)
                            pos.animateTo(snapped.toFloat(), Motion.glide)
                            active = false
                        }
                    },
                    onDragCancel = { active = false },
                ) { change, dx ->
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    cur = (cur - dx / pxPerUnit).coerceIn(range.start.toFloat(), range.endInclusive.toFloat())
                    val target = cur
                    scope.launch { pos.snapTo(target) }
                    emit(target.toDouble())
                }
            }) {
            Canvas(Modifier.fillMaxSize()) {
                val pxPerUnit = size.width / (span * 0.6)
                val center = size.width / 2
                var t = range.start
                var i = 0
                while (t <= range.endInclusive + 1e-9) {
                    val x = (center + (t - shown) * pxPerUnit).toFloat()
                    if (x in -2f..size.width + 2f) {
                        val major = i % 5 == 0
                        val zero = abs(t - default) < minor / 2
                        val color = when {
                            t < default && minus != null -> minus.copy(alpha = 0.85f)
                            t > default && plus != null -> plus.copy(alpha = 0.85f)
                            else -> Pal.dim
                        }
                        // graduations swell as they pass under the pointer, and fade at the edges
                        val near = (1f - (abs(x - center) / (22.dp.toPx())).coerceAtMost(1f))
                        val edge = (1f - abs(x - center) / center).coerceIn(0f, 1f)
                        val base = if (zero) 0.7f else if (major) 0.5f else 0.28f
                        val h = size.height * (base + 0.16f * near * near)
                        drawLine(color.copy(alpha = color.alpha * (0.25f + 0.75f * edge)), Offset(x, size.height - h), Offset(x, size.height),
                            strokeWidth = (if (major) 2f else 1.2f) + near)
                    }
                    t += minor
                    i++
                }
                // pointer
                val p = Path().apply {
                    moveTo(center - 6.dp.toPx(), 0f); lineTo(center + 6.dp.toPx(), 0f); lineTo(center, 8.dp.toPx()); close()
                }
                drawPath(p, accent)
                drawLine(accent, Offset(center, 6.dp.toPx()), Offset(center, size.height), strokeWidth = 2.5f)
            }
        }
    }
}

private fun snap(v: Double, range: ClosedFloatingPointRange<Double>, step: Double): Double {
    val s = ((v - range.start) / step).roundToInt() * step + range.start
    return (s.coerceIn(range.start, range.endInclusive) * 1000).roundToInt() / 1000.0
}

@Composable
fun Chip(label: String, active: Boolean, accent: Color,
         modifier: Modifier = Modifier.padding(end = 8.dp, bottom = 8.dp), onClick: () -> Unit) {
    val fill by animateColorAsState(if (active) accent else Color.Transparent, tween(220), label = "chipFill")
    val ink by animateColorAsState(if (active) Pal.dink else Pal.text, tween(220), label = "chipInk")
    val line by animateColorAsState(if (active) accent else Color(0xFF5A2A21), tween(220), label = "chipLine")
    // a small pop when it becomes the chosen one
    val pop = remember { Animatable(1f) }
    LaunchedEffect(active) {
        if (active) { pop.snapTo(0.88f); pop.animateTo(1f, Motion.settle) }
    }
    Box(modifier.width(IntrinsicSize.Max).heightIn(min = 48.dp)
        .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
        .clip(RoundedCornerShape(16.dp))
        .background(fill)
        .border(1.dp, line, RoundedCornerShape(16.dp))
        .springClick(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center) {
        Text(label, modifier = Modifier.fillMaxWidth(), color = ink, fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun ToggleRow(label: String, on: Boolean, accent: Color, onToggle: () -> Unit) {
    val color by animateColorAsState(if (on) accent else Pal.dim, tween(200), label = "toggle")
    // a tiny mechanical switch next to the label
    val knob by animateFloatAsState(if (on) 1f else 0f, Motion.settle, label = "knob")
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Pal.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Canvas(Modifier.padding(end = 10.dp).size(26.dp, 12.dp)) {
            drawRoundRect(color.copy(alpha = 0.35f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
            val r = size.height / 2
            drawCircle(color, r * 0.85f, Offset(r + (size.width - 2 * r) * knob, r))
        }
        AnimatedContent(on, label = "onoff", transitionSpec = {
            (slideInVertically { if (targetState) it else -it } + fadeIn()) togetherWith (slideOutVertically { if (targetState) -it else it } + fadeOut())
        }) { v -> Text(stringResource(if (v) R.string.on else R.string.off), color = color, fontFamily = Fonts.mono, fontSize = 13.sp) }
    }
}
fun signed(v: Double, digits: Int = 1, unit: String = ""): String {
    val s = if (digits == 0) abs(v).roundToInt().toString() else "%.${digits}f".format(abs(v))
    val sign = if (v > 1e-9) "+" else if (v < -1e-9) "−" else ""
    return sign + s + unit
}
