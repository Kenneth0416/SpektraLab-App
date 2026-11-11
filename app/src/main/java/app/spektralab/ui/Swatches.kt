package app.spektralab.ui

import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spektralab.SpektraApp
import app.spektralab.data.LibraryItem
import app.spektralab.data.Media
import app.spektralab.data.SourceImage
import app.spektralab.engine.Catalog
import app.spektralab.engine.Look
import app.spektralab.engine.LookSetup
import app.spektralab.render.Framing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Square swatches for the film / paper pickers: one picture (the frame on the easel, or
 * a built-in test scene in the camera) printed through every stock, so a roll is chosen
 * by what it does to the picture rather than by its name.
 */
class Swatches(private val app: SpektraApp) {
    private val mem = LruCache<String, Bitmap>(160)
    private val mutex = Mutex()
    // the last picture the swatches were made from (decoded once, reused for every stock)
    private var sourceKey: String? = null
    private var source: SourceImage? = null
    private var sourceEv = 0f

    private fun sourceKey(item: LibraryItem?) = item?.let { "${it.id}_${Integer.toHexString(it.uri.hashCode())}" } ?: "scene"
    private fun key(item: LibraryItem?, look: Look) = "${sourceKey(item)}|${look.film}|${look.paper}"

    fun cached(item: LibraryItem?, look: Look): Bitmap? = mem.get(key(item, look))

    suspend fun get(item: LibraryItem?, look: Look): Bitmap? {
        val k = key(item, look)
        mem.get(k)?.let { return it }
        return mutex.withLock {
            mem.get(k)?.let { return@withLock it }
            val src = source(item) ?: return@withLock null
            runCatching {
                // a coarse LUT is plenty at this size and keeps a whole row of rolls quick
                val setup = withContext(Dispatchers.Default) { LookSetup.create(look, app.engine, 20) }
                val framing = Framing.compute(src.width, src.height, look, EDGE)
                app.glWorker.run { renderPhoto(src, setup, sourceEv, framing, null, 1) }
            }.getOrNull()?.also { mem.put(k, it) }
        }
    }

    private suspend fun source(item: LibraryItem?): SourceImage? {
        val k = sourceKey(item)
        if (k == sourceKey) return source
        val src = withContext(Dispatchers.IO) {
            if (item == null) SourceImage(testScene(), null)
            else runCatching {
                val uri = Uri.parse(item.uri)
                if (item.isVideo) Media.videoFrame(app, uri, minOf(item.durationMs * 500, 1_000_000L), 2 * EDGE)?.let { SourceImage(it, null) }
                else Media.loadSource(app, uri, 2 * EDGE, tryRaw = item.isRaw)
            }.getOrNull()
        } ?: return null
        sourceEv = item?.autoEv ?: withContext(Dispatchers.Default) { Media.autoExposure(src.bitmap!!) }
        source = src
        sourceKey = k
        return src
    }

