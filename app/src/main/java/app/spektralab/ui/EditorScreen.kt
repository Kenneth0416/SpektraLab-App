package app.spektralab.ui

import android.net.Uri
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.spektralab.data.LibraryItem
import app.spektralab.data.Media
import app.spektralab.engine.Catalog
import app.spektralab.engine.Look
import app.spektralab.engine.LookMapper
import app.spektralab.render.FilmPreviewView
import app.spektralab.render.Framing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.util.Locale

private data class ToolDef(val id: String, val label: String, val keys: (Look) -> Look)

private val TOOLS = listOf(
    ToolDef("film", "胶片") { it.copy(film = Look.DEFAULT.film) },
    ToolDef("paper", "相纸") { it.copy(paper = "auto", printEv = 0.0, glare = 0.0) },
    ToolDef("exp", "曝光") { it.copy(filmEv = 0.0, push = 0) },
    ToolDef("hal", "光晕") { it.copy(halation = 1.0, halationSize = 1.0) },
    ToolDef("grain", "颗粒") { it.copy(grain = 1.0, grainSize = 1.0) },
    ToolDef("diff", "柔焦") { it.copy(diffusion = "off", diffusionAmount = 0.5) },
    ToolDef("color", "滤色") { it.copy(yFilter = 0.0, mFilter = 0.0) },
    ToolDef("fmt", "画幅") { it.copy(format = "135", filmEdge = false, dateStamp = false) },
)

fun summary(look: Look): String {
    val f = Catalog.film(look.film).name
    val p = when {
        Catalog.film(look.film).kind == "彩色反转片" -> "直接扫描"
        look.paper == "auto" -> "自动相纸"
        else -> Catalog.paper(look.paper).label
    }
    return "$f × $p"
}

@Composable
fun EditorScreen(state: AppState, id: Long) {
    val items by state.library.items.collectAsState()
    val item = items.firstOrNull { it.id == id } ?: run { state.back(); return }
    val look = item.look
    val accent = Color(Catalog.film(look.film).accent)
    val history by state.history.collectAsState()
    val redo by state.redo.collectAsState()
    var tool by rememberSaveable { mutableStateOf("film") }
    var playing by remember { mutableStateOf(false) }
    var hold by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val preview = remember { FilmPreviewView(context) }
    DisposableEffect(Unit) { onDispose { preview.release() } }
    val engine = state.app.engine

    // source
    LaunchedEffect(item.id) {
        playing = false
        if (item.isVideo) preview.setVideo(Uri.parse(item.uri), item.rotation, item.width, item.height, item.autoEv)
        else {
            val bmp = withContext(Dispatchers.IO) { runCatching { Media.decodeImage(context, Uri.parse(item.uri), previewDecodeEdge(context)) }.getOrNull() }
            if (bmp != null) preview.setPhoto(bmp, item.autoEv, item.id.toInt()) else state.say("无法读取原片")
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 灯箱", color = Pal.text, fontSize = 15.sp, modifier = Modifier.clickable { state.back() }.padding(6.dp))
            Spacer(Modifier.weight(1f))
            Text("↶", color = if (history.isNotEmpty()) Pal.text else Pal.off, fontSize = 22.sp,
                modifier = Modifier.clickable { state.undo() }.padding(horizontal = 10.dp))
            Text("↷", color = if (redo.isNotEmpty()) Pal.text else Pal.off, fontSize = 22.sp,
                modifier = Modifier.clickable { state.redoStep() }.padding(horizontal = 10.dp))
            Spacer(Modifier.width(6.dp))
            Text("配方", color = Pal.text, fontSize = 15.sp, modifier = Modifier.clickable { state.openRecipes() }.padding(8.dp))
            Text("导出", color = Pal.dink, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(accent)
                    .clickable { state.openExport(listOf(item.id)) }.padding(horizontal = 14.dp, vertical = 6.dp))
        }
        // 1. preview
        Box(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            val framing = remember(item.width, item.height, look.format, look.filmEdge) {
                Framing.compute(item.width.coerceAtLeast(1), item.height.coerceAtLeast(1), look, 2048)
            }
            val aspect = framing.outWidth.toFloat() / framing.outHeight
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // fit the frame into whatever the pane gives us (portrait or landscape pane)
                val boxAspect = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else 1f
                Box(Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = aspect < boxAspect)
                    .pointerInput(Unit) { detectTapGestures(onPress = { hold = true; tryAwaitRelease(); hold = false }) }) {
                    AndroidView({ preview }, Modifier.fillMaxSize())
                    CropCorners(Modifier.fillMaxSize())
                    Text(if (hold) "原图 ORIGINAL" else summary(look).uppercase(Locale.ROOT), fontFamily = Fonts.mono, fontSize = 9.sp,
                        color = Pal.text, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
                            .background(Color(0x99140807)).padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            Safelight(Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        // 2. actions
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (item.isVideo) {
                Text(if (playing) "暂停" else "播放 " + mmss(item.durationMs / 1000), color = Pal.dink, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Pal.text)
                        .clickable { playing = !playing; preview.play(playing) }.padding(horizontal = 12.dp, vertical = 5.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text("按住画面看原图", color = Pal.dim, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("复制设置", color = Pal.text, fontSize = 13.sp, modifier = Modifier.clickable { state.copyLook() }.padding(8.dp))
            val clip by state.clip.collectAsState()
            Text("粘贴设置", color = if (clip != null) Pal.text else Pal.off, fontSize = 13.sp,
                modifier = Modifier.clickable { state.pasteLook() }.padding(8.dp))
        }
        // 3. tools: tabs + panel
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                TOOLS.forEach { t ->
                    val modified = t.keys(look) != look
                    Column(Modifier.clickable { tool = t.id }.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(5.dp).clip(CircleShape).background(if (modified) accent else Color.Transparent))
                        Text(t.label, color = if (t.id == tool) Pal.text else Pal.dim, fontSize = 14.sp)
                        Box(Modifier.width(22.dp).height(2.dp).background(if (t.id == tool) accent else Color.Transparent))
                    }
                }
            }
            Column(Modifier.fillMaxWidth().weight(1f).background(Pal.darkPanel)
                .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp)) {
                ToolPanel(state, tool, item, look, accent, engine.film(LookMapper.filmStock(look)).isPositive)
            }
        }
    }
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

@Composable
private fun CropCorners(modifier: Modifier) {
    Canvas(modifier) {
        val l = 16.dp.toPx(); val w = 2.dp.toPx(); val c = Pal.safelight.copy(alpha = 0.9f)
        listOf(Offset(0f, 0f) to (1 to 1), Offset(size.width, 0f) to (-1 to 1),
            Offset(0f, size.height) to (1 to -1), Offset(size.width, size.height) to (-1 to -1)).forEach { (o, d) ->
            drawLine(c, o, Offset(o.x + d.first * l, o.y), w)
            drawLine(c, o, Offset(o.x, o.y + d.second * l), w)
        }
    }
}

/** Darkroom safelight: a breathing red dot. */
@Composable
private fun Safelight(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "safelight")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "a")
    Box(modifier.size(8.dp).alpha(a).clip(CircleShape).background(Pal.safelight))
}

