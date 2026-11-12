package app.spektralab.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.spektralab.R
import app.spektralab.data.LibraryItem
import app.spektralab.data.Media
import app.spektralab.engine.Catalog
import app.spektralab.engine.Look
import app.spektralab.engine.LookMapper
import app.spektralab.render.FilmPreviewView
import app.spektralab.render.Framing
import app.spektralab.render.PaperBack
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.util.Locale

private data class ToolDef(val id: String, val keys: (Look) -> Look)

private val TOOLS = listOf(
    ToolDef("film") { it.copy(film = Look.DEFAULT.film) },
    ToolDef("paper") { it.copy(paper = "auto", printEv = 0.0, glare = 0.0) },
    ToolDef("exp") { it.copy(filmEv = 0.0, push = 0) },
    ToolDef("hal") { it.copy(halation = 1.0, halationSize = 1.0) },
    ToolDef("grain") { it.copy(grain = 1.0, grainSize = 1.0) },
    ToolDef("diff") { it.copy(diffusion = "off", diffusionAmount = 0.5) },
    ToolDef("color") { it.copy(yFilter = 0.0, mFilter = 0.0) },
    ToolDef("fmt") { it.copy(format = "135", border = "none", dateStamp = false) },
)

@Composable
private fun toolLabel(id: String): String = stringResource(when (id) {
    "film" -> R.string.tool_film
    "paper" -> R.string.tool_paper
    "exp" -> R.string.tool_exposure
    "hal" -> R.string.tool_halation
    "grain" -> R.string.tool_grain
    "diff" -> R.string.tool_diffusion
    "color" -> R.string.tool_color
    else -> R.string.tool_format
})

@Composable
fun summary(look: Look): String {
    val film = Catalog.film(look.film)
    val p = when {
        film.kind == "positive" -> stringResource(R.string.direct_scan)
        look.paper == "auto" -> stringResource(R.string.auto_paper)
        else -> paperLabel(Catalog.paper(look.paper))
    }
    return "${film.name} × $p"
}

