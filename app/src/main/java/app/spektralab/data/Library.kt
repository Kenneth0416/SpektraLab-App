package app.spektralab.data

import android.content.Context
import androidx.annotation.StringRes
import app.spektralab.R
import app.spektralab.engine.Look
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LibraryItem(
    val id: Long,
    val uri: String,
    val isVideo: Boolean,
    val isRaw: Boolean,
    /** Upright (display) size. */
    val width: Int,
    val height: Int,
    val durationMs: Long,
    /** Clockwise rotation metadata of a video. */
    val rotation: Int,
    val dateTaken: Long,
    /** Center-weighted auto exposure measured at import (spektrafilm default metering). */
    val autoEv: Float,
    val look: Look,
    val name: String,
)

data class Recipe(val name: String, val look: Look, val builtin: Boolean = false, @StringRes val nameRes: Int? = null)

fun Recipe.localizedName(context: Context): String = nameRes?.let(context::getString) ?: name

object LookJson {
    fun toJson(l: Look) = JSONObject().apply {
        put("film", l.film); put("paper", l.paper); put("printEv", l.printEv); put("glare", l.glare)
        put("filmEv", l.filmEv); put("push", l.push); put("halation", l.halation); put("halationSize", l.halationSize)
        put("grain", l.grain); put("grainSize", l.grainSize); put("diffusion", l.diffusion)
        put("diffusionAmount", l.diffusionAmount); put("y", l.yFilter); put("m", l.mFilter)
        put("format", l.format); put("border", l.border); put("date", l.dateStamp)
    }

    fun fromJson(o: JSONObject?): Look {
        val d = Look.DEFAULT
        if (o == null) return d
        return Look(
            film = o.optString("film", d.film), paper = o.optString("paper", d.paper),
            printEv = o.optDouble("printEv", d.printEv), glare = o.optDouble("glare", d.glare),
            filmEv = o.optDouble("filmEv", d.filmEv), push = o.optInt("push", d.push),
            halation = o.optDouble("halation", d.halation), halationSize = o.optDouble("halationSize", d.halationSize),
            grain = o.optDouble("grain", d.grain), grainSize = o.optDouble("grainSize", d.grainSize),
            diffusion = o.optString("diffusion", d.diffusion), diffusionAmount = o.optDouble("diffusionAmount", d.diffusionAmount),
            yFilter = o.optDouble("y", d.yFilter), mFilter = o.optDouble("m", d.mFilter),
            format = o.optString("format", d.format),
            // older libraries stored the film edge as a boolean
            border = o.optString("border", if (o.optBoolean("edge", false)) "edge" else d.border),
            dateStamp = o.optBoolean("date", d.dateStamp),
        )
    }
}

/** The lightbox: imported items and user recipes, persisted as JSON in app storage. */
class Library(context: Context) {
    private val file = File(context.filesDir, "library.json")
    private val _items = MutableStateFlow<List<LibraryItem>>(emptyList())
    val items: StateFlow<List<LibraryItem>> = _items
    private val _recipes = MutableStateFlow<List<Recipe>>(emptyList())
    val recipes: StateFlow<List<Recipe>> = _recipes

