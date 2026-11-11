package app.spektralab.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

/** Two opposing arrows, with matching painted bounds around the canvas center. */
@Composable
fun CameraFlipIcon(modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(modifier.size(28.dp)) {
        val unit = size.minDimension / 24f
        val radius = 7.5f * unit
        val stroke = Stroke(1.7f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val arrowhead = Path().apply {
            moveTo(center.x + radius - 3f * unit, center.y - 3f * unit)
            lineTo(center.x + radius, center.y)
            lineTo(center.x + radius + 3f * unit, center.y - 3f * unit)
        }
        // Rotate the second arrow about the same center used by the button animation.
        for (angle in listOf(0f, 180f)) {
            rotate(angle, pivot = center) {
                drawArc(
                    tint, startAngle = 210f, sweepAngle = 150f, useCenter = false,
                    topLeft = center - Offset(radius, radius),
                    size = Size(radius * 2f, radius * 2f), style = stroke
                )
                drawPath(arrowhead, tint, style = stroke)
            }
        }
    }
}