    companion object {
        const val EDGE = 168

        /**
         * The look a swatch is printed with: only the stocks, everything else neutral
         * (square crop, no grain at this size) so the swatches differ by colour alone.
         */
        fun look(film: String, paper: String) = Look(film = film, paper = paper, grain = 0.0, format = "66")

        /** Sky, sun, foliage, skin and saturated primaries: what tells film stocks apart. */
        internal fun testScene(): Bitmap {
            val n = 2 * EDGE
            val s = n.toFloat()
            val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            fun rgb(v: Long) = (0xFF000000 or v).toInt()
            // sky down to a warm horizon, with the sun
            p.shader = LinearGradient(0f, 0f, 0f, s * 0.6f, intArrayOf(rgb(0x2F6FC4), rgb(0x8DBBE6), rgb(0xF6DDB4)), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, s, s * 0.6f, p)
            p.shader = RadialGradient(s * 0.74f, s * 0.2f, s * 0.2f, intArrayOf(rgb(0xFFFFFF), rgb(0xFFF1C8), 0x00FFE9B0), floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(s * 0.74f, s * 0.2f, s * 0.2f, p)
            // sea, far hills, grass
            p.shader = LinearGradient(0f, s * 0.5f, 0f, s * 0.64f, rgb(0x2E7A96), rgb(0x1B4F74), Shader.TileMode.CLAMP)
            c.drawRect(0f, s * 0.5f, s, s * 0.64f, p)
            p.shader = null
            p.color = rgb(0x4E6E72)
            c.drawOval(RectF(-s * 0.3f, s * 0.4f, s * 0.5f, s * 0.7f), p)
            p.shader = LinearGradient(0f, s * 0.6f, 0f, s, rgb(0x6FA03C), rgb(0x23451C), Shader.TileMode.CLAMP)
            c.drawRect(0f, s * 0.6f, s, s, p)
            p.shader = null
            p.color = rgb(0x1E3A1A)
            c.drawOval(RectF(-s * 0.2f, s * 0.84f, s * 0.6f, s * 1.2f), p)
            // a figure: skin, dark hair, red top
            p.color = rgb(0xC2262A)
            c.drawRoundRect(RectF(s * 0.2f, s * 0.6f, s * 0.52f, s * 1.1f), s * 0.1f, s * 0.1f, p)
            p.color = rgb(0xE3AC8B)
            c.drawRect(s * 0.325f, s * 0.52f, s * 0.395f, s * 0.64f, p)
            c.drawCircle(s * 0.36f, s * 0.44f, s * 0.105f, p)
            p.color = rgb(0x2A1B14)
            c.drawArc(RectF(s * 0.245f, s * 0.325f, s * 0.475f, s * 0.555f), 180f, 180f, true, p)
            // a yellow / blue parasol and its deep shadow
            p.color = rgb(0xF2B21E)
            c.drawArc(RectF(s * 0.58f, s * 0.5f, s * 0.98f, s * 0.9f), 180f, 180f, true, p)
            p.color = rgb(0x2444B4)
            c.drawArc(RectF(s * 0.58f, s * 0.5f, s * 0.98f, s * 0.9f), 240f, 60f, true, p)
            p.color = rgb(0xEDE6D8)
            c.drawRect(s * 0.775f, s * 0.7f, s * 0.785f, s * 0.92f, p)
            p.color = rgb(0x10200F)
            c.drawOval(RectF(s * 0.62f, s * 0.9f, s * 0.96f, s * 0.96f), p)
            return bmp
        }
    }
}

/** One choice in a [FrameRow]. */
class FrameChoice(val id: String, val name: String, val sub: String, val edge: String, val accent: Color, val look: Look)

fun filmChoices(look: Look): List<FrameChoice> = Catalog.films.map { f ->
    FrameChoice(f.stock, f.name, "ISO ${f.iso} · ${f.kind}", f.iso, Color(f.accent), Swatches.look(f.stock, look.paper))
}

fun paperChoices(look: Look, accent: Color): List<FrameChoice> = Catalog.papers.map { p ->
    val sub = when {
        p.id == "auto" -> "原配相纸"
        p.id == "none" -> "直接扫描"
        p.id.startsWith("kodak_23") -> "电影拷贝片"
        else -> "彩色相纸"
    }
    FrameChoice(p.id, p.label, sub, if (p.id == "none") "SCAN" else "PRINT", accent, Swatches.look(look.film, p.id))
}

/**
 * A row of square film frames, each showing [source] (or the test scene when null)
 * through that choice. Opens scrolled to the loaded one.
 */
@Composable
fun FrameRow(app: SpektraApp, source: LibraryItem?, choices: List<FrameChoice>, selected: String, onPick: (String) -> Unit) {
    val start = remember { (choices.indexOfFirst { it.id == selected } - 1).coerceAtLeast(0) }
    LazyRow(Modifier.fillMaxWidth(), rememberLazyListState(start), contentPadding = PaddingValues(start = 4.dp, top = 8.dp, bottom = 2.dp, end = 8.dp)) {
        items(choices, key = { it.id }) { c -> FilmFrame(app, source, c, c.id == selected) { onPick(c.id) } }
    }
}

private val FilmBase = Color(0xFF3A2519)

@Composable
private fun FilmFrame(app: SpektraApp, source: LibraryItem?, choice: FrameChoice, on: Boolean, onClick: () -> Unit) {
    // keep showing the last swatch while a new one is printed (e.g. after changing paper)
    var bmp by remember { mutableStateOf(app.swatches.cached(source, choice.look)) }
    LaunchedEffect(source?.id, source?.uri, choice.look) { app.swatches.get(source, choice.look)?.let { bmp = it } }
    val developed by animateFloatAsState(if (bmp != null) 1f else 0f, tween(350), label = "developed")
    // the loaded frame lifts out of the row and its rebate lights up in the roll's colour
    val lift by animateFloatAsState(if (on) 1f else 0f, Motion.settle, label = "lift")
    val ring by animateColorAsState(if (on) choice.accent else Color.Transparent, tween(250), label = "ring")
    val ink by animateColorAsState(if (on) choice.accent else Pal.text, tween(250), label = "ink")
    val frame = RoundedCornerShape(5.dp)
    Column(Modifier.padding(end = 10.dp).width(92.dp)
        .graphicsLayer { translationY = -5.dp.toPx() * lift; scaleX = 1f + 0.04f * lift; scaleY = 1f + 0.04f * lift }
        .springClick(0.93f, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = "${choice.name}，${choice.sub}"; selected = on; role = Role.RadioButton }) {
        Column(Modifier.fillMaxWidth().clip(frame).background(FilmBase).border(1.5.dp, ring, frame)) {
            Perforations("")
            Box(Modifier.padding(horizontal = 7.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(1.5.dp))
                .background(Brush.linearGradient(listOf(choice.accent.copy(alpha = 0.55f), Color(0xFF1A0E0A))))) {
                bmp?.let {
                    Image(it.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer { alpha = developed }, contentScale = ContentScale.Crop)
                }
            }
            Perforations(choice.edge)
        }
        Spacer(Modifier.height(5.dp))
        Text(choice.name, color = ink, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
        Text(choice.sub, color = Pal.dim, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Sprocket holes of the frame's rebate, with the edge print (ISO / PRINT) over them. */
@Composable
private fun Perforations(edge: String) {
    Box(Modifier.fillMaxWidth().height(11.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val hole = Size(5.dp.toPx(), 4.dp.toPx())
            val pitch = 9.5.dp.toPx()
            val count = ((size.width - hole.width) / pitch).toInt() + 1
            var x = (size.width - ((count - 1) * pitch + hole.width)) / 2
            repeat(count) {
                drawRoundRect(Pal.dark, Offset(x, (size.height - hole.height) / 2), hole, CornerRadius(1.dp.toPx()))
                x += pitch
            }
        }
        if (edge.isNotEmpty()) Text(edge, fontFamily = Fonts.mono, fontSize = 6.sp, lineHeight = 7.sp, color = Pal.stripEdge,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 7.dp).background(FilmBase).padding(horizontal = 2.dp))
    }
}
