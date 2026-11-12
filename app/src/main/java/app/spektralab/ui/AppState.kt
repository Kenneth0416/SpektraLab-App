package app.spektralab.ui

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.spektralab.R
import app.spektralab.SpektraApp
import app.spektralab.camera.RawShooter
import app.spektralab.data.LibraryItem
import app.spektralab.data.Media
import app.spektralab.data.LookJson
import app.spektralab.data.Recipe
import app.spektralab.data.localizedName
import app.spektralab.engine.Look
import app.spektralab.export.ExportOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Sheet { NONE, IMPORT, RECIPES, EXPORT, PROGRESS, ABOUT }
enum class Filter { ALL, PHOTO, VIDEO }

/** UI state of the whole app (lightbox + darkroom), following the interaction prototype. */
class AppState(application: Application) : AndroidViewModel(application) {
    val app = application as SpektraApp
    val library = app.library
    val exports = app.exports

    val editId = MutableStateFlow<Long?>(null)
    val filter = MutableStateFlow(Filter.ALL)
    val selecting = MutableStateFlow(false)
    val picked = MutableStateFlow<List<Long>>(emptyList())
    val sheet = MutableStateFlow(Sheet.NONE)
    val clip = MutableStateFlow<Look?>(null)
    val exportTargets = MutableStateFlow<List<Long>>(emptyList())
    val exportOptions = MutableStateFlow(ExportOptions())
    val importing = MutableStateFlow(false)
    val settingsOpen = MutableStateFlow(false)

    private val languagePreferences = LanguagePreferences(application)
    val language = MutableStateFlow(languagePreferences.load())

    fun setLanguage(value: AppLanguage) {
        languagePreferences.save(value)
        language.value = value
        AppLocales.apply(value)
    }

    fun openSettings() {
        sheet.value = Sheet.NONE
        selecting.value = false
        picked.value = emptyList()
        settingsOpen.value = true
    }

    fun closeSettings() { settingsOpen.value = false }

    // camera: the look loaded in the camera, kept between sessions
    val cameraOpen = MutableStateFlow(false)
    val camLook = MutableStateFlow(runCatching {
        LookJson.fromJson(org.json.JSONObject(application.getSharedPreferences("camera", android.content.Context.MODE_PRIVATE).getString("look", "{}")!!))
    }.getOrDefault(Look.DEFAULT))

    fun setCamLook(look: Look) {
        camLook.value = look
        app.getSharedPreferences("camera", android.content.Context.MODE_PRIVATE).edit().putString("look", LookJson.toJson(look).toString()).apply()
    }

    /** Hardware shutter (volume keys) while the camera is open. */
    val shutterKey = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun openCamera() { sheet.value = Sheet.NONE; selecting.value = false; picked.value = emptyList(); cameraOpen.value = true }
    fun closeCamera() { cameraOpen.value = false; sheet.value = Sheet.NONE }

    /**
     * A shot was saved to the album ([uri] is the untouched original, raw when possible):
     * put it on the lightbox with the camera's look and print it.
     */
    fun onCaptured(uri: Uri, evBias: Float = 0f) {
        val look = camLook.value
        viewModelScope.launch {
            val item = withContext(Dispatchers.IO) { Media.importItem(app, uri, library.reserveIds(1)) }
                // the exposure compensation dialled in at the viewfinder is part of the metered exposure
                ?.let { it.copy(look = look, autoEv = it.autoEv + evBias) }
                ?: return@launch say(R.string.captured_unreadable)
            library.add(listOf(item))
            // every shot is also printed: the film picture goes to the album next to the original
            exports.enqueue(item, exportOptions.value)
        }
    }

    /** Multi-frame capture: each shot is a short raw burst merged into one cleaner original. */
    val multiFrame = MutableStateFlow(application.getSharedPreferences("camera", android.content.Context.MODE_PRIVATE).getBoolean("multiFrame", false))
    fun setMultiFrame(on: Boolean) {
        multiFrame.value = on
        app.getSharedPreferences("camera", android.content.Context.MODE_PRIVATE).edit().putBoolean("multiFrame", on).apply()
    }

    /** Progress 0..1 of the burst being merged, null when idle. */
    val merging = MutableStateFlow<Float?>(null)

    /** Raw capture into memory (single frames and bursts); lives as long as the app's UI state. */
    val shooter = RawShooter(application).also { sh ->
        sh.onProgress = { merging.value = it }
        sh.onSaved = { uri, evBias -> onShotSaved(uri, evBias) }
        sh.onFinished = { first, merged, used -> onShotFinished(first, merged, used) }
    }
    override fun onCleared() {
        shooter.close()
        shots.clear()
        super.onCleared()
    }

