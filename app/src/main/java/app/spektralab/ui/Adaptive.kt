package app.spektralab.ui

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

/** How a foldable is being held, from Jetpack WindowManager. */
enum class Posture {
    /** Flat, or no fold. */
    NORMAL,
    /** Half opened with a horizontal hinge (laptop / tabletop). */
    TABLETOP,
    /** Half opened with a vertical hinge, or a hinge that separates two screens. */
    BOOK,
}

@Immutable
data class FoldState(
    val posture: Posture = Posture.NORMAL,
    /** Hinge bounds in window pixels when it splits the content (half opened or separating). */
    val hinge: Rect? = null,
)

val LocalFold = compositionLocalOf { FoldState() }

/** Collect the window's folding features for the lifetime of [activity]. */
@Composable
fun rememberFoldState(activity: Activity): FoldState {
    var state by remember { mutableStateOf(FoldState()) }
    LaunchedEffect(activity) {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
            val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
            state = if (fold == null) FoldState() else {
                val b = fold.bounds
                val rect = Rect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
                val halfOpen = fold.state == FoldingFeature.State.HALF_OPENED
                when {
                    halfOpen && fold.orientation == FoldingFeature.Orientation.HORIZONTAL -> FoldState(Posture.TABLETOP, rect)
                    halfOpen || fold.isSeparating -> FoldState(
                        if (fold.orientation == FoldingFeature.Orientation.VERTICAL) Posture.BOOK else Posture.TABLETOP, rect)
                    else -> FoldState()
                }
            }
        }
    }
    return state
}

/** Width buckets following the Material window size classes. */
enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

@Composable
fun widthClass(): WidthClass {
    val w = LocalConfiguration.current.screenWidthDp
    return when {
        w < 600 -> WidthClass.COMPACT
        w < 840 -> WidthClass.MEDIUM
        else -> WidthClass.EXPANDED
    }
}

/** Sheets and bars are capped to this width on large screens. */
val SheetMaxWidth: Dp = 600.dp