    /** Ids are never reused, so caches keyed by id can't show a removed item. */
    private var idCounter = 0L
    private val saver = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "spektra-library") }
    private val saveQueued = java.util.concurrent.atomic.AtomicBoolean(false)

    init { load() }

    fun item(id: Long): LibraryItem? = _items.value.firstOrNull { it.id == id }

    @Synchronized fun add(newItems: List<LibraryItem>) {
        _items.value = newItems + _items.value
        save()
    }

    @Synchronized fun remove(ids: Collection<Long>) {
        _items.value = _items.value.filterNot { it.id in ids }
        save()
    }

    /** The original of an item was replaced by a better one (a merged burst). */
    @Synchronized fun setSource(id: Long, uri: String, name: String) {
        _items.value = _items.value.map { if (it.id == id) it.copy(uri = uri, name = name) else it }
        save()
    }

    @Synchronized fun setLook(id: Long, look: Look) {
        _items.value = _items.value.map { if (it.id == id) it.copy(look = look) else it }
        save()
    }

    @Synchronized fun setLooks(ids: Collection<Long>, look: Look) {
        _items.value = _items.value.map { if (it.id in ids) it.copy(look = look) else it }
        save()
    }

    @Synchronized fun addRecipe(r: Recipe) {
        _recipes.value = listOf(r) + _recipes.value
        save()
    }

    @Synchronized fun removeRecipe(name: String) {
        _recipes.value = _recipes.value.filterNot { it.name == name }
        save()
    }

    /** Reserve [count] fresh ids. */
    @Synchronized fun reserveIds(count: Int): Long {
        idCounter = maxOf(idCounter, _items.value.maxOfOrNull { it.id } ?: 0L)
        val first = idCounter + 1
        idCounter += count
        save()
        return first
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("items") ?: JSONArray()
            idCounter = root.optLong("nextId", 0L)
            _items.value = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                LibraryItem(
                    id = o.getLong("id"), uri = o.getString("uri"), isVideo = o.optBoolean("video"),
                    isRaw = o.optBoolean("raw"), width = o.optInt("w"), height = o.optInt("h"),
                    durationMs = o.optLong("dur"), rotation = o.optInt("rot"), dateTaken = o.optLong("date"),
                    autoEv = o.optDouble("autoEv", 0.0).toFloat(), look = LookJson.fromJson(o.optJSONObject("look")),
                    name = o.optString("name"),
                )
            }
            val rs = root.optJSONArray("recipes") ?: JSONArray()
            _recipes.value = (0 until rs.length()).map { i ->
                val o = rs.getJSONObject(i)
                Recipe(o.getString("name"), LookJson.fromJson(o.optJSONObject("look")))
            }
        }
    }

    /** Persist off the main thread; bursts of edits (slider drags) collapse into one write. */
    private fun save() {
        if (saveQueued.getAndSet(true)) return
        saver.execute {
            saveQueued.set(false)
            writeNow()
        }
    }

    @Synchronized private fun writeNow() {
        val root = JSONObject()
        root.put("nextId", idCounter)
        root.put("items", JSONArray().apply {
            _items.value.forEach { it2 ->
                put(JSONObject().apply {
                    put("id", it2.id); put("uri", it2.uri); put("video", it2.isVideo); put("raw", it2.isRaw)
                    put("w", it2.width); put("h", it2.height); put("dur", it2.durationMs); put("rot", it2.rotation)
                    put("date", it2.dateTaken); put("autoEv", it2.autoEv.toDouble()); put("name", it2.name)
                    put("look", LookJson.toJson(it2.look))
                })
            }
        })
        root.put("recipes", JSONArray().apply {
            _recipes.value.forEach { r -> put(JSONObject().put("name", r.name).put("look", LookJson.toJson(r.look))) }
        })
        val tmp = File(file.parentFile, "library.json.tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
    }

    companion object {
        val BUILTIN_RECIPES = listOf(
            Recipe("builtin:daily_portrait", Look(film = "kodak_portra_400", filmEv = 0.5), true, R.string.recipe_daily_portrait),
            Recipe("builtin:neon_night", Look(film = "kodak_vision3_500t", paper = "kodak_2383", halation = 1.6,
                diffusion = "cinebloom", diffusionAmount = 0.4), true, R.string.recipe_neon_night),
            Recipe("builtin:rich_landscape", Look(film = "fujifilm_velvia_100", grain = 0.8), true, R.string.recipe_rich_landscape),
            Recipe("builtin:travel_snapshot", Look(film = "kodak_gold_200", diffusion = "pro_mist", diffusionAmount = 0.4,
                halation = 1.3, dateStamp = true), true, R.string.recipe_travel_snapshot),
            Recipe("builtin:street_push", Look(film = "kodak_portra_800", push = 2, grain = 1.3, format = "half"), true, R.string.recipe_street_push),
        )
    }
}
