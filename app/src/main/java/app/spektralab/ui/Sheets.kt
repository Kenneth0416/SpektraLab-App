package app.spektralab.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import app.spektralab.data.Library
import app.spektralab.engine.Catalog
import app.spektralab.export.PhotoFormat

@Composable
fun Sheets(state: AppState) {
    val sheet by state.sheet.collectAsState()
    val dark = state.editId.collectAsState().value != null
    Box(Modifier.fillMaxSize()) {
        if (sheet != Sheet.NONE) {
            Box(Modifier.fillMaxSize().background(Color(0x88000000))
                .clickable(remember { MutableInteractionSource() }, null) {
                    if (sheet != Sheet.PROGRESS) state.sheet.value = Sheet.NONE
                })
        }
        AnimatedVisibility(sheet != Sheet.NONE, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            val bg = if (dark || sheet == Sheet.EXPORT || sheet == Sheet.PROGRESS) Pal.darkPanel else Pal.paper
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(bg)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .navigationBarsPadding().padding(20.dp)) {
                when (sheet) {
                    Sheet.IMPORT -> ImportSheet(state)
                    Sheet.RECIPES -> RecipeSheet(state, dark)
                    Sheet.EXPORT -> ExportSheet(state)
                    Sheet.PROGRESS -> ProgressSheet(state)
                    Sheet.NONE -> {}
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
        SourceCard("相册", "照片和视频，可多选", Modifier.weight(1f)) {
            photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }
        SourceCard("文件", "RAW / DNG / JPEG / 视频", Modifier.weight(1f)) {
            files.launch(arrayOf("image/*", "video/*"))
        }
    }
    Spacer(Modifier.height(12.dp))
    Text("只选一项会直接进暗房。RAW 文件从「文件」导入，由系统解码（取决于设备支持）。原片只读不改。", color = Pal.grey, fontSize = 12.sp)
}

@Composable
private fun SourceCard(title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(Color.White).border(1.dp, Pal.ink, RoundedCornerShape(10.dp))
        .clickable(onClick = onClick).padding(16.dp)) {
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
            Row(Modifier.padding(vertical = 5.dp).rotate(rot).fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Pal.card)
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
            .border(1.dp, Pal.text, RoundedCornerShape(22.dp)).clickable { go(true) }.padding(12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text("存到相册", color = Pal.dink, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
            .clip(RoundedCornerShape(22.dp)).background(acc).clickable { go(false) }.padding(12.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun Label(t: String) = Text(t, color = Pal.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))

@Composable
private fun ProgressSheet(state: AppState) {
    val st = state.exports.state.collectAsState().value ?: return
    Text("正在冲印", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Pal.text)
    Text("${minOf(st.total, st.done + 1)} / ${st.total}", fontFamily = Fonts.mono, color = Pal.text, fontSize = 14.sp,
        modifier = Modifier.padding(vertical = 8.dp))
    val pct = ((st.done + st.current) / st.total.coerceAtLeast(1)).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Pal.line)) {
        Box(Modifier.fillMaxWidth(pct).fillMaxHeight().background(Pal.safelight))
    }
    Spacer(Modifier.height(10.dp))
    Text("每一项都要完整算一遍，全尺寸需要几秒，视频需要更久。可以先回去继续修图。", color = Pal.dim, fontSize = 12.sp)
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("停止", color = Pal.dim, fontSize = 15.sp, modifier = Modifier.clickable { state.exports.cancel() }.padding(12.dp))
        Spacer(Modifier.weight(1f))
        Text("后台继续", color = Pal.dink, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(22.dp)).background(Pal.text)
                .clickable { state.sheet.value = Sheet.NONE }.padding(horizontal = 18.dp, vertical = 12.dp))
    }
}

@Composable
private fun ProgressPill(state: AppState, modifier: Modifier) {
    val st = state.exports.state.collectAsState().value
    val sheet by state.sheet.collectAsState()
    if (st == null || !st.running || sheet == Sheet.PROGRESS) return
    Row(modifier.clip(RoundedCornerShape(16.dp)).background(Pal.ink).clickable { state.sheet.value = Sheet.PROGRESS }
        .padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(Pal.safelight))
        Spacer(Modifier.width(8.dp))
        Text("冲印中 ${minOf(st.total, st.done + 1)} / ${st.total}", color = Pal.paper, fontSize = 13.sp, fontFamily = Fonts.mono)
    }
}

@Composable
private fun Hint(state: AppState, modifier: Modifier) {
    val hint by state.hint.collectAsState()
    AnimatedVisibility(hint.isNotEmpty(), modifier, enter = fadeIn(), exit = fadeOut()) {
        Text(hint, color = Pal.paper, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(18.dp))
            .background(Color(0xE61B1A17)).padding(horizontal = 16.dp, vertical = 9.dp))
    }
}
