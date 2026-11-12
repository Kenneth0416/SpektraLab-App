package app.spektralab.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spektralab.R
import app.spektralab.data.LibraryItem
import app.spektralab.engine.Catalog
import app.spektralab.engine.Look
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(state: AppState) {
    val items by state.library.items.collectAsState()
    val filter by state.filter.collectAsState()
    val selecting by state.selecting.collectAsState()
    val picked by state.picked.collectAsState()
    val importing by state.importing.collectAsState()
    // the lightbox "switches on" like a fluorescent tube
    val tube by tubeFlicker()
    // strips are dealt onto the table only when the lightbox opens, not while scrolling
    var dealing by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(900); dealing = false }

    val shown = items.filter { filter == Filter.ALL || (filter == Filter.VIDEO) == it.isVideo }
    // three frames per strip on a phone, more on an unfolded inner screen / tablet
    val perStrip = when (widthClass()) { WidthClass.COMPACT -> 3; WidthClass.MEDIUM -> 4; WidthClass.EXPANDED -> 6 }
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0]
    val strips = remember(shown, perStrip, locale) { buildStrips(shown, perStrip, context) }

    Box(Modifier.fillMaxSize().background(
        Brush.radialGradient(listOf(Pal.glow, Pal.paper), radius = 1800f)
    ).drawWithContent {
        drawContent()
        // tube not fully on yet: the whole table is dimmer
        if (tube < 1f) drawRect(Pal.ink.copy(alpha = 0.32f * (1f - tube)))
    }) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // header
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 14.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.contact_sheet), fontFamily = Fonts.mono, fontSize = 9.sp, color = Pal.grey, letterSpacing = 1.5.sp)
                    // the printed title is swapped for a handwritten note when circling frames
                    AnimatedContent(selecting, label = "title", transitionSpec = {
                        (slideInVertically(Motion.glideOffset) { if (targetState) it / 2 else -it / 2 } + fadeIn(tween(180))) togetherWith
                            (slideOutVertically(Motion.glideOffset) { if (targetState) -it / 2 else it / 2 } + fadeOut(tween(120)))
                    }) { sel ->
                        if (sel) Text(pluralStringResource(R.plurals.selected_frames, picked.size, picked.size),
                            fontFamily = Fonts.hand, fontSize = 34.sp, color = Pal.red)
                        else Row(verticalAlignment = Alignment.Bottom) {
                            Text(stringResource(R.string.library_title), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Pal.ink)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.library_frame_count, items.size), fontFamily = Fonts.mono,
                                fontSize = 10.sp, color = Pal.grey, modifier = Modifier.padding(bottom = 6.dp))
                        }
                    }
                }
                SettingsButton(state::openSettings, Modifier.padding(end = 6.dp))
                Text(stringResource(if (selecting) R.string.done else R.string.select), color = Pal.ink, fontSize = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).border(1.dp, Pal.ink, RoundedCornerShape(16.dp))
                        .springClick { state.toggleSelecting() }.padding(horizontal = 14.dp, vertical = 6.dp))
            }
            // filters with a red crayon underline
            Row(Modifier.padding(start = 20.dp, top = 10.dp, bottom = 4.dp)) {
                listOf(
                    Filter.ALL to stringResource(R.string.all),
                    Filter.PHOTO to stringResource(R.string.photos),
                    Filter.VIDEO to stringResource(R.string.videos),
                ).forEach { (f, label) ->
                    Column(Modifier.padding(end = 20.dp).clickable { state.filter.value = f }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, color = if (filter == f) Pal.ink else Pal.grey, fontSize = 15.sp,
                            fontWeight = if (filter == f) FontWeight.Bold else FontWeight.Normal)
                        // the underline is drawn with a quick crayon stroke each time
                        val stroke by drawOnProgress(filter == f, durationMs = 260)
                        Canvas(Modifier.width(30.dp).height(6.dp)) {
                            if (filter == f) {
                                val p = Path().apply {
                                    moveTo(0f, size.height * 0.6f)
                                    quadraticTo(size.width * 0.5f, size.height * 0.1f, size.width, size.height * 0.5f)
                                }
                                drawPartialPath(p, stroke, Pal.red, 3.dp.toPx())
                            }
                        }
                    }
                }
            }
            if (shown.isEmpty()) {
                EmptyLightbox(Modifier.weight(1f).fillMaxWidth(), filter)
            } else {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 110.dp)) {
                    itemsIndexed(strips, key = { _, it -> it.key }) { i, strip ->
                        // tossed onto the table from alternating sides, then they settle;
                        // removing / filtering slides the remaining strips together
                        val side = if (i % 2 == 0) -1f else 1f
                        Box(Modifier.animateItem().dealIn(i, enabled = dealing, fromX = side * 140f, fromY = 30f, tilt = side * 5f)) {
                            FilmStrip(state, strip, selecting, picked)
                        }
                    }
                }
            }
        }
        // bottom bar
        Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).widthIn(max = SheetMaxWidth).fillMaxWidth()
            .clip(RoundedCornerShape(26.dp)).background(Pal.ink).padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            val clip by state.clip.collectAsState()
            AnimatedContent(selecting, Modifier.fillMaxWidth(), label = "bar", transitionSpec = {
                (fadeIn(tween(200, delayMillis = 80)) + scaleIn(Motion.settle, initialScale = 0.9f)) togetherWith fadeOut(tween(100))
            }) { sel ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    if (!sel) {
                        BarButton(stringResource(if (importing) R.string.importing else R.string.import_action) + if (importing) busyDots() else "",
                            Pal.paper, filled = true) { if (!importing) state.sheet.value = Sheet.IMPORT }
                        BarButton(stringResource(R.string.camera), Pal.paper) { state.openCamera() }
                    } else {
                        BarButton(stringResource(R.string.paste_settings), if (clip != null) Pal.paper else Color(0xFF8F8A7E)) { state.pasteToPicked() }
                        BarButton(stringResource(R.string.recipes), Pal.paper) { state.openRecipes() }
                        BarButton(stringResource(R.string.export_action), Pal.paper) { state.openExport(state.picked.value) }
                        BarButton(stringResource(R.string.remove), Pal.paper) { state.removePicked() }
                    }
                }
            }
        }
    }
}