    private val shots = HashMap<Uri, kotlinx.coroutines.Deferred<LibraryItem?>>()

    /** The first frame of a shot is in the album: it goes on the lightbox right away. */
    private fun onShotSaved(uri: Uri, evBias: Float) {
        val look = camLook.value
        shots[uri] = viewModelScope.async {
            val item = withContext(Dispatchers.IO) { Media.importItem(app, uri, library.reserveIds(1)) }
                ?.let { it.copy(look = look, autoEv = it.autoEv + evBias) }
            if (item == null) say(R.string.captured_unreadable) else library.add(listOf(item))
            item
        }
    }

    /** The shot is complete: swap in the merged original if there is one, then print it. */
    private fun onShotFinished(first: Uri, merged: Uri?, framesUsed: Float) {
        val pending = shots.remove(first)
        viewModelScope.launch {
            val id = pending?.await()?.id
            if (merged != null) {
                // The lightbox uses the merged source. Keep the first frame in MediaStore:
                // queued exports and in-flight decodes may still own its immutable URI.
                if (id != null && library.item(id) != null) {
                    library.setSource(id, merged.toString(), withContext(Dispatchers.IO) { Media.name(app, merged) })
                    say(R.string.burst_merged, framesUsed)
                }
            }
            // every shot is also printed: the film picture goes to the album next to the original
            id?.let { library.item(it) }?.let { exports.enqueue(it, exportOptions.value) }
        }
    }

    // viewing preferences (spektrafilm GUI display options)
    private val prefs = application.getSharedPreferences("display", android.content.Context.MODE_PRIVATE)
    /** 18% grey backdrop around the print, like spektrafilm's gray_18_canvas. */
    val gray18 = MutableStateFlow(prefs.getBoolean("gray18", false))
    /** spektrafilm's instant-film development animation when a photo opens. */
    val reveal = MutableStateFlow(prefs.getBoolean("reveal", true))

    fun setGray18(on: Boolean) { gray18.value = on; prefs.edit().putBoolean("gray18", on).apply() }
    fun setReveal(on: Boolean) { reveal.value = on; prefs.edit().putBoolean("reveal", on).apply() }

    private val _hint = MutableStateFlow("")
    val hint: StateFlow<String> = _hint
    private var hintJob: Job? = null

    // editor history
    val history = MutableStateFlow<List<Look>>(emptyList())
    val redo = MutableStateFlow<List<Look>>(emptyList())
    private var lastKey: String? = null

    fun say(@StringRes text: Int, vararg args: Any) = say(app.getString(text, *args))

    fun say(text: String) {
        _hint.value = text
        hintJob?.cancel()
        hintJob = viewModelScope.launch { delay(2400); _hint.value = "" }
    }

    fun currentItem(): LibraryItem? = editId.value?.let { library.item(it) }

    /** Recipes apply to the open item, else to the camera, else to the circled items. */
    private val recipeTargetIsCamera get() = editId.value == null && cameraOpen.value

    // ----------------------------------------------------------- navigation
    fun openEdit(id: Long) {
        lastKey = null
        history.value = emptyList(); redo.value = emptyList()
        sheet.value = Sheet.NONE
        editId.value = id
        // start on the two slow parts of opening a frame before the darkroom is even composed:
        // the first rendition of the picture and the look's tables (both are cached)
        library.item(id)?.let { item ->
            if (app.quick.worth(item)) app.appScope.launch(Dispatchers.IO) { app.quick.load(item) }
            app.appScope.launch { runCatching { app.looks.get(item.look) } }
        }
    }

    fun back() {
        if (settingsOpen.value) return closeSettings()
        if (editId.value == null && cameraOpen.value) return closeCamera()
        editId.value = null
        sheet.value = Sheet.NONE
    }

    // ----------------------------------------------------------- selection
    fun toggleSelecting() { selecting.value = !selecting.value; picked.value = emptyList() }

    fun togglePick(id: Long) {
        picked.value = if (id in picked.value) picked.value - id else picked.value + id
    }

    // ----------------------------------------------------------- looks
    /** Change one setting of the open item; consecutive edits of the same key share one undo step. */
    fun setLook(key: String, look: Look) {
        val item = currentItem() ?: return
        if (look == item.look) return
        if (key != lastKey) history.value = history.value + item.look
        redo.value = emptyList()
        lastKey = key
        library.setLook(item.id, look)
    }

