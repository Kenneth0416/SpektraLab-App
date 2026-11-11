package app.spektralab.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import app.spektralab.data.Library
import app.spektralab.engine.Catalog
import app.spektralab.export.PhotoFormat

@Composable
fun Sheets(state: AppState) {
    val sheet by state.sheet.collectAsState()
    val dark = state.editId.collectAsState().value != null || state.cameraOpen.collectAsState().value
    val fold = LocalFold.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    var box by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    // tabletop: keep sheets on the lower half, below the hinge
    val maxSheet = fold.hinge?.takeIf { fold.posture == Posture.TABLETOP && box.height > 0f && it.bottom < box.bottom }
        ?.let { with(density) { (box.bottom - it.bottom).toDp() } } ?: androidx.compose.ui.unit.Dp.Infinity
    Box(Modifier.fillMaxSize().onGloballyPositioned { box = it.boundsInWindow() }) {
        // keep showing the last sheet's content while it slides away
        var lastSheet by remember { mutableStateOf(Sheet.IMPORT) }
        if (sheet != Sheet.NONE) lastSheet = sheet
        AnimatedVisibility(sheet != Sheet.NONE, enter = fadeIn(tween(220)), exit = fadeOut(tween(260))) {
            Box(Modifier.fillMaxSize().background(Color(0x88000000))
                .clickable(remember { MutableInteractionSource() }, null) {
                    if (sheet != Sheet.PROGRESS) state.sheet.value = Sheet.NONE
                })
        }
        AnimatedVisibility(sheet != Sheet.NONE, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(Motion.settleOffset) { it } + fadeIn(tween(120)),
            exit = slideOutVertically(tween(220, easing = FastOutLinearInEasing)) { it } + fadeOut(tween(220))) {
            val shownSheet = lastSheet
            val bg by animateColorAsState(
                if (dark || shownSheet == Sheet.EXPORT || shownSheet == Sheet.PROGRESS) Pal.darkPanel else Pal.paper, tween(250), label = "sheetBg")
            val baseModifier = Modifier.widthIn(max = SheetMaxWidth).heightIn(max = maxSheet).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(bg)
                .pointerInput(Unit) { detectTapGestures { } }
            val sheetModifier = if (shownSheet == Sheet.ABOUT) {
                baseModifier.navigationBarsPadding().padding(20.dp).animateContentSize(Motion.glideSize)
            } else {
                baseModifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp).animateContentSize(Motion.glideSize)
            }
            Column(sheetModifier) {
                // export options hand over to the progress view in place
                AnimatedContent(shownSheet, label = "sheet", transitionSpec = {
                    (fadeIn(tween(220, delayMillis = 90)) + slideInVertically(Motion.glideOffset) { it / 6 }) togetherWith fadeOut(tween(90))
                }) { sh ->
                    Column {
                        when (sh) {
                            Sheet.IMPORT -> ImportSheet(state)
                            Sheet.RECIPES -> RecipeSheet(state, dark)
                            Sheet.EXPORT -> ExportSheet(state)
                            Sheet.PROGRESS -> ProgressSheet(state)
                            Sheet.ABOUT -> AboutSheet(state, dark)
                            Sheet.NONE -> {}
                        }
                    }
                }
            }
        }
        ProgressPill(state, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp))
        Hint(state, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp))
    }
}

@Composable
private fun ImportSheet(state: AppState) {
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { state.importUris(it) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { state.importUris(it) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("导入", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Pal.ink, modifier = Modifier.weight(1f))
        Text("取消", color = Pal.grey, modifier = Modifier.clickable { state.sheet.value = Sheet.NONE }.padding(6.dp))
    }
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // the two slide mounts are dealt out one after the other
        SourceCard("相册", "照片和视频，可多选", Modifier.weight(1f).dealIn(1, fromY = 50f, tilt = -4f)) {
            photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }
        SourceCard("文件", "RAW / DNG / JPEG / 视频", Modifier.weight(1f).dealIn(2, fromY = 50f, tilt = 4f)) {
            files.launch(arrayOf("image/*", "video/*"))
        }
    }
    Spacer(Modifier.height(12.dp))
    Text("只选一项会直接进暗房。RAW 文件从「文件」导入，由系统解码（取决于设备支持）。原片只读不改。", color = Pal.grey, fontSize = 12.sp)
}