@Composable
private fun BarButton(label: String, color: Color, filled: Boolean = false, onClick: () -> Unit) {
    Text(label, color = if (filled) Pal.ink else color, fontSize = 15.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(20.dp))
            .background(if (filled) Pal.paper else Color.Transparent)
            .springClick(0.9f, onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp))
}

/** "…" that fills up one dot at a time while something is in progress. */
@Composable
fun busyDots(): String {
    val t by rememberInfiniteTransition(label = "dots").animateFloat(0f, 4f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "dots")
    return "·".repeat(t.toInt().coerceIn(0, 3)).padEnd(3, ' ')
}

/** A counter whose digits roll up or down when the value changes. */
@Composable
fun RollingNumber(value: Int, font: androidx.compose.ui.text.font.FontFamily, size: androidx.compose.ui.unit.TextUnit, color: Color) {
    AnimatedContent(value, label = "count", transitionSpec = {
        val up = targetState > initialState
        (slideInVertically(Motion.settleOffset) { if (up) it else -it } + fadeIn(tween(120))) togetherWith
            (slideOutVertically(tween(140)) { if (up) -it else it } + fadeOut(tween(100)))
    }) { v -> Text("$v", fontFamily = font, fontSize = size, color = color) }
}

@Composable
private fun EmptyLightbox(modifier: Modifier, filter: Filter) {
    val bob by idleBob()
    val arrow by drawOnProgress(filter, durationMs = 700, delayMs = 500)
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(when (filter) { Filter.VIDEO -> R.string.empty_videos; Filter.PHOTO -> R.string.empty_photos; else -> R.string.empty_lightbox }),
                fontFamily = Fonts.hand, fontSize = 32.sp, color = Pal.grey,
                modifier = Modifier.graphicsLayer { translationY = bob * 4.dp.toPx(); rotationZ = bob * 0.8f })
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.empty_lightbox_hint), color = Pal.grey, fontSize = 13.sp)
            // a pencilled arrow that draws itself towards the import button
            Canvas(Modifier.padding(top = 14.dp).size(60.dp, 90.dp).graphicsLayer { translationY = bob * 3.dp.toPx() }) {
                val shaft = Path().apply {
                    moveTo(size.width * 0.75f, 0f)
                    cubicTo(size.width * 0.1f, size.height * 0.25f, size.width * 0.9f, size.height * 0.55f, size.width * 0.35f, size.height * 0.95f)
                }
                drawPartialPath(shaft, (arrow / 0.8f).coerceAtMost(1f), Pal.grey, 2.dp.toPx())
                val head = Path().apply {
                    moveTo(size.width * 0.32f, size.height * 0.72f)
                    lineTo(size.width * 0.35f, size.height * 0.95f)
                    lineTo(size.width * 0.62f, size.height * 0.88f)
                }
                drawPartialPath(head, ((arrow - 0.8f) / 0.2f).coerceIn(0f, 1f), Pal.grey, 2.dp.toPx())
            }
        }
    }
}

class Strip(val key: String, val label: String, val rot: Float, val frames: List<LibraryItem>, val slots: Int)

