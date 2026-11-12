package app.spektralab.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import app.spektralab.data.SourceImage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Source identity is (item ID, URI): a burst can replace the original under the same ID. */
@Composable
internal fun PhotoSourceEffects(
    source: Pair<Long, String>, zoomed: Boolean, worthFull: Boolean,
    load: suspend (full: Boolean) -> SourceImage?,
    onSource: (Pair<Long, String>) -> Unit,
    onPreview: (SourceImage, Pair<Long, String>) -> Unit,
    onFull: (SourceImage, Pair<Long, String>) -> Unit,
    onError: () -> Unit,
) {
    val currentSource by rememberUpdatedState(source)
    val currentZoomed by rememberUpdatedState(zoomed)
    LaunchedEffect(source) {
        onSource(source)
        launch {
            val image = load(false)
            if (currentSource == source) {
                if (image != null) onPreview(image, source) else onError()
            }
        }
        if (worthFull) launch {
            snapshotFlow { currentZoomed }.first { it }
            // Zooming out does not cancel this load. A source change cancels both loads,
            // and the identity guard also rejects decoders that finish after cancellation.
            val image = load(true)
            if (image != null && currentSource == source) onFull(image, source)
        }
    }
}