    fun applyLook(look: Look) {
        lastKey = null
        val item = currentItem()
        if (item != null) {
            history.value = history.value + item.look
            redo.value = emptyList()
            library.setLook(item.id, look)
        } else {
            library.setLooks(picked.value, look)
        }
    }

    fun undo() = step(-1)
    fun redoStep() = step(1)

    private fun step(dir: Int) {
        lastKey = null
        val item = currentItem() ?: return
        val from = if (dir < 0) history.value else redo.value
        if (from.isEmpty()) return
        val target = from.last()
        if (dir < 0) { history.value = from.dropLast(1); redo.value = redo.value + item.look }
        else { redo.value = from.dropLast(1); history.value = history.value + item.look }
        library.setLook(item.id, target)
    }

    fun copyLook() { currentItem()?.let { clip.value = it.look; say(R.string.settings_copied) } }

    fun pasteLook() {
        val c = clip.value ?: return say(R.string.no_copied_settings)
        applyLook(c); say(R.string.settings_pasted)
    }

    fun pasteToPicked() {
        val c = clip.value ?: return say(R.string.copy_settings_first)
        if (picked.value.isEmpty()) return say(R.string.select_items_to_apply)
        library.setLooks(picked.value, c)
        say(R.string.settings_pasted_count, picked.value.size)
    }

    fun removePicked() {
        if (picked.value.isEmpty()) return say(R.string.select_items_to_remove)
        val n = picked.value.size
        app.thumbs.forget(picked.value)
        app.quick.forget(picked.value)
        library.remove(picked.value)
        picked.value = emptyList(); selecting.value = false
        say(R.string.items_removed, n)
    }

    fun openRecipes() {
        if (editId.value == null && !cameraOpen.value && picked.value.isEmpty()) return say(R.string.select_items_to_apply)
        sheet.value = Sheet.RECIPES
    }

    fun applyRecipe(r: Recipe) {
        val full = r.look
        if (recipeTargetIsCamera) setCamLook(full) else applyLook(full)
        sheet.value = Sheet.NONE
        say(R.string.recipe_applied, r.localizedName(app))
    }

    fun saveRecipe() {
        val look = currentItem()?.look ?: (if (cameraOpen.value) camLook.value else return)
        val used = library.recipes.value.map { it.name }.toSet()
        val name = generateSequence(1) { it + 1 }.map { app.getString(R.string.my_recipe, it) }.first { it !in used }
        library.addRecipe(Recipe(name, look))
        say(R.string.recipe_saved, name)
    }

    // ----------------------------------------------------------- import
    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        sheet.value = Sheet.NONE
        importing.value = true
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                val first = library.reserveIds(uris.size)
                uris.mapIndexedNotNull { i, u -> Media.importItem(app, u, first + i) }
            }
            importing.value = false
            if (items.isEmpty()) return@launch say(R.string.no_importable_media)
            library.add(items)
            filter.value = Filter.ALL
            if (items.size == 1) openEdit(items[0].id) else say(R.string.items_imported, items.size)
            if (items.size < uris.size) say(R.string.items_import_skipped, uris.size - items.size)
        }
    }

    // ----------------------------------------------------------- export
    fun openExport(ids: List<Long>) {
        if (ids.isEmpty()) return say(R.string.select_items_to_export)
        if (exports.isRunning()) { sheet.value = Sheet.PROGRESS; return }
        exportTargets.value = ids
        sheet.value = Sheet.EXPORT
    }

    fun startExport(share: Boolean) {
        val items = exportTargets.value.mapNotNull { library.item(it) }
        if (items.isEmpty() || exports.isRunning()) { sheet.value = Sheet.NONE; return say(R.string.nothing_to_export) }
        exports.start(items, exportOptions.value, share)
        sheet.value = Sheet.PROGRESS
        selecting.value = false
        picked.value = emptyList()
        viewModelScope.launch {
            val st = exports.state.first { it != null && !it.running }!!
            if (sheet.value == Sheet.PROGRESS) sheet.value = Sheet.NONE
            val msg = when {
                st.saved.isEmpty() -> app.getString(R.string.export_failed)
                st.share -> app.getString(R.string.export_shared_count, st.saved.size)
                else -> app.getString(R.string.export_saved_count, st.saved.size)
            } + if (st.failed > 0 && st.saved.isNotEmpty()) app.getString(R.string.export_failed_count, st.failed) else ""
            say(msg)
            exports.dismiss()
        }
    }
}