@Composable
private fun SourceCard(title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(Color.White).border(1.dp, Pal.ink, RoundedCornerShape(10.dp))
        .springClick(0.95f, onClick = onClick).padding(16.dp)) {
        Text(title, fontSize = 18.sp, color = Pal.ink, fontWeight = FontWeight.Bold)
        Text(sub, fontSize = 12.sp, color = Pal.grey)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecipeSheet(state: AppState, inEditor: Boolean) {
    val mine by state.library.recipes.collectAsState()
    val picked by state.picked.collectAsState()
    val fg = if (inEditor) Pal.text else Pal.ink
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (inEditor) "配方" else "配方 · 套用到 ${picked.size} 项", fontSize = 20.sp, fontWeight = FontWeight.Bold,
            color = fg, modifier = Modifier.weight(1f))
        Text("关闭", color = if (inEditor) Pal.dim else Pal.grey, modifier = Modifier.clickable { state.sheet.value = Sheet.NONE }.padding(6.dp))
    }
    Spacer(Modifier.height(12.dp))
    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
        (mine + Library.BUILTIN_RECIPES).forEachIndexed { i, r ->
            val rot = floatArrayOf(-0.6f, 0.5f, -0.3f, 0.7f)[i % 4]
            Row(Modifier.padding(vertical = 5.dp).dealIn(i, key = r.name, fromX = if (i % 2 == 0) -90f else 90f, fromY = 24f, tilt = rot * 10f)
                .rotate(rot).fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Pal.card)
                .combinedClickable(onClick = { state.applyRecipe(r) },
                    onLongClick = { if (!r.builtin) { state.library.removeRecipe(r.name); state.say("已删除：${r.name}") } })
                .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(Catalog.film(r.look.film).accent)))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(r.name, fontFamily = Fonts.hand, fontSize = 24.sp, color = Pal.ink)
                    Text(summary(r.look), fontSize = 12.sp, color = Pal.grey)
                }
            }
        }
    }
    if (inEditor) {
        Text("＋ 把当前设置写成一张配方卡", color = Pal.text, fontSize = 14.sp,
            modifier = Modifier.padding(top = 12.dp).fillMaxWidth().border(1.dp, Pal.off, RoundedCornerShape(8.dp))
                .clickable { state.saveRecipe() }.padding(12.dp))
    }
}

