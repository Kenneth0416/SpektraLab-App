package app.spektralab.ui

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.spektralab.update.UpdateClient
import app.spektralab.update.UpdateManager
import app.spektralab.update.UpdatePreferences
import app.spektralab.R
import app.spektralab.SpektraApp
import app.spektralab.camera.CameraFormat
import app.spektralab.camera.RawShooter
import app.spektralab.data.IncomingShare
import app.spektralab.data.LibraryItem
import app.spektralab.data.Media
import app.spektralab.data.RawPreview
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
    private val installedPackage = application.packageManager.getPackageInfo(application.packageName, 0)
    val installedVersionName = installedPackage.versionName.orEmpty()
    val updates = UpdateManager(
        viewModelScope, UpdatePreferences(application), installedPackage.longVersionCode,
        android.os.Build.VERSION.SDK_INT, UpdateClient(application.packageName)::fetch,
    )

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
    /** One-time optional coffee prompt after ten imported photos. */
    val coffeePrompt = MutableStateFlow(false)
    private val coffee = CoffeePromptStore(application)

    /** Hide this appearance only. The next imported photo can show it again. */
    fun snoozeCoffeePrompt() { coffeePrompt.value = false }

    /** Retire the prompt for good. */
    fun dismissCoffeePrompt() {
        coffee.markPrompted()
        coffeePrompt.value = false
    }

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
            val item = withContext(Dispatchers.IO) { Media.importItem(app, uri, library.reserveIds(1), quick = app.quick) }
                // the exposure compensation dialled in at the viewfinder is part of the metered exposure
                ?.let { it.copy(look = look, autoEv = it.autoEv + evBias) }
                ?: return@launch say(R.string.captured_unreadable)
            library.add(listOf(item))
            // every shot is also printed: the film picture goes to the album next to the original
            exports.enqueue(item, exportOptions.value)
        }
    }

    /** What the shutter keeps as the original; kept between sessions. */
    val cameraFormat = MutableStateFlow(application.getSharedPreferences("camera", android.content.Context.MODE_PRIVATE).let { prefs ->
        CameraFormat.fromName(prefs.getString("cameraFormat", null))
            // legacy preference: burst shooters keep their merged originals, everyone else starts on JPEG
            ?: if (prefs.getBoolean("multiFrame", false)) CameraFormat.RAW_BURST else CameraFormat.JPEG
    })

    fun setCameraFormat(format: CameraFormat) {
        cameraFormat.value = format
        app.getSharedPreferences("camera", android.content.Context.MODE_PRIVATE).edit().putString("cameraFormat", format.persistName).apply()
    }

    /** Progress 0..1 of the burst being merged, null when idle. */
    val merging = MutableStateFlow<Float?>(null)

    /** Raw capture into memory (single frames and bursts); lives as long as the app's UI state. */
    val shooter = RawShooter(application).also { sh ->
        sh.onProgress = { merging.value = it }
        sh.onSaved = { uri, evBias, preview -> onShotSaved(uri, evBias, preview) }
        sh.onFinished = { first, merged, used, preview -> onShotFinished(first, merged, used, preview) }
    }
    override fun onCleared() {
        shooter.close()
        shots.clear()
        super.onCleared()
    }

    private val shots = HashMap<Uri, kotlinx.coroutines.Deferred<LibraryItem?>>()

    /** The first frame of a shot is in the album: it goes on the lightbox right away. */
    private fun onShotSaved(uri: Uri, evBias: Float, preview: RawPreview?) {
        val look = camLook.value
        shots[uri] = viewModelScope.async {
            val item = withContext(Dispatchers.IO) {
                Media.importItem(app, uri, library.reserveIds(1), quick = app.quick, captured = preview)
            }
                ?.let { it.copy(look = look, autoEv = it.autoEv + evBias) }
            if (item == null) say(R.string.captured_unreadable) else library.add(listOf(item))
            item
        }
    }

    /** The shot is complete: swap in the merged original if there is one, then print it. */
    private fun onShotFinished(first: Uri, merged: Uri?, framesUsed: Float, preview: RawPreview?) {
        val pending = shots.remove(first)
        viewModelScope.launch {
            val id = pending?.await()?.id
            if (merged != null) {
                // The lightbox uses the merged source. Keep the first frame in MediaStore:
                // queued exports and in-flight decodes may still own its immutable URI.
                if (id != null && library.item(id) != null) {
                    val item = library.item(id)!!
                    val name = withContext(Dispatchers.IO) {
                        val name = Media.name(app, merged)
                        // Populate the NEW URI before publishing it to observers; never
                        // relabel the first-frame proxy as the merged source.
                        preview?.source()?.use { app.quick.keep(item.copy(uri = merged.toString(), name = name), it) }
                        name
                    }
                    library.setSource(id, merged.toString(), name)
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
    private val importMutex = kotlinx.coroutines.sync.Mutex()

    fun importSharedUris(uris: List<Uri>, mimeType: String? = null) {
        if (uris.isEmpty()) { say(R.string.no_importable_media); return }
        importUris(uris, shared = true, sharedMimeType = mimeType)
    }

    fun importUris(uris: List<Uri>, shared: Boolean = false, sharedMimeType: String? = null) {
        if (uris.isEmpty()) return
        sheet.value = Sheet.NONE
        viewModelScope.launch {
            // A second share can arrive while a large video is being copied.
            importMutex.lock()
            try {
                importing.value = true
                if (shared) {
                    back()
                    settingsOpen.value = false
                    cameraOpen.value = false
                    selecting.value = false
                    picked.value = emptyList()
                    coffeePrompt.value = false
                }
                val items = withContext(Dispatchers.IO) {
                    val first = library.reserveIds(uris.size)
                    uris.mapIndexedNotNull { i, u ->
                        if (shared) IncomingShare.importItem(app, u, first + i, quick = app.quick, mimeType = sharedMimeType)
                        else Media.importItem(app, u, first + i, quick = app.quick)
                    }
                }
                if (items.isEmpty()) return@launch say(R.string.no_importable_media)
                library.add(items)
                filter.value = Filter.ALL
                val photos = items.count { !it.isVideo }
                val showCoffee = coffee.recordImportedPhotos(photos)
                // A single imported photo normally opens the darkroom. Keep that frame on the
                // lightbox while the optional prompt is up, so the two screens do not stack.
                if (showCoffee) coffeePrompt.value = true
                if (items.size == 1 && !showCoffee) openEdit(items[0].id) else say(R.string.items_imported, items.size)
                if (items.size < uris.size) say(R.string.items_import_skipped, uris.size - items.size)
            } finally {
                importing.value = false
                importMutex.unlock()
            }
        }
    }

    // ----------------------------------------------------------- export
    fun openExport(ids: List<Long>) {
        if (ids.isEmpty()) return say(R.string.select_items_to_export)
        exportTargets.value = ids
        sheet.value = Sheet.EXPORT
    }

    /** Waits for the queue to drain once a manual export joined it, then reports. */
    private var exportWatch: Job? = null

    fun startExport(share: Boolean) {
        val items = exportTargets.value.mapNotNull { library.item(it) }
        if (items.isEmpty()) { sheet.value = Sheet.NONE; return say(R.string.nothing_to_export) }
        // a running batch takes the new items at its end
        val joined = exports.isRunning()
        exports.start(items, exportOptions.value, share)
        sheet.value = Sheet.PROGRESS
        selecting.value = false
        picked.value = emptyList()
        if (joined) say(R.string.export_queued_count, items.size)
        watchExport()
    }

    fun retryExports() {
        if (exports.retry() == 0) { sheet.value = Sheet.NONE; return say(R.string.retry_source_missing) }
        sheet.value = Sheet.PROGRESS
        watchExport()
    }

    fun dismissExports() {
        exports.dismiss()
        sheet.value = Sheet.NONE
    }

    private fun watchExport() {
        if (exportWatch?.isActive == true) return
        exportWatch = viewModelScope.launch {
            val st = exports.state.first { it != null && !it.running }!!
            // failures stay on screen (sheet or pill) until retried or dismissed
            if (sheet.value == Sheet.PROGRESS && st.failures.isEmpty()) sheet.value = Sheet.NONE
            val msg = when {
                // nothing saved and nothing failed: the user stopped the queue
                st.saved.isEmpty() && st.failures.isEmpty() -> app.getString(R.string.export_stopped)
                st.saved.isEmpty() -> app.getString(R.string.export_failed_items, st.failed)
                st.share -> app.getString(R.string.export_shared_count, st.saved.size)
                else -> app.getString(R.string.export_saved_count, st.saved.size)
            } + if (st.failed > 0 && st.saved.isNotEmpty()) app.getString(R.string.export_failed_count, st.failed) else ""
            say(msg)
            if (st.failures.isEmpty()) exports.dismiss()
        }
    }
}
