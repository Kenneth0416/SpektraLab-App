package app.spektralab

import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import androidx.appcompat.app.AppCompatActivity
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
import app.spektralab.ui.CameraScreen
import app.spektralab.ui.CoffeePromptDialog
import app.spektralab.ui.LibraryScreen
import app.spektralab.ui.RoomTransition
import app.spektralab.ui.LocalFold
import app.spektralab.ui.rememberFoldState
import app.spektralab.ui.Sheet
import app.spektralab.ui.Sheets
import app.spektralab.ui.UpdateDialog
import app.spektralab.ui.SettingsScreen

class MainActivity : AppCompatActivity() {
    private val state: AppState by viewModels()

    /** Volume keys release the shutter in the camera, like on every phone camera. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val isVolume = keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP
        if (isVolume && state.cameraOpen.value && state.editId.value == null) {
            if (event.repeatCount == 0) state.shutterKey.tryEmit(Unit)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // share sheets can only be started by a foreground activity: open finished
        // "share" exports when we are (back) on screen
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                state.updates.check()
                // prints left from a previous run: only now may the export service be started
                state.exports.resume()
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
                val cameraOpen by state.cameraOpen.collectAsState()
                val settingsOpen by state.settingsOpen.collectAsState()
                val coffeePrompt by state.coffeePrompt.collectAsState()
                val updatePrompt by state.updates.prompt.collectAsState()
                val importing by state.importing.collectAsState()
                val exporting by state.exports.state.collectAsState()
                val merging by state.merging.collectAsState()
                val canRemind = sheet == Sheet.NONE && !coffeePrompt && editId == null && !cameraOpen &&
                    !selecting && !importing && exporting == null && merging == null
                BackHandler(coffeePrompt || sheet != Sheet.NONE || editId != null || selecting || cameraOpen || settingsOpen) {
                    when {
                        coffeePrompt -> state.snoozeCoffeePrompt()
                        sheet != Sheet.NONE && sheet != Sheet.PROGRESS -> state.sheet.value = Sheet.NONE
                        sheet == Sheet.PROGRESS -> state.sheet.value = Sheet.NONE
                        settingsOpen -> state.closeSettings()
                        editId != null || cameraOpen -> state.back()
                        selecting -> state.toggleSelecting()
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    // lightbox <-> darkroom: the bright table fades to near black
                    // rooms: lightbox (bright), darkroom editor and camera (dark)
                    if (settingsOpen) SettingsScreen(state)
                    else RoomTransition(editId to cameraOpen, isDark = { it.first != null || it.second }) { (id, camera) ->
                        when {
                            id != null -> EditorScreen(state, id)
                            camera -> CameraScreen(state)
                            else -> LibraryScreen(state)
                        }
                    }
                    Sheets(state)
                    updatePrompt?.takeIf { canRemind || (it.manual && settingsOpen && !coffeePrompt && sheet == Sheet.NONE) }?.let { prompt ->
                        UpdateDialog(prompt.release, onLater = state.updates::dismissPrompt,
                            onOpened = state.updates::dismissPrompt)
                    }
                    if (coffeePrompt) CoffeePromptDialog(
                        onLater = state::snoozeCoffeePrompt,
                        onDismissForever = state::dismissCoffeePrompt,
                    )
                }
            } }
        }
    }
}