@Composable
private fun ExportSheet(state: AppState) {
    val targets = state.exportTargets.collectAsState().value.mapNotNull { state.library.item(it) }
    val o by state.exportOptions.collectAsState()
    val notifier = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val acc = state.editId.collectAsState().value?.let { state.library.item(it) }?.let { Color(Catalog.film(it.look.film).accent) } ?: Pal.red
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("导出 ${targets.size} 项", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Pal.text, modifier = Modifier.weight(1f))
        Text("取消", color = Pal.dim, modifier = Modifier.clickable { state.sheet.value = Sheet.NONE }.padding(6.dp))
    }
    if (targets.any { !it.isVideo }) {
        Label("格式")
        Row { PhotoFormat.entries.forEach { f -> Chip(f.label, o.photoFormat == f, Pal.paper) { state.exportOptions.value = o.copy(photoFormat = f) } } }
        Label("尺寸")
        Row {
            listOf(2048 to "长边 2048", 3400 to "长边 3400", 0 to "全尺寸").forEach { (v, l) ->
                Chip(l, o.photoLongEdge == v, Pal.paper) { state.exportOptions.value = o.copy(photoLongEdge = v) }
            }
        }
    }
    if (targets.any { it.isVideo }) {
        Label("视频")
        Row {
            listOf(1920 to "1080p", 3840 to "4K").forEach { (v, l) ->
                Chip(l, o.videoLongEdge == v, Pal.paper) { state.exportOptions.value = o.copy(videoLongEdge = v) }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text("导出的是一份新文件，原片不动。颗粒和光晕按输出尺寸重新计算。", color = Pal.dim, fontSize = 12.sp)
    Spacer(Modifier.height(14.dp))
    fun go(share: Boolean) {
        if (Build.VERSION.SDK_INT >= 33) notifier.launch(Manifest.permission.POST_NOTIFICATIONS)
        state.startExport(share)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("分享…", color = Pal.text, fontSize = 15.sp, modifier = Modifier.weight(1f).clip(RoundedCornerShape(22.dp))
            .border(1.dp, Pal.text, RoundedCornerShape(22.dp)).springClick { go(true) }.padding(12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text("存到相册", color = Pal.dink, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
            .clip(RoundedCornerShape(22.dp)).background(acc).springClick { go(false) }.padding(12.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun Label(t: String) = Text(t, color = Pal.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))

@Composable
private fun ProgressSheet(state: AppState) {
    val st = state.exports.state.collectAsState().value ?: return
    Text("正在冲印" + busyDots(), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Pal.text)
    Row(Modifier.padding(vertical = 8.dp)) {
        RollingNumber(minOf(st.total, st.done + 1), Fonts.mono, 14.sp, Pal.text)
        Text(" / ${st.total}", fontFamily = Fonts.mono, color = Pal.text, fontSize = 14.sp)
    }
    val target = ((st.done + st.current) / st.total.coerceAtLeast(1)).coerceIn(0f, 1f)
    val pct by animateFloatAsState(target, Motion.glide, label = "progress")
    // a strip of film being pulled through: sprocket holes run along the filled part
    val run by rememberInfiniteTransition(label = "film").animateFloat(0f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing)), label = "run")
    Canvas(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(3.dp)).background(Pal.line)) {
        val w = size.width * pct
        drawRect(Pal.safelight, size = Size(w, size.height))
        val pitch = 12.dp.toPx()
        var x = -pitch + run * pitch
        while (x < w) {
            if (x + 5.dp.toPx() <= w) drawRoundRect(Pal.dink.copy(alpha = 0.55f), Offset(x, size.height * 0.3f),
                Size(5.dp.toPx(), size.height * 0.4f), CornerRadius(1.dp.toPx()))
            x += pitch
        }
    }
    Spacer(Modifier.height(10.dp))
    Text("每一项都要完整算一遍，全尺寸需要几秒，视频需要更久。可以先回去继续修图。", color = Pal.dim, fontSize = 12.sp)
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("停止", color = Pal.dim, fontSize = 15.sp, modifier = Modifier.clickable { state.exports.cancel() }.padding(12.dp))
        Spacer(Modifier.weight(1f))
        Text("后台继续", color = Pal.dink, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(22.dp)).background(Pal.text)
                .springClick { state.sheet.value = Sheet.NONE }.padding(horizontal = 18.dp, vertical = 12.dp))
    }
}

@Composable
private fun ProgressPill(state: AppState, modifier: Modifier) {
    val st = state.exports.state.collectAsState().value
    val sheet by state.sheet.collectAsState()
    // drops down from the top edge while printing continues in the background
    var last by remember { mutableStateOf(st) }
    if (st != null) last = st
    AnimatedVisibility(st != null && st.running && sheet != Sheet.PROGRESS, modifier,
        enter = slideInVertically(Motion.settleOffset) { -it * 2 } + fadeIn(), exit = slideOutVertically(tween(200)) { -it * 2 } + fadeOut()) {
        val s = last ?: return@AnimatedVisibility
        val pulse by rememberInfiniteTransition(label = "pill").animateFloat(0.35f, 1f,
            infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
        Row(Modifier.clip(RoundedCornerShape(16.dp)).background(Pal.ink).springClick { state.sheet.value = Sheet.PROGRESS }
            .padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).graphicsLayer { alpha = pulse; scaleX = 0.7f + 0.3f * pulse; scaleY = 0.7f + 0.3f * pulse }
                .clip(CircleShape).background(Pal.safelight))
            Spacer(Modifier.width(8.dp))
            Text("冲印中 ", color = Pal.paper, fontSize = 13.sp, fontFamily = Fonts.mono)
            RollingNumber(minOf(s.total, s.done + 1), Fonts.mono, 13.sp, Pal.paper)
            Text(" / ${s.total}", color = Pal.paper, fontSize = 13.sp, fontFamily = Fonts.mono)
        }
    }
}

@Composable
private fun Hint(state: AppState, modifier: Modifier) {
    val hint by state.hint.collectAsState()
    // keep the text while the note fades out
    var lastHint by remember { mutableStateOf("") }
    if (hint.isNotEmpty()) lastHint = hint
    AnimatedVisibility(hint.isNotEmpty(), modifier,
        enter = slideInVertically(Motion.settleOffset) { it } + scaleIn(Motion.settle, initialScale = 0.85f) + fadeIn(tween(120)),
        exit = fadeOut(tween(260)) + slideOutVertically(tween(260)) { it / 2 }) {
        Text(lastHint, color = Pal.paper, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(18.dp))
            .background(Color(0xE61B1A17)).padding(horizontal = 16.dp, vertical = 9.dp))
    }
}