private fun dayLabel(context: android.content.Context, t: Long): String {
    val locale = context.resources.configuration.locales[0]
    val c = Calendar.getInstance(locale).apply { timeInMillis = t }
    val now = Calendar.getInstance(locale)
    val days = ((now.timeInMillis - t) / 86_400_000L).toInt()
    fun format(pattern: String): String = java.text.SimpleDateFormat(
        android.text.format.DateFormat.getBestDateTimePattern(locale, pattern), locale).format(Date(t))
    val base = format("MMMd")
    val sameDay = now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)
    return when {
        sameDay -> context.getString(R.string.date_today, base)
        days < 7 -> context.getString(R.string.date_with_weekday, base, format("EEE"))
        now.get(Calendar.YEAR) != c.get(Calendar.YEAR) -> format("yMMMd")
        else -> base
    }
}

private fun buildStrips(items: List<LibraryItem>, perStrip: Int, context: android.content.Context): List<Strip> {
    val rots = floatArrayOf(-0.7f, 0.5f, -0.4f, 0.8f)
    val out = ArrayList<Strip>()
    val byDay = items.sortedByDescending { it.dateTaken }.groupBy { dayLabel(context, it.dateTaken) }
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
    val mediaType = stringResource(if (item.isVideo) R.string.video else R.string.photo)
    val desc = stringResource(R.string.media_description, mediaType, item.name) +
        if (order >= 0) stringResource(R.string.media_selected) else ""
    // a circled frame sinks a little under the crayon
    val sink by animateFloatAsState(if (order >= 0) 0.94f else 1f, Motion.settle, label = "sink")
    Box(modifier.aspectRatio(1.5f).graphicsLayer { scaleX = sink; scaleY = sink }
        .clip(RoundedCornerShape(2.dp)).background(Color(0xFF3A2C22))
        .semantics { contentDescription = desc }
        .springClick(0.92f) { if (selecting) state.togglePick(item.id) else state.openEdit(item.id) }) {
        // the picture "develops" out of the paper back when its thumbnail is ready
        Crossfade(thumb, animationSpec = tween(450), label = "develop") { t ->
            if (t != null) Image(t.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else {
                // not developed yet: the back of the photo paper (spektrafilm virtual paper back)
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val back = remember { app.spektralab.render.PaperBack.render(ctx, 240, 160) }
                Image(back.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        val tag = if (item.isVideo) mmss(item.durationMs / 1000) else if (item.isRaw) "RAW" else ""
        if (tag.isNotEmpty()) Text(tag, fontFamily = Fonts.mono, fontSize = 8.sp, color = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp).background(Color(0xCC0D0B09)).padding(horizontal = 3.dp, vertical = 1.dp))
        if (item.look != Look.DEFAULT) RedTick(Modifier.align(Alignment.TopEnd).padding(3.dp).size(14.dp))
        // drawn on when picked, quickly rubbed out when unpicked
        androidx.compose.animation.AnimatedVisibility(order >= 0, enter = EnterTransition.None, exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 1.08f)) {
            val shownOrder = remember { mutableIntStateOf(order) }
            if (order >= 0) shownOrder.intValue = order
            CrayonCircle(shownOrder.intValue + 1, Modifier.fillMaxSize())
        }
    }
}

/** Hand-drawn red circle with a handwritten order number (multi-select). */
@Composable
fun CrayonCircle(number: Int, modifier: Modifier) {
    // the hand goes once around, then writes the number
    val stroke by drawOnProgress(Unit, durationMs = 380)
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(260); pop.animateTo(1f, Motion.settle) }
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
            drawPartialPath(p, stroke, Pal.red, 3.dp.toPx())
        }
        Box(Modifier.align(Alignment.TopStart).padding(start = 4.dp).graphicsLayer {
            scaleX = pop.value; scaleY = pop.value; alpha = pop.value.coerceIn(0f, 1f); rotationZ = -18f * (1f - pop.value)
        }) { RollingNumber(number, Fonts.hand, 24.sp, Pal.red) }
    }
}

@Composable
private fun RedTick(modifier: Modifier) {
    val stroke by drawOnProgress(Unit, durationMs = 300, delayMs = 150)
    Canvas(modifier) {
        val p = Path().apply {
            moveTo(size.width * 0.1f, size.height * 0.55f)
            lineTo(size.width * 0.4f, size.height * 0.85f)
            lineTo(size.width * 0.95f, size.height * 0.1f)
        }
        drawPartialPath(p, stroke, Pal.red, 2.5.dp.toPx())
    }
}

fun mmss(secs: Long): String = "${secs / 60}:${(secs % 60).toString().padStart(2, '0')}"
