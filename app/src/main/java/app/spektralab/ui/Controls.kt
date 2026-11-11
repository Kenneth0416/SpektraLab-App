package app.spektralab.ui

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
    val current by rememberUpdatedState(value)
    // the gesture handlers below live across recompositions; always call the latest
    // callback, which closes over the current look (otherwise other edits get reverted)
    val latestOnChange by rememberUpdatedState(onChange)
    var dragValue by remember { mutableStateOf<Double?>(null) }
    val shown = dragValue ?: value
    val span = range.endInclusive - range.start
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Pal.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(readout(shown), color = accent, fontFamily = Fonts.mono, fontSize = 13.sp)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(34.dp)
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(shown.toFloat(), range.start.toFloat()..range.endInclusive.toFloat())
                setProgress { v -> latestOnChange(snap(v.toDouble(), range, step)); true }
            }
            .pointerInput(range, step) {
                detectTapGestures(onDoubleTap = { latestOnChange(default) })
            }
            .pointerInput(range, step) {
                // full width of the view = 60% of the range
                val pxPerUnit = size.width / (span * 0.6)
                detectHorizontalDragGestures(
                    onDragStart = { dragValue = current },
                    onDragEnd = { dragValue?.let { latestOnChange(snap(it, range, step)) }; dragValue = null },
                    onDragCancel = { dragValue = null },
                ) { change, dx ->
                    change.consume()
                    val nv = ((dragValue ?: current) - dx / pxPerUnit).coerceIn(range.start, range.endInclusive)
                    dragValue = nv
                    latestOnChange(snap(nv, range, step))
                }
            }) {
            Canvas(Modifier.fillMaxSize()) {
                val pxPerUnit = size.width / (span * 0.6)
                val center = size.width / 2
                val minor = span / 40.0
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
                        val h = if (zero) size.height * 0.7f else if (major) size.height * 0.5f else size.height * 0.28f
                        drawLine(color, Offset(x, size.height - h), Offset(x, size.height), strokeWidth = if (major) 2f else 1.2f)
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
fun Chip(label: String, active: Boolean, accent: Color, onClick: () -> Unit) {
    Text(label, color = if (active) Pal.dink else Pal.text, fontSize = 13.sp,
        modifier = Modifier.padding(end = 8.dp, bottom = 8.dp).clip(RoundedCornerShape(16.dp))
            .background(if (active) accent else Color.Transparent)
            .border(1.dp, if (active) accent else Color(0xFF5A2A21), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp))
}

@Composable
fun ToggleRow(label: String, on: Boolean, accent: Color, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Pal.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(if (on) "开" else "关", color = if (on) accent else Pal.dim, fontFamily = Fonts.mono, fontSize = 13.sp)
    }
}

fun signed(v: Double, digits: Int = 1, unit: String = ""): String {
    val s = if (digits == 0) abs(v).roundToInt().toString() else "%.${digits}f".format(abs(v))
    val sign = if (v > 1e-9) "+" else if (v < -1e-9) "−" else ""
    return sign + s + unit
}