@Composable
private fun ToolPanel(state: AppState, tool: String, item: LibraryItem, look: Look, accent: Color, positive: Boolean) {
    val (title, desc) = when (tool) {
        "film" -> "胶片 · ${Catalog.film(look.film).name}" to "决定色彩与影调的底子。"
        "paper" -> "相纸 · ${if (positive) "直接扫描" else Catalog.paper(look.paper).label}" to "同一卷底片，印在不同相纸上是不同的结果。"
        "exp" -> "曝光与冲洗" to "胶片曝光改变的是色彩和颗粒，亮度由印相自动补回。"
        "hal" -> "光晕" to "高光周围的红色溢光。1.0 是这款胶片的原生强度。"
        "grain" -> "颗粒" to "1.0 是这款胶片的原生颗粒，随画幅物理缩放。"
        "diff" -> "柔焦镜" to "装在镜头前的柔焦滤镜，让高光溢开。"
        "color" -> "放大机滤色" to "用黄 / 品红滤色校色，或故意做偏色。"
        else -> "画幅与片边 · ${Catalog.format(look.format).label}" to "画幅决定裁切，也决定颗粒与光晕的相对大小。"
    }
    fun set(key: String, l: Look) = state.setLook(key, l)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Pal.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(desc, color = Pal.dim, fontSize = 12.sp)
        }
        Text("重置", color = Pal.dim, fontSize = 13.sp, modifier = Modifier.clickable {
            TOOLS.firstOrNull { it.id == tool }?.let { state.applyLook(it.keys(look)) }
        }.padding(6.dp))
    }
    Spacer(Modifier.height(10.dp))
    when (tool) {
        "film" -> {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Catalog.films.forEach { f ->
                    val on = f.stock == look.film
                    val c = Color(f.accent)
                    Column(Modifier.padding(end = 10.dp).width(92.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (on) Color(0xFF24100D) else Color.Transparent)
                        .border(1.dp, if (on) c else Pal.line, RoundedCornerShape(10.dp))
                        .clickable { set("film", look.copy(film = f.stock)) }.padding(8.dp)) {
                        Box(Modifier.size(30.dp).clip(CircleShape).border(2.dp, c, CircleShape), contentAlignment = Alignment.Center) {
                            Text(f.iso, color = c, fontFamily = Fonts.mono, fontSize = 8.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(f.name, color = Pal.text, fontSize = 12.sp, maxLines = 1)
                        Text(f.kind, color = Pal.dim, fontSize = 10.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(Catalog.film(look.film).note, color = Pal.dim, fontSize = 12.sp)
        }
        "paper" -> {
            if (positive) Text("反转片冲出来就是正像，直接扫描，不再印相。", color = Pal.dim, fontSize = 12.sp)
            else {
                FlowRow { Catalog.papers.forEach { p -> Chip(p.label, p.id == look.paper, accent) { set("paper", look.copy(paper = p.id)) } } }
                Text(Catalog.paper(look.paper).note, color = Pal.dim, fontSize = 12.sp)
                RulerSlider("印相曝光", look.printEv, -2.0..2.0, 0.1, 0.0, accent, { signed(it, 1, " EV") }) { set("pexp", look.copy(printEv = it)) }
                RulerSlider("眩光", look.glare, 0.0..1.0, 0.01, 0.0, accent, { "${(it * 100).toInt()}%" }) { set("glare", look.copy(glare = it)) }
            }
        }
        "exp" -> {
            RulerSlider("胶片曝光", look.filmEv, -3.0..3.0, 0.1, 0.0, accent, { signed(it, 1, " EV") }) { set("ev", look.copy(filmEv = it)) }
            Text("冲洗", color = Pal.text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
            FlowRow {
                listOf(-1 to "减冲 −1", 0 to "标准", 1 to "迫冲 +1", 2 to "迫冲 +2").forEach { (v, l) ->
                    Chip(l, look.push == v, accent) { set("push", look.copy(push = v)) }
                }
            }
            Text(if (look.film == "kodak_portra_800" && look.push > 0) "Portra 800 使用 spektrafilm 实测的迫冲曲线。"
                else "每迫冲一档少曝光一档、反差约增加 15%，颗粒更明显。", color = Pal.dim, fontSize = 12.sp)
        }
        "hal" -> {
            RulerSlider("强度", look.halation, 0.0..4.0, 0.1, 1.0, accent, { "×%.1f".format(it) }) { set("hal", look.copy(halation = it)) }
            RulerSlider("范围", look.halationSize, 0.3..3.0, 0.1, 1.0, accent, { "×%.1f".format(it) }) { set("halSize", look.copy(halationSize = it)) }
            Text("夜景灯光、逆光发丝最明显。", color = Pal.dim, fontSize = 12.sp)
        }
        "grain" -> {
            RulerSlider("强度", look.grain, 0.0..3.0, 0.1, 1.0, accent, { "×%.1f".format(it) }) { set("grain", look.copy(grain = it)) }
            RulerSlider("颗粒粗细", look.grainSize, 0.5..2.5, 0.1, 1.0, accent, { "×%.1f".format(it) }) { set("gran", look.copy(grainSize = it)) }
            val fmt = Catalog.format(look.format)
            Text("当前画幅 ${fmt.label}：${fmt.note}" + if (item.isVideo) " 视频逐帧重新生成颗粒。" else "",
                color = Pal.dim, fontSize = 12.sp)
        }
        "diff" -> {
            FlowRow { Catalog.diffusions.forEach { d -> Chip(d.label, d.id == look.diffusion, accent) { set("diff", look.copy(diffusion = d.id)) } } }
            Text(Catalog.diffusion(look.diffusion).note, color = Pal.dim, fontSize = 12.sp)
            if (look.diffusion != "off") {
                RulerSlider("强度", look.diffusionAmount, 0.0..1.0, 0.01, 0.5, accent, { "${(it * 100).toInt()}%" }) {
                    set("diffAmt", look.copy(diffusionAmount = it))
                }
            }
        }
        "color" -> {
            if (positive) Text("反转片不经过放大机，滤色不起作用。", color = Pal.dim, fontSize = 12.sp)
            RulerSlider("黄滤 Y", look.yFilter, -50.0..50.0, 1.0, 0.0, accent, { signed(it, 0, " CC") },
                minus = Pal.yellowFilter, plus = Pal.blueShift) { set("y", look.copy(yFilter = it)) }
            RulerSlider("品红滤 M", look.mFilter, -50.0..50.0, 1.0, 0.0, accent, { signed(it, 0, " CC") },
                minus = Pal.magentaFilter, plus = Pal.greenShift) { set("m", look.copy(mFilter = it)) }
            Text("刻度上的颜色就是画面会偏去的方向。单位 CC，和暗房一致：加黄滤，画面减黄偏蓝。", color = Pal.dim, fontSize = 12.sp)
        }
        "fmt" -> {
            FlowRow { Catalog.formats.forEach { f -> Chip(f.label, f.id == look.format, accent) { set("fmt", look.copy(format = f.id)) } } }
            ToggleRow("片边（胶片名与格号）", look.filmEdge, accent) { set("edge", look.copy(filmEdge = !look.filmEdge)) }
            ToggleRow("日期后背", look.dateStamp, accent) { set("date", look.copy(dateStamp = !look.dateStamp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRow(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow { content() }
}
