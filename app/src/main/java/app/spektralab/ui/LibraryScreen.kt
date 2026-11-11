package app.spektralab.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spektralab.data.LibraryItem
import app.spektralab.engine.Catalog
import app.spektralab.engine.Look
import java.util.Calendar
import java.util.Locale

@Composable
fun LibraryScreen(state: AppState) {
    val items by state.library.items.collectAsState()
    val filter by state.filter.collectAsState()
    val selecting by state.selecting.collectAsState()
    val picked by state.picked.collectAsState()
    val importing by state.importing.collectAsState()
    // the lightbox "switches on" like a fluorescent tube
    var lit by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { lit = true }
    val glow by animateFloatAsState(if (lit) 1f else 0.6f, tween(700, easing = FastOutSlowInEasing), label = "glow")

    val shown = items.filter { filter == Filter.ALL || (filter == Filter.VIDEO) == it.isVideo }
    // three frames per strip on a phone, more on an unfolded inner screen / tablet
    val perStrip = when (widthClass()) { WidthClass.COMPACT -> 3; WidthClass.MEDIUM -> 4; WidthClass.EXPANDED -> 6 }
    val strips = remember(shown, perStrip) { buildStrips(shown, perStrip) }

    Box(Modifier.fillMaxSize().background(
        Brush.radialGradient(listOf(Pal.glow, Pal.paper.copy(alpha = 0.96f + 0.04f * glow)), radius = 1800f)
    )) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // header
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 14.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("SPEKTRALAB · CONTACT SHEET", fontFamily = Fonts.mono, fontSize = 9.sp, color = Pal.grey, letterSpacing = 1.5.sp)
                    if (selecting) Text("圈出 ${picked.size} 张", fontFamily = Fonts.hand, fontSize = 34.sp, color = Pal.red)
                    else Row(verticalAlignment = Alignment.Bottom) {
                        Text("图库", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Pal.ink)
                        Spacer(Modifier.width(10.dp))
                        Text("${items.size} FRAMES", fontFamily = Fonts.mono, fontSize = 10.sp, color = Pal.grey,
                            modifier = Modifier.padding(bottom = 6.dp))
                    }
                }
                Text(if (selecting) "完成" else "选择", color = Pal.ink, fontSize = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).border(1.dp, Pal.ink, RoundedCornerShape(16.dp))
                        .clickable { state.toggleSelecting() }.padding(horizontal = 14.dp, vertical = 6.dp))
            }
            // filters with a red crayon underline
            Row(Modifier.padding(start = 20.dp, top = 10.dp, bottom = 4.dp)) {
                listOf(Filter.ALL to "全部", Filter.PHOTO to "照片", Filter.VIDEO to "视频").forEach { (f, label) ->
                    Column(Modifier.padding(end = 20.dp).clickable { state.filter.value = f }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, color = if (filter == f) Pal.ink else Pal.grey, fontSize = 15.sp,
                            fontWeight = if (filter == f) FontWeight.Bold else FontWeight.Normal)
                        Canvas(Modifier.width(30.dp).height(6.dp)) {
                            if (filter == f) {
                                val p = Path().apply {
                                    moveTo(0f, size.height * 0.6f)
                                    quadraticTo(size.width * 0.5f, size.height * 0.1f, size.width, size.height * 0.5f)
                                }
                                drawPath(p, Pal.red, style = Stroke(width = 3.dp.toPx()))
                            }
                        }
                    }
                }
            }
            if (shown.isEmpty()) {
                EmptyLightbox(Modifier.weight(1f).fillMaxWidth(), filter)
            } else {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 110.dp)) {
                    items(strips, key = { it.key }) { strip ->
                        FilmStrip(state, strip, selecting, picked)
                    }
                }
            }
        }
        // bottom bar
        Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).widthIn(max = SheetMaxWidth).fillMaxWidth()
            .clip(RoundedCornerShape(26.dp)).background(Pal.ink).padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            val clip by state.clip.collectAsState()
            if (!selecting) {
                BarButton(if (importing) "导入中…" else "＋ 导入", Pal.paper, filled = true) { if (!importing) state.sheet.value = Sheet.IMPORT }
                BarButton("相机", Pal.paper.copy(alpha = 0.45f)) { state.say("相机暂未开放，先从相册导入") }
            } else {
                BarButton("粘贴设置", if (clip != null) Pal.paper else Color(0xFF8F8A7E)) { state.pasteToPicked() }
                BarButton("配方", Pal.paper) { state.openRecipes() }
                BarButton("导出", Pal.paper) { state.openExport(state.picked.value) }
                BarButton("移除", Pal.paper) { state.removePicked() }
            }
        }
    }
}

@Composable
private fun BarButton(label: String, color: Color, filled: Boolean = false, onClick: () -> Unit) {
    Text(label, color = if (filled) Pal.ink else color, fontSize = 15.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(20.dp))
            .background(if (filled) Pal.paper else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp))
}

@Composable
private fun EmptyLightbox(modifier: Modifier, filter: Filter) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(when (filter) { Filter.VIDEO -> "还没有视频"; Filter.PHOTO -> "还没有照片"; else -> "灯箱是空的" },
                fontFamily = Fonts.hand, fontSize = 32.sp, color = Pal.grey)
            Spacer(Modifier.height(8.dp))
            Text("点「＋ 导入」从相册或文件放上几张底片", color = Pal.grey, fontSize = 13.sp)
        }
    }
}

class Strip(val key: String, val label: String, val rot: Float, val frames: List<LibraryItem>, val slots: Int)

