package app.spektralab

import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import app.spektralab.ui.AppState
import app.spektralab.ui.EditorScreen
import app.spektralab.ui.LibraryScreen
import app.spektralab.ui.RoomTransition
import app.spektralab.ui.LocalFold
import app.spektralab.ui.rememberFoldState
import app.spektralab.ui.Sheet
import app.spektralab.ui.Sheets

class MainActivity : ComponentActivity() {
    private val state: AppState by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // share sheets can only be started by a foreground activity: open finished
        // "share" exports when we are (back) on screen
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                state.exports.pendingShare.collect { uris ->
                    if (uris != null) {
                        state.exports.pendingShare.value = null
                        state.exports.shareUris(this@MainActivity, uris)
                    }
                }
            }
        }
        setContent {
            val fold = rememberFoldState(this)
            MaterialTheme { CompositionLocalProvider(LocalFold provides fold) {
                val editId by state.editId.collectAsState()
                val sheet by state.sheet.collectAsState()
                val selecting by state.selecting.collectAsState()
                BackHandler(sheet != Sheet.NONE || editId != null || selecting) {
                    when {
                        sheet != Sheet.NONE && sheet != Sheet.PROGRESS -> state.sheet.value = Sheet.NONE
                        sheet == Sheet.PROGRESS -> state.sheet.value = Sheet.NONE
                        editId != null -> state.back()
                        selecting -> state.toggleSelecting()
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    // lightbox <-> darkroom: the bright table fades to near black
                    RoomTransition(editId, isDark = { it != null }) { id ->
                        if (id == null) LibraryScreen(state) else EditorScreen(state, id)
                    }
                    Sheets(state)
                }
            } }
        }
    }
}
