package app.spektralab.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.spektralab.SpektraApp
import app.spektralab.data.LibraryItem
import app.spektralab.data.Media
import app.spektralab.data.Recipe
import app.spektralab.engine.Look
import app.spektralab.export.ExportOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Sheet { NONE, IMPORT, RECIPES, EXPORT, PROGRESS }
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

    fun say(text: String) {
        _hint.value = text
        hintJob?.cancel()
        hintJob = viewModelScope.launch { delay(2400); _hint.value = "" }
    }

    fun currentItem(): LibraryItem? = editId.value?.let { library.item(it) }

    // ----------------------------------------------------------- navigation
    fun openEdit(id: Long) {
        lastKey = null
        history.value = emptyList(); redo.value = emptyList()
        sheet.value = Sheet.NONE
        editId.value = id
    }

    fun back() {
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

    fun copyLook() { currentItem()?.let { clip.value = it.look; say("已复制这张的设置") } }

    fun pasteLook() {
        val c = clip.value ?: return say("还没有复制过设置")
        applyLook(c); say("已粘贴设置")
    }

    fun pasteToPicked() {
        val c = clip.value ?: return say("先打开一张，点「复制设置」")
        if (picked.value.isEmpty()) return say("先圈出要套用的项目")
        library.setLooks(picked.value, c)
        say("已粘贴到 ${picked.value.size} 项")
    }

    fun removePicked() {
        if (picked.value.isEmpty()) return say("先圈出要移除的项目")
        val n = picked.value.size
        app.thumbs.forget(picked.value)
        library.remove(picked.value)
        picked.value = emptyList(); selecting.value = false
        say("已移除 $n 项，相册原片不受影响")
    }

    fun openRecipes() {
        if (editId.value == null && picked.value.isEmpty()) return say("先圈出要套用的项目")
        sheet.value = Sheet.RECIPES
    }

    fun applyRecipe(r: Recipe) {
        val full = r.look
        applyLook(full)
        sheet.value = Sheet.NONE
        say("已套用：${r.name}")
    }

    fun saveRecipe() {
        val item = currentItem() ?: return
        val used = library.recipes.value.map { it.name }.toSet()
        val name = generateSequence(1) { it + 1 }.map { "我的配方 $it" }.first { it !in used }
        library.addRecipe(Recipe(name, item.look))
        say("已写成配方卡：$name")
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
            if (items.isEmpty()) return@launch say("没有可以导入的照片或视频")
            library.add(items)
            filter.value = Filter.ALL
            if (items.size == 1) openEdit(items[0].id) else say("已导入 ${items.size} 项")
            if (items.size < uris.size) say("有 ${uris.size - items.size} 项无法读取，已跳过")
        }
    }

    // ----------------------------------------------------------- export
    fun openExport(ids: List<Long>) {
        if (ids.isEmpty()) return say("先圈出要导出的项目")
        if (exports.isRunning()) { sheet.value = Sheet.PROGRESS; return }
        exportTargets.value = ids
        sheet.value = Sheet.EXPORT
    }

    fun startExport(share: Boolean) {
        val items = exportTargets.value.mapNotNull { library.item(it) }
        if (items.isEmpty() || exports.isRunning()) { sheet.value = Sheet.NONE; return say("没有可以导出的项目") }
        exports.start(items, exportOptions.value, share)
        sheet.value = Sheet.PROGRESS
        selecting.value = false
        picked.value = emptyList()
        viewModelScope.launch {
            val st = exports.state.first { it != null && !it.running }!!
            if (sheet.value == Sheet.PROGRESS) sheet.value = Sheet.NONE
            val msg = when {
                st.saved.isEmpty() -> "导出失败"
                st.share -> "已交给系统分享 · ${st.saved.size} 项"
                else -> "已存到相册 · ${st.saved.size} 项"
            } + if (st.failed > 0 && st.saved.isNotEmpty()) "（${st.failed} 项失败）" else ""
            say(msg)
            exports.dismiss()
        }
    }
}
