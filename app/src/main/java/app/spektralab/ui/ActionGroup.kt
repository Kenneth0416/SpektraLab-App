package app.spektralab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor

/** Equal widths and content-driven equal heights within each row; never cap label lines.
 * The width budget grows with system text size. Labels may still wrap inside a cell.
 * Only use for simple, intrinsically measurable children (not Lazy/Subcompose layouts).
 */
@Composable
internal fun ActionGroup(
    count: Int,
    modifier: Modifier = Modifier,
    minCellWidth: Dp = 120.dp,
    content: @Composable (Int, Modifier) -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = 8.dp
        val columns = floor((maxWidth + gap) / (minCellWidth * fontScale + gap))
            .toInt().coerceIn(1, count.coerceAtLeast(1))
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            for (start in 0 until count step columns) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (index in start until minOf(start + columns, count)) {
                        content(index, Modifier.weight(1f).fillMaxHeight())
                    }
                    repeat((start + columns - count).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
internal fun ActionButton(
    label: String,
    ink: Color,
    modifier: Modifier = Modifier,
    fill: Color = Color.Transparent,
    border: Color? = null,
    bold: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier.heightIn(min = 48.dp).clip(shape).background(fill)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .semantics { role = Role.Button }
            .springClick(0.9f, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, modifier = Modifier.fillMaxWidth(), color = ink, fontSize = 15.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center)
    }
}