private fun dayLabel(t: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = t }
    val now = Calendar.getInstance()
    val days = ((now.timeInMillis - t) / 86_400_000L).toInt()
    val base = "${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日"
    val sameDay = now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)
    return when {
        sameDay -> "$base · 今天"
        days < 7 -> base + " · " + arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")[c.get(Calendar.DAY_OF_WEEK) - 1]
        now.get(Calendar.YEAR) != c.get(Calendar.YEAR) -> "${c.get(Calendar.YEAR)}年$base"
        else -> base
    }
}

private fun buildStrips(items: List<LibraryItem>, perStrip: Int): List<Strip> {
    val rots = floatArrayOf(-0.7f, 0.5f, -0.4f, 0.8f)
    val out = ArrayList<Strip>()
    val byDay = items.sortedByDescending { it.dateTaken }.groupBy { dayLabel(it.dateTaken) }
    for ((label, group) in byDay) {
        group.chunked(perStrip).forEachIndexed { i, frames ->
            out += Strip("${frames.first().id}", if (i == 0) label else "", rots[out.size % 4], frames, perStrip)
        }
    }
    return out
}

@Composable
private fun FilmStrip(state: AppState, strip: Strip, selecting: Boolean, picked: List<Long>) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
        if (strip.label.isNotEmpty()) Text(strip.label, fontFamily = Fonts.hand, fontSize = 22.sp, color = Pal.ink.copy(alpha = 0.75f),
            modifier = Modifier.padding(start = 6.dp, bottom = 2.dp))
        Box(Modifier.rotate(strip.rot).fillMaxWidth().clip(RoundedCornerShape(3.dp)).background(Pal.strip)) {
            Column {
                Sprockets(strip.frames.firstOrNull()?.look?.let { Catalog.film(it.film).name.uppercase(Locale.ROOT) } ?: "")
                Row(Modifier.padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    strip.frames.forEach { item ->
                        Frame(state, item, selecting, picked.indexOf(item.id), Modifier.weight(1f))
                    }
                    repeat(strip.slots - strip.frames.size) { Spacer(Modifier.weight(1f)) }
                }
                Sprockets("")
            }
        }
    }
}

@Composable
private fun Sprockets(edge: String) {
    Box(Modifier.fillMaxWidth().height(18.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val hole = Size(7.dp.toPx(), 5.dp.toPx())
            var x = 6.dp.toPx()
            while (x < size.width) {
                drawRoundRect(Pal.paper.copy(alpha = 0.9f), Offset(x, (size.height - hole.height) / 2), hole, CornerRadius(1.5.dp.toPx()))
                x += 14.dp.toPx()
            }
        }
        if (edge.isNotEmpty()) Text(edge, fontFamily = Fonts.mono, fontSize = 7.sp, color = Pal.stripEdge,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 80.dp).background(Pal.strip).padding(horizontal = 4.dp))
    }
}

@Composable
private fun Frame(state: AppState, item: LibraryItem, selecting: Boolean, order: Int, modifier: Modifier) {
    val thumb by produceState(state.app.thumbs.cached(item), item.id, item.look) {
        value = state.app.thumbs.get(item)
    }
    val desc = (if (item.isVideo) "视频：" else "照片：") + item.name + if (order >= 0) "，已圈选" else ""
    Box(modifier.aspectRatio(1.5f).clip(RoundedCornerShape(2.dp)).background(Color(0xFF3A2C22))
        .semantics { contentDescription = desc }
        .clickable { if (selecting) state.togglePick(item.id) else state.openEdit(item.id) }) {
        thumb?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } ?: Box(Modifier.fillMaxSize().alpha(0.4f).background(Brush.linearGradient(listOf(Color(0xFF5A4636), Color(0xFF2A1F18)))))
        val tag = if (item.isVideo) mmss(item.durationMs / 1000) else if (item.isRaw) "RAW" else ""
        if (tag.isNotEmpty()) Text(tag, fontFamily = Fonts.mono, fontSize = 8.sp, color = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp).background(Color(0xCC0D0B09)).padding(horizontal = 3.dp, vertical = 1.dp))
        if (item.look != Look.DEFAULT) RedTick(Modifier.align(Alignment.TopEnd).padding(3.dp).size(14.dp))
        if (order >= 0) CrayonCircle(order + 1, Modifier.fillMaxSize())
    }
}

/** Hand-drawn red circle with a handwritten order number (multi-select). */
@Composable
fun CrayonCircle(number: Int, modifier: Modifier) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val p = Path()
            val cx = size.width / 2; val cy = size.height / 2
            val rx = size.width * 0.46f; val ry = size.height * 0.44f
            for (i in 0..40) {
                val a = (i / 40f) * 6.6f - 0.3f
                val wob = 1f + 0.04f * kotlin.math.sin(a * 3f)
                val x = cx + rx * wob * kotlin.math.cos(a)
                val y = cy + ry * wob * kotlin.math.sin(a)
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            drawPath(p, Pal.red, style = Stroke(width = 3.dp.toPx()))
        }
        Text("$number", fontFamily = Fonts.hand, fontSize = 24.sp, color = Pal.red,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp))
    }
}

@Composable
private fun RedTick(modifier: Modifier) {
    Canvas(modifier) {
        val p = Path().apply {
            moveTo(size.width * 0.1f, size.height * 0.55f)
            lineTo(size.width * 0.4f, size.height * 0.85f)
            lineTo(size.width * 0.95f, size.height * 0.1f)
        }
        drawPath(p, Pal.red, style = Stroke(width = 2.5.dp.toPx()))
    }
}

fun mmss(secs: Long): String = "${secs / 60}:${(secs % 60).toString().padStart(2, '0')}"
