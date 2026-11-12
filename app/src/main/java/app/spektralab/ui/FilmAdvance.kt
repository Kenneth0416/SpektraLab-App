package app.spektralab.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import app.spektralab.R
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Film advance lever. Swing it with the thumb (drag towards the right) through its
 * stroke: the ratchet clicks along the way, and a full stroke advances the film and
 * cocks the shutter. A half stroke springs back and does nothing, like the real thing.
 *
 * @param cocked the film is already advanced: the lever swings freely without effect
 * @param nudge changes when the shutter was pressed without advancing; the lever twitches
 * @param autoStroke changes when the motor drive should perform a stroke by itself
 */
@Composable
fun FilmAdvanceLever(cocked: Boolean, accent: Color, nudge: Int, autoStroke: Int, modifier: Modifier = Modifier,
                     guidanceEnabled: Boolean = true, onAdvance: () -> Unit) {
    val p = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val advance by rememberUpdatedState(onAdvance)
    val isCocked by rememberUpdatedState(cocked)
    val leverDescription = stringResource(if (cocked) R.string.film_lever_advanced else R.string.film_lever_action)
    var interacting by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var guideReady by remember { mutableStateOf(false) }
    val demonstration = remember { Animatable(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val waiting = !cocked && guidanceEnabled && resumed && !interacting
    LaunchedEffect(waiting, interaction) {
        guideReady = false
        if (waiting) {
            delay(10_000)
            guideReady = true
        }
    }
    val showGuide = waiting && guideReady
    LaunchedEffect(showGuide) {
        demonstration.snapTo(0f)
        if (showGuide) while (true) {
            demonstration.animateTo(1f, tween(1400))
            delay(600)
            demonstration.snapTo(0f)
        }
    }

    suspend fun finishStroke() {
        guideReady = false
        interaction++
        p.animateTo(1f, tween(90))
        if (!isCocked) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            advance()
        }
        p.animateTo(0f, Motion.settle)
    }
    LaunchedEffect(nudge) {
        if (nudge > 0) { p.animateTo(0.16f, tween(70)); p.animateTo(0f, Motion.press) }
    }
    LaunchedEffect(autoStroke) {
        if (autoStroke > 0) {
            // motor drive: one quick stroke with the ratchet ticking
            for (i in 1..5) { p.animateTo(i / 5f * 0.95f, tween(45)); haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
            finishStroke()
        }
    }

    Box(modifier.size(112.dp, 84.dp)
        .semantics {
            contentDescription = leverDescription
            role = Role.Button
            onClick { scope.launch { finishStroke() }; true }
        }
        .pointerInput(Unit) {
            val travel = 96.dp.toPx()
            var cur = 0f
            var lastTick = 0
            detectDragGestures(
                onDragStart = {
                    interacting = true; guideReady = false; interaction++
                    cur = p.value; lastTick = (cur * 8).toInt(); scope.launch { p.stop() }
                },
                onDragEnd = {
                    scope.launch {
                        try { if (p.value >= 0.8f) finishStroke() else p.animateTo(0f, Motion.settle) }
                        finally { interacting = false }
                    }
                },
                onDragCancel = {
                    scope.launch {
                        try { p.animateTo(0f, Motion.settle) }
                        finally { interacting = false }
                    }
                },
            ) { change, drag ->
                change.consume()
                // the thumb pushes the lever tip to the right and slightly up
                cur = (cur + (drag.x - drag.y * 0.5f) / travel).coerceIn(0f, 1f)
                val tick = (cur * 8).toInt()
                if (tick != lastTick) { lastTick = tick; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                val target = cur
                scope.launch { p.snapTo(target) }
            }
        }) {
        Canvas(Modifier.matchParentSize()) {
            val pivot = Offset(size.width * 0.62f, size.height - 16.dp.toPx())
            val arm = 58.dp.toPx()
            // rest: pointing left; full stroke: 125 degrees further, up and to the right
            val angle = (180f + 125f * p.value) * PI.toFloat() / 180f
            val tip = Offset(pivot.x + arm * cos(angle), pivot.y + arm * sin(angle))
            val metal = Color(0xFFB9B2A6)
            // Only show the direction cue after ten seconds of idle manual transport.
            if (showGuide) {
                drawArc(accent.copy(alpha = 0.65f), 180f, 125f, false,
                    Offset(pivot.x - arm, pivot.y - arm), androidx.compose.ui.geometry.Size(arm * 2, arm * 2),
                    style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                val endAngle = 305f * PI.toFloat() / 180f
                val end = pivot + Offset(arm * cos(endAngle), arm * sin(endAngle))
                val tangent = Offset(-sin(endAngle), cos(endAngle))
                val normal = Offset(cos(endAngle), sin(endAngle))
                for (side in listOf(-1f, 1f)) {
                    drawLine(accent, end, end - tangent * 7.dp.toPx() + normal * (side * 4.dp.toPx()),
                        strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                }
            }
            // hub
            drawCircle(Color(0xFF2A1A16), 15.dp.toPx(), pivot)
            drawCircle(metal, 15.dp.toPx(), pivot, style = Stroke(1.5.dp.toPx()))
            // arm and thumb pad
            drawLine(metal, pivot, tip, strokeWidth = 7.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(Color(0xFF1B1210), 9.dp.toPx(), tip)
            drawCircle(if (cocked) Pal.off else accent, 9.dp.toPx(), tip, style = Stroke(2.dp.toPx()))
            // hub screw: red dot shows the shutter is cocked
            drawCircle(if (cocked) Pal.safelight else Color(0xFF4A2A22), 4.dp.toPx(), pivot)
            if (showGuide) {
                // A translucent lever and touch ring demonstrate the full drag, without advancing film.
                val demoAngle = (180f + 125f * demonstration.value) * PI.toFloat() / 180f
                val finger = pivot + Offset(arm * cos(demoAngle), arm * sin(demoAngle))
                drawLine(accent.copy(alpha = 0.35f), pivot, finger, 7.dp.toPx(), StrokeCap.Round)
                drawCircle(accent.copy(alpha = 0.2f), 12.dp.toPx(), finger)
                drawCircle(accent, 12.dp.toPx(), finger, style = Stroke(2.dp.toPx()))
                drawCircle(Pal.text, 3.dp.toPx(), finger)
            }
        }
    }
}

/** Mechanical frame counter window: the number rolls when the film advances. */
@Composable
fun FrameCounter(frame: Int, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(5.dp)).background(Color(0xFF1B1210)).border(1.dp, Pal.off, RoundedCornerShape(5.dp))
        .padding(horizontal = 9.dp, vertical = 3.dp), contentAlignment = Alignment.Center) {
        RollingNumber(frame, Fonts.mono, 15.sp, Pal.text)
    }
}