@Composable
fun EditorScreen(state: AppState, id: Long) {
    val items by state.library.items.collectAsState()
    val item = items.firstOrNull { it.id == id } ?: run {
        // removed while open: leave the darkroom (state change outside composition)
        LaunchedEffect(id) { state.back() }
        return
    }
    val look = item.look
    // the whole darkroom re-tints when another roll is loaded
    val accent by animateColorAsState(Color(Catalog.film(look.film).accent), tween(500), label = "accent")
    val history by state.history.collectAsState()
    val redo by state.redo.collectAsState()
    var tool by rememberSaveable { mutableStateOf("film") }
    var playing by remember { mutableStateOf(false) }
    var hold by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val preview = remember { FilmPreviewView(context) }
    DisposableEffect(Unit) { onDispose { preview.release() } }
    val engine = state.app.engine
    var developed by remember(item.id) { mutableStateOf(false) }
    val gray18 by state.gray18.collectAsState()
    val revealOn by state.reveal.collectAsState()
    LaunchedEffect(revealOn) { preview.revealEnabled = revealOn }
    DisposableEffect(preview) {
        preview.onFirstFrame = { developed = true }
        preview.onVideoState = { isPlaying, _ -> playing = isPlaying }
        preview.onGpuError = { msg -> state.say(msg) }
        onDispose { preview.onFirstFrame = null; preview.onVideoState = null; preview.onGpuError = null }
    }
    // pause playback and the GL thread with the activity
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> preview.pauseForBackground()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> preview.resumeFromBackground()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(item.id) { preview.setFrameInfo(item.id.toInt() % 36 + 1, item.dateTaken) }

    // source
    LaunchedEffect(item.id) {
        playing = false
        if (item.isVideo) preview.setVideo(Uri.parse(item.uri), item.rotation, item.width, item.height, item.autoEv)
        else {
            val bmp = withContext(Dispatchers.IO) { runCatching { Media.loadSource(context, Uri.parse(item.uri), previewDecodeEdge(context), tryRaw = item.isRaw) }.getOrNull() }
            if (bmp != null) preview.setPhoto(bmp, item.autoEv, item.id.toInt()) else state.say(R.string.unread_original)
        }
    }
    // look -> spektrafilm tables (spectral print stage), off the main thread
    val lookState = rememberUpdatedState(look)
    LaunchedEffect(Unit) {
        snapshotFlow { lookState.value }.distinctUntilChanged().collectLatest { l ->
            val setup = withContext(Dispatchers.Default) { runCatching { state.app.looks.get(l) }.getOrNull() }
            if (setup != null) preview.setLook(l, setup)
        }
    }
    LaunchedEffect(hold) { preview.setOriginal(hold) }

    val fold = LocalFold.current
    val wide = widthClass() != WidthClass.COMPACT
    var origin by remember { mutableStateOf(Offset.Zero) }
    val mode = when {
        fold.posture == Posture.TABLETOP -> EditorMode.TABLETOP
        fold.posture == Posture.BOOK -> EditorMode.BOOK
        wide -> EditorMode.SIDE
        else -> EditorMode.COMPACT
    }
    val hinge = fold.hinge?.translate(-origin)

    EditorLayout(mode, hinge, Modifier.fillMaxSize().background(Pal.dark).statusBarsPadding().navigationBarsPadding()
        .displayCutoutPadding().onGloballyPositioned { origin = it.positionInWindow() }) {
        // 0. top bar
        Row(Modifier.fillMaxWidth().dealIn(0, fromY = -40f).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.back_to_lightbox), color = Pal.text, fontSize = 15.sp, modifier = Modifier.springClick { state.back() }.padding(6.dp))
            Spacer(Modifier.weight(1f))
            KickGlyph("↶", history.isNotEmpty(), -1f) { state.undo() }
            KickGlyph("↷", redo.isNotEmpty(), 1f) { state.redoStep() }
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.recipes), color = Pal.text, fontSize = 15.sp, modifier = Modifier.springClick { state.openRecipes() }.padding(8.dp))
            Text(stringResource(R.string.export_action), color = Pal.dink, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.springClick(0.9f) { state.openExport(listOf(item.id)) }
                    .clip(RoundedCornerShape(16.dp)).background(accent).padding(horizontal = 14.dp, vertical = 6.dp))
        }
        // 1. preview
        val backdrop by animateColorAsState(if (gray18) Gray18 else Color.Transparent, tween(400), label = "backdrop")
        Box(Modifier.fillMaxSize().background(backdrop)
            .padding(horizontal = 14.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            val framing = remember(item.width, item.height, look.format, look.border) {
                Framing.compute(item.width.coerceAtLeast(1), item.height.coerceAtLeast(1), look, 2048)
            }
            val aspect = framing.outWidth.toFloat() / framing.outHeight
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // fit the frame into whatever the pane gives us (portrait or landscape pane)
                val boxAspect = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else 1f
                Box(Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = aspect < boxAspect)
                    .pointerInput(Unit) { detectTapGestures(onPress = { hold = true; tryAwaitRelease(); hold = false }) }) {
                    AndroidView({ preview }, Modifier.fillMaxSize())
                    // spektrafilm's virtual photo-paper back until the first frame is developed
                    androidx.compose.animation.AnimatedVisibility(!developed, enter = EnterTransition.None, exit = fadeOut(tween(350))) {
                        val back = remember(item.id) {
                            PaperBack.render(context, 540, (540 / aspect).toInt().coerceIn(120, 1600), item.id.toInt())
                        }
                        Image(back.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    CropCorners(Modifier.fillMaxSize(), hold)
                    // the label flips like a film counter between the look and "original"
                    AnimatedContent(if (hold) stringResource(R.string.original_image) else summary(look).uppercase(Locale.ROOT),
                        Modifier.align(Alignment.BottomStart).padding(10.dp), label = "badge", transitionSpec = {
                            (slideInVertically(Motion.settleOffset) { it } + fadeIn(tween(120))) togetherWith
                                (slideOutVertically(tween(140)) { -it } + fadeOut(tween(100)))
                        }) { label ->
                        Text(label, fontFamily = Fonts.mono, fontSize = 9.sp, color = Pal.text,
                            modifier = Modifier.background(Color(0x99140807)).padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
            Safelight(Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        // 2. actions
        Row(Modifier.fillMaxWidth().dealIn(1, fromY = 0f).padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (item.isVideo) {
                Box(Modifier.springClick(0.9f) { playing = !playing; preview.play(playing) }
                    .clip(RoundedCornerShape(14.dp)).background(Pal.text).animateContentSize(Motion.glideSize)
                    .padding(horizontal = 12.dp, vertical = 5.dp)) {
                    Crossfade(playing, animationSpec = tween(160), label = "play") { p ->
                        Text(if (p) stringResource(R.string.pause) else stringResource(R.string.play_with_duration, mmss(item.durationMs / 1000)),
                            color = Pal.dink, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.width(10.dp))
            }
            val hintColor by animateColorAsState(if (hold) accent else Pal.dim, tween(180), label = "holdHint")
            Text(stringResource(if (hold) R.string.release_to_film else R.string.hold_for_original), color = hintColor,
                fontSize = 12.sp, modifier = Modifier.weight(1f))
            val clip by state.clip.collectAsState()
            // "copy" lifts the settings off the print, "paste" lights up once there is something to paste
            Text(stringResource(R.string.copy_settings), color = Pal.text, fontSize = 13.sp, modifier = Modifier.springClick(0.88f) { state.copyLook() }.padding(8.dp))
            val pasteColor by animateColorAsState(if (clip != null) Pal.text else Pal.off, tween(300), label = "paste")
            Text(stringResource(R.string.paste_settings), color = pasteColor, fontSize = 13.sp,
                modifier = Modifier.springClick(0.88f) { state.pasteLook() }.padding(8.dp))
        }
        // 3. tools: tabs + panel
        ToolDeck(state, tool, { tool = it }, look, accent, engine.film(LookMapper.filmStock(look)).isPositive, item.isVideo,
            onSet = { key, l -> state.setLook(key, l) }, onApply = { state.applyLook(it) })
    }
}

/**
 * Tool tabs + the panel of the selected tool, bound to any look (an item in the editor,
 * or the look loaded in the camera).
 */
@Composable
fun ToolDeck(state: AppState, tool: String, onTool: (String) -> Unit, look: Look, accent: Color, positive: Boolean, isVideo: Boolean,
             onSet: (String, Look) -> Unit, onApply: (Look) -> Unit) {
    Column(Modifier.fillMaxSize().dealIn(2, fromY = 120f)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            TOOLS.forEach { t ->
                val modified = t.keys(look) != look
                val on = t.id == tool
                val dot by animateFloatAsState(if (modified) 1f else 0f, Motion.settle, label = "dot")
                val bar by animateFloatAsState(if (on) 1f else 0f, Motion.settle, label = "bar")
                val ink by animateColorAsState(if (on) Pal.text else Pal.dim, tween(200), label = "tab")
                Column(Modifier.springClick(0.9f) { onTool(t.id) }.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    // a dot of the roll's colour pops up when this tool has been touched
                    Box(Modifier.size(5.dp).graphicsLayer { scaleX = dot; scaleY = dot }.clip(CircleShape).background(accent))
                    Text(toolLabel(t.id), color = ink, fontSize = 14.sp)
                    Box(Modifier.width(22.dp).height(2.dp).graphicsLayer { scaleX = bar; alpha = bar.coerceIn(0f, 1f) }.background(accent))
                }
            }
        }
        // panels slide in the direction of the tab that was tapped
        AnimatedContent(tool, Modifier.fillMaxWidth().weight(1f).background(Pal.darkPanel), label = "tool", transitionSpec = {
            val dir = if (TOOLS.indexOfFirst { it.id == targetState } >= TOOLS.indexOfFirst { it.id == initialState }) 1 else -1
            (slideInHorizontally(Motion.glideOffset) { dir * it / 5 } + fadeIn(tween(200, delayMillis = 60))) togetherWith
                (slideOutHorizontally(Motion.glideOffset) { -dir * it / 5 } + fadeOut(tween(110)))
        }) { t ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp)) {
                ToolPanel(state, t, isVideo, look, accent, positive, onSet, onApply)
            }
        }
    }
}

/** Undo / redo arrow that swings in its direction when tapped and springs back. */
@Composable
private fun KickGlyph(glyph: String, enabled: Boolean, dir: Float, onClick: () -> Unit) {
    val rot = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val color by animateColorAsState(if (enabled) Pal.text else Pal.off, tween(200), label = "glyph")
    Text(glyph, color = color, fontSize = 22.sp, modifier = Modifier
        .clickable(remember { MutableInteractionSource() }, null) {
            onClick()
            if (enabled) scope.launch { rot.snapTo(dir * 70f); rot.animateTo(0f, Motion.settle) }
            else scope.launch { rot.snapTo(dir * 8f); rot.animateTo(0f, Motion.press) }
        }
        .padding(horizontal = 10.dp).graphicsLayer { rotationZ = rot.value })
}

/** Preview source resolution: enough for the largest (unfolded) display, capped for GPU memory. */
private fun previewDecodeEdge(context: android.content.Context): Int {
    val long = if (android.os.Build.VERSION.SDK_INT >= 30) {
        val b = context.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
        maxOf(b.width(), b.height())
    } else context.resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) }
    return long.coerceIn(1600, 3072)
}

enum class EditorMode { COMPACT, SIDE, BOOK, TABLETOP }

/**
 * Places the editor's four parts (top bar, preview, actions, tools) without
 * changing their order in the composition, so the GL preview survives
 * folding / unfolding:
 *  - COMPACT  phone: stacked, tools at the bottom
 *  - SIDE     unfolded inner screen / tablet: preview left, tools in a side pane
 *  - BOOK     vertical hinge: preview and tools on either side of the hinge
 *  - TABLETOP half-folded on a table: preview above the hinge, tools below
 */
@Composable
internal fun EditorLayout(mode: EditorMode, hinge: Rect?, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { m, c ->
        val w = c.maxWidth
        val h = c.maxHeight
        val loose = { cw: Int, ch: Int -> Constraints(0, cw.coerceAtLeast(0), 0, ch.coerceAtLeast(0)) }
        val fixed = { cw: Int, ch: Int -> Constraints.fixed(cw.coerceAtLeast(0), ch.coerceAtLeast(0)) }
        when (mode) {
            EditorMode.COMPACT -> {
                val top = m[0].measure(loose(w, h))
                val actions = m[2].measure(loose(w, h))
                val toolsH = minOf(300.dp.roundToPx(), (h * 0.42f).toInt())
                val tools = m[3].measure(fixed(w, toolsH))
                val previewH = h - top.height - actions.height - toolsH
                val preview = m[1].measure(fixed(w, previewH))
                layout(w, h) {
                    top.place(0, 0)
                    preview.place(0, top.height)
                    actions.place(0, top.height + previewH)
                    tools.place(0, h - toolsH)
                }
            }
            EditorMode.SIDE, EditorMode.BOOK -> {
                // split at the hinge in book posture, otherwise give tools a side pane
                val paneW = (w * 0.38f).toInt().coerceIn(340.dp.roundToPx(), 460.dp.roundToPx())
                val leftW = if (mode == EditorMode.BOOK && hinge != null && hinge.left > 0 && hinge.left < w) hinge.left.toInt() else w - paneW
                val rightX = if (mode == EditorMode.BOOK && hinge != null && hinge.right < w) hinge.right.toInt() else leftW
                val top = m[0].measure(loose(w, h))
                val actions = m[2].measure(loose(leftW, h))
                val previewH = h - top.height - actions.height
                val preview = m[1].measure(fixed(leftW, previewH))
                val tools = m[3].measure(fixed(w - rightX, h - top.height))
                layout(w, h) {
                    top.place(0, 0)
                    preview.place(0, top.height)
                    actions.place(0, top.height + previewH)
                    tools.place(rightX, top.height)
                }
            }
            EditorMode.TABLETOP -> {
                val hingeTop = hinge?.top?.toInt()?.takeIf { it in 1 until h } ?: (h / 2)
                val hingeBottom = hinge?.bottom?.toInt()?.takeIf { it in hingeTop..h } ?: hingeTop
                val top = m[0].measure(loose(w, hingeTop))
                val preview = m[1].measure(fixed(w, hingeTop - top.height))
                val actions = m[2].measure(loose(w, h - hingeBottom))
                val tools = m[3].measure(fixed(w, h - hingeBottom - actions.height))
                layout(w, h) {
                    top.place(0, 0)
                    preview.place(0, top.height)
                    actions.place(0, hingeBottom)
                    tools.place(0, hingeBottom + actions.height)
                }
            }
        }
    }
}

/** Red crop marks: they close in on the frame when it appears and open up while the original is held. */
@Composable
internal fun CropCorners(modifier: Modifier, spread: Boolean) {
    val enter = remember { Animatable(1f) }
    LaunchedEffect(Unit) { delay(250); enter.animateTo(0f, Motion.settle) }
    val open by animateFloatAsState(if (spread) 1f else 0f, Motion.settle, label = "corners")
    Canvas(modifier) {
        val out = 22.dp.toPx() * enter.value + 7.dp.toPx() * open
        val l = 16.dp.toPx() + 6.dp.toPx() * open
        val w = 2.dp.toPx()
        val c = Pal.safelight.copy(alpha = 0.9f * (1f - enter.value).coerceIn(0f, 1f))
        listOf(Offset(0f, 0f) to (1 to 1), Offset(size.width, 0f) to (-1 to 1),
            Offset(0f, size.height) to (1 to -1), Offset(size.width, size.height) to (-1 to -1)).forEach { (o0, d) ->
            val o = Offset(o0.x - d.first * out, o0.y - d.second * out)
            drawLine(c, o, Offset(o.x + d.first * l, o.y), w)
            drawLine(c, o, Offset(o.x, o.y + d.second * l), w)
        }
    }
}

/** Darkroom safelight: a slowly breathing red lamp with a soft halo. */
@Composable
internal fun Safelight(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "safelight")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    Canvas(modifier.size(26.dp)) {
        drawCircle(Brush.radialGradient(listOf(Pal.safelight.copy(alpha = 0.45f * a), Color.Transparent), radius = size.minDimension / 2 * (0.7f + 0.3f * a)))
        drawCircle(Pal.safelight.copy(alpha = 0.4f + 0.6f * a), 4.dp.toPx())
    }
}

@Composable
private fun ToolPanel(state: AppState, tool: String, isVideo: Boolean, look: Look, accent: Color, positive: Boolean,
                      onSet: (String, Look) -> Unit, onApply: (Look) -> Unit) {
    val (title, desc) = when (tool) {
        "film" -> stringResource(R.string.tool_title_film, Catalog.film(look.film).name) to stringResource(R.string.film_description)
        "paper" -> stringResource(R.string.tool_title_paper,
            if (positive) stringResource(R.string.direct_scan) else paperLabel(Catalog.paper(look.paper))) to stringResource(R.string.paper_description)
        "exp" -> stringResource(R.string.tool_title_exposure) to stringResource(R.string.exposure_description)
        "hal" -> stringResource(R.string.tool_halation) to stringResource(R.string.halation_description)
        "grain" -> stringResource(R.string.tool_grain) to stringResource(R.string.grain_description)
        "diff" -> stringResource(R.string.tool_title_diffusion) to stringResource(R.string.diffusion_description)
        "color" -> stringResource(R.string.tool_title_color) to stringResource(R.string.color_description)
        else -> stringResource(R.string.tool_title_format, formatLabel(Catalog.format(look.format))) to stringResource(R.string.format_description)
    }
    fun set(key: String, l: Look) = onSet(key, l)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Crossfade(title, animationSpec = tween(220), label = "title") { tt ->
                Text(tt, color = Pal.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Text(desc, color = Pal.dim, fontSize = 12.sp)
        }
        Text(stringResource(R.string.reset), color = Pal.dim, fontSize = 13.sp, modifier = Modifier.springClick(0.85f) {
            TOOLS.firstOrNull { it.id == tool }?.let { onApply(it.keys(look)) }
        }.padding(6.dp))
    }
    Spacer(Modifier.height(10.dp))
    when (tool) {
        "film" -> {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Catalog.films.forEach { f ->
                    val on = f.stock == look.film
                    val c = Color(f.accent)
                    // the loaded roll lifts out of the row; its canister cap fills with the roll's colour
                    val lift by animateFloatAsState(if (on) 1f else 0f, Motion.settle, label = "lift")
                    val cardBg by animateColorAsState(if (on) Color(0xFF24100D) else Color.Transparent, tween(250), label = "cardBg")
                    val ring by animateColorAsState(if (on) c else Pal.line, tween(250), label = "ring")
                    Column(Modifier.padding(end = 10.dp, top = 6.dp).width(92.dp)
                        .graphicsLayer { translationY = -5.dp.toPx() * lift; scaleX = 1f + 0.04f * lift; scaleY = 1f + 0.04f * lift }
                        .springClick(0.93f) { set("film", look.copy(film = f.stock)) }
                        .clip(RoundedCornerShape(10.dp)).background(cardBg)
                        .border(1.dp, ring, RoundedCornerShape(10.dp)).padding(8.dp)) {
                        Box(Modifier.size(30.dp).graphicsLayer { rotationZ = 360f * lift }.clip(CircleShape)
                            .background(c.copy(alpha = 0.22f * lift.coerceIn(0f, 1f))).border(2.dp, c, CircleShape), contentAlignment = Alignment.Center) {
                            Text(f.iso, color = c, fontFamily = Fonts.mono, fontSize = 8.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(f.name, color = Pal.text, fontSize = 12.sp, maxLines = 1)
                        Text(filmKind(f), color = Pal.dim, fontSize = 10.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Crossfade(filmNote(look.film), animationSpec = tween(250), label = "note") { n ->
                Text(n, color = Pal.dim, fontSize = 12.sp)
            }
        }
        "paper" -> {
            if (positive) Text(stringResource(R.string.positive_paper_message), color = Pal.dim, fontSize = 12.sp)
            else {
                FlowRow { Catalog.papers.forEach { p -> Chip(paperLabel(p), p.id == look.paper, accent) { set("paper", look.copy(paper = p.id)) } } }
                Text(paperNote(look.paper), color = Pal.dim, fontSize = 12.sp)
                RulerSlider(stringResource(R.string.print_exposure), look.printEv, -2.0..2.0, 0.1, 0.0, accent, { signed(it, 1, " EV") }) { set("pexp", look.copy(printEv = it)) }
                RulerSlider(stringResource(R.string.glare), look.glare, 0.0..1.0, 0.01, 0.0, accent, { "${(it * 100).toInt()}%" }) { set("glare", look.copy(glare = it)) }
            }
        }
        "exp" -> {
            RulerSlider(stringResource(R.string.film_exposure), look.filmEv, -3.0..3.0, 0.1, 0.0, accent, { signed(it, 1, " EV") }) { set("ev", look.copy(filmEv = it)) }
            Text(stringResource(R.string.development), color = Pal.text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
            FlowRow {
                listOf(
                    -1 to stringResource(R.string.pull_minus_one),
                    0 to stringResource(R.string.standard_development),
                    1 to stringResource(R.string.push_plus_one),
                    2 to stringResource(R.string.push_plus_two),
                ).forEach { (v, l) ->
                    Chip(l, look.push == v, accent) { set("push", look.copy(push = v)) }
                }
            }
            Text(stringResource(if (look.film == "kodak_portra_800" && look.push > 0) R.string.portra_push_note else R.string.push_note),
                color = Pal.dim, fontSize = 12.sp)
        }
        "hal" -> {
            RulerSlider(stringResource(R.string.strength), look.halation, 0.0..4.0, 0.1, 1.0, accent, { "×%.1f".format(it) }) { set("hal", look.copy(halation = it)) }
            RulerSlider(stringResource(R.string.range), look.halationSize, 0.3..3.0, 0.1, 1.0, accent, { "×%.1f".format(it) }) { set("halSize", look.copy(halationSize = it)) }
            Text(stringResource(R.string.halation_hint), color = Pal.dim, fontSize = 12.sp)
        }
        "grain" -> {
            RulerSlider(stringResource(R.string.strength), look.grain, 0.0..3.0, 0.1, 1.0, accent, { "×%.1f".format(it) }) { set("grain", look.copy(grain = it)) }
            RulerSlider(stringResource(R.string.grain_size), look.grainSize, 0.5..2.5, 0.1, 1.0, accent, { "×%.1f".format(it) }) { set("gran", look.copy(grainSize = it)) }
            Text(stringResource(R.string.current_format, formatLabel(Catalog.format(look.format)), formatNote(look.format)) +
                if (isVideo) stringResource(R.string.video_grain_note) else "", color = Pal.dim, fontSize = 12.sp)
        }
        "diff" -> {
            FlowRow { Catalog.diffusions.forEach { d -> Chip(diffusionLabel(d), d.id == look.diffusion, accent) { set("diff", look.copy(diffusion = d.id)) } } }
            Text(diffusionNote(look.diffusion), color = Pal.dim, fontSize = 12.sp)
            // the strength ruler unfolds once a filter is screwed on
            androidx.compose.animation.AnimatedVisibility(look.diffusion != "off",
                enter = expandVertically(Motion.glideSize) + fadeIn(tween(200, delayMillis = 80)),
                exit = shrinkVertically(Motion.glideSize) + fadeOut(tween(100))) {
                RulerSlider(stringResource(R.string.strength), look.diffusionAmount, 0.0..1.0, 0.01, 0.5, accent, { "${(it * 100).toInt()}%" }) {
                    set("diffAmt", look.copy(diffusionAmount = it))
                }
            }
        }
        "color" -> {
            if (positive) Text(stringResource(R.string.positive_filter_message), color = Pal.dim, fontSize = 12.sp)
            RulerSlider(stringResource(R.string.yellow_filter), look.yFilter, -50.0..50.0, 1.0, 0.0, accent, { signed(it, 0, " CC") },
                minus = Pal.yellowFilter, plus = Pal.blueShift) { set("y", look.copy(yFilter = it)) }
            RulerSlider(stringResource(R.string.magenta_filter), look.mFilter, -50.0..50.0, 1.0, 0.0, accent, { signed(it, 0, " CC") },
                minus = Pal.magentaFilter, plus = Pal.greenShift) { set("m", look.copy(mFilter = it)) }
            Text(stringResource(R.string.color_filter_hint), color = Pal.dim, fontSize = 12.sp)
        }
        "fmt" -> {
            FlowRow { Catalog.formats.forEach { f -> Chip(formatLabel(f), f.id == look.format, accent) { set("fmt", look.copy(format = f.id)) } } }
            Text(stringResource(R.string.border), color = Pal.text, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
            FlowRow { Catalog.borders.forEach { b -> Chip(borderLabel(b), b.id == look.border, accent) { set("border", look.copy(border = b.id)) } } }
            Text(borderNote(look.border), color = Pal.dim, fontSize = 12.sp)
            ToggleRow(stringResource(R.string.date_back), look.dateStamp, accent) { set("date", look.copy(dateStamp = !look.dateStamp)) }
            Text(stringResource(R.string.display_no_export), color = Pal.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
            val gray18 by state.gray18.collectAsState()
            val reveal by state.reveal.collectAsState()
            ToggleRow(stringResource(R.string.gray_18_background), gray18, accent) { state.setGray18(!gray18) }
            ToggleRow(stringResource(R.string.development_animation), reveal, accent) { state.setReveal(!reveal) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRow(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow { content() }
}
