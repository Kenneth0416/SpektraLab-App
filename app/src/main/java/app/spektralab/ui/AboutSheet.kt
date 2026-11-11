package app.spektralab.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spektralab.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BundledLicenseDoc(
    val id: String,
    val title: String,
    val subtitle: String,
    val assetPath: String,
)

object BundledLicenses {
    val docs = listOf(
        BundledLicenseDoc(
            id = "gpl3",
            title = "GNU GPL v3.0 完整许可原文",
            subtitle = "代码许可（app / engine / GLSL）· licenses/GPL-3.0.txt",
            assetPath = "licenses/GPL-3.0.txt",
        ),
        BundledLicenseDoc(
            id = "ccbysa4",
            title = "CC BY-SA 4.0 与 spektrafilm 数据前序",
            subtitle = "胶片/相纸档案与 LUT 许可 · spektra/SPEKTRAFILM_LICENSE.txt",
            assetPath = "spektra/SPEKTRAFILM_LICENSE.txt",
        ),
        BundledLicenseDoc(
            id = "changelog",
            title = "档案与 LUT 修改记录（CHANGELOG）",
            subtitle = "烘焙与归属记录 · spektra/CHANGELOG.txt",
            assetPath = "spektra/CHANGELOG.txt",
        ),
        BundledLicenseDoc(
            id = "notice",
            title = "修改声明、源码与第三方依赖摘要",
            subtitle = "NOTICE · licenses/NOTICE.txt",
            assetPath = "licenses/NOTICE.txt",
        ),
        BundledLicenseDoc(
            id = "ofl_longcang",
            title = "SIL OFL 1.1 — Long Cang 字体",
            subtitle = "The LongCang Project Authors · licenses/OFL-LongCang.txt",
            assetPath = "licenses/OFL-LongCang.txt",
        ),
        BundledLicenseDoc(
            id = "ofl_martian",
            title = "SIL OFL 1.1 — Martian Mono 字体",
            subtitle = "The Martian Mono Project Authors · licenses/OFL-MartianMono.txt",
            assetPath = "licenses/OFL-MartianMono.txt",
        ),
        BundledLicenseDoc(
            id = "apache2",
            title = "Apache License 2.0 完整原文",
            subtitle = "Kotlin / Jetpack / AndroidX 依赖 · licenses/APACHE-2.0.txt",
            assetPath = "licenses/APACHE-2.0.txt",
        ),
    )

    fun loadAsset(context: Context, assetPath: String): String =
        context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
}

@Composable
fun AboutSheet(state: AppState, dark: Boolean) {
    val context = LocalContext.current
    val brand = stringResource(R.string.app_name)
    var selectedDoc by remember { mutableStateOf<BundledLicenseDoc?>(null) }
    var docChunks by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = selectedDoc != null) { selectedDoc = null }

    LaunchedEffect(selectedDoc) {
        val doc = selectedDoc ?: return@LaunchedEffect
        loadError = null
        docChunks = emptyList()
        runCatching {
            withContext(Dispatchers.IO) { BundledLicenses.loadAsset(context, doc.assetPath) }
        }.onSuccess { text ->
            docChunks = chunkLicenseText(text)
        }.onFailure {
            loadError = "无法读取内置许可文件：${doc.assetPath}"
        }
    }

    val fg = if (dark) Pal.text else Pal.ink
    val muted = if (dark) Pal.dim else Pal.grey
    val cardBg = if (dark) Color(0xFF221310) else Color.White
    val borderCol = if (dark) Pal.off else Pal.ink.copy(alpha = 0.2f)

    Column(Modifier.fillMaxWidth().heightIn(max = 580.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (selectedDoc != null) {
                Text("‹ 返回", color = fg, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { selectedDoc = null }.padding(end = 10.dp, top = 4.dp, bottom = 4.dp))
            }
            Text(
                text = selectedDoc?.title ?: stringResource(R.string.about_title),
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = fg,
                modifier = Modifier.weight(1f),
            )
            Text("关闭", color = muted, fontSize = 15.sp,
                modifier = Modifier.clickable { state.sheet.value = Sheet.NONE }.padding(6.dp))
        }
        Spacer(Modifier.height(10.dp))

        val activeDoc = selectedDoc
        if (activeDoc != null) {
            Text(activeDoc.subtitle, fontFamily = Fonts.mono, fontSize = 10.sp, color = muted)
            Spacer(Modifier.height(8.dp))
            val err = loadError
            if (err != null) {
                Text(err, color = Pal.red, fontSize = 13.sp)
            } else {
                SelectionContainer {
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f, fill = false)
                            .clip(RoundedCornerShape(10.dp))
                            .background(cardBg)
                            .border(1.dp, borderCol, RoundedCornerShape(10.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(docChunks) { chunk ->
                            Text(chunk, fontFamily = Fonts.mono, fontSize = 10.sp, lineHeight = 15.sp, color = fg)
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f, fill = false).testTag("about_list"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    NoticeBlock(
                        title = brand,
                        body = "版本 0.1.0 · 应用包名 app.spektralab\n" +
                            "独立项目声明：$brand 是独立项目，不隶属于 spektrafilm 或 SpektraLab 项目，也未获得其赞助或背书。\n" +
                            "名称说明：$brand 是为区分上游项目而采用的展示名称，尚未进行商标注册（not trademark-cleared），本项目不主张也不声明任何商标权利。\n" +
                            "本应用包含基于 spektrafilm 的独立衍生移植（independent fork），保留 Andrea Volpato 与上游项目的来源归属。",
                        fg = fg, muted = muted, cardBg = cardBg, borderCol = borderCol,
                    )
                }
                item {
                    NoticeBlock(
                        title = "上游来源与实际仓库地址",
                        body = "上游胶片模拟项目：spektrafilm — Andrea Volpato\n" +
                            "https://github.com/andreavolpato/spektrafilm\n" +
                            "（引擎与烘焙数据源自 commit 3bb2c2d2801ff68b92019cf1dbcbb133d60832bc, v0.3.4）\n\n" +
                            "本应用源码仓库：\n" +
                            "https://github.com/Kenneth0416/SpektraLab-App",
                        fg = fg, muted = muted, cardBg = cardBg, borderCol = borderCol,
                    )
                }
                item {
                    NoticeBlock(
                        title = "代码与数据许可区分（GPLv3 vs CC BY-SA 4.0）",
                        body = "• 可执行代码（app/、engine/ Kotlin 移植、DNG 冲洗与 GLSL ES 3.00 着色器）：采用 GNU General Public License v3.0（GPLv3）。包含修改记录（2026-11-10 至 2026-11-11 移植与适配），按 GPLv3 条款“按原样”提供，不提供任何担保（NO WARRANTY），任何获许可方均可依 GPLv3 再分发。\n\n" +
                            "• 胶片/相纸档案与查找表数据（assets/spektra/*.spk、index.json）：源自 Andrea Volpato 的 spektrafilm 档案，采用 Creative Commons Attribution-ShareAlike 4.0 International（CC BY-SA 4.0）授权，完整保留上游 SPEKTRAFILM_LICENSE.txt 前序与全文，并附带 CHANGELOG.txt 记录 2026-11-10 烘焙与 2026-11-11 归属维护。\n\n" +
                            "• 导出成片：依据上游前序与 GPLv3 第 2 条，用户通过本应用拍摄或导出的成品照片与视频不受 copyleft 约束，也不强制添加任何输出水印。",
                        fg = fg, muted = muted, cardBg = cardBg, borderCol = borderCol,
                    )
                }
                item {
                    NoticeBlock(
                        title = "对应源码要求（GPLv3 Section 6）",
                        body = "仅提供通用仓库主页链接本身并不足以自动满足所有发行场景的 GPLv3 对应源码（Corresponding Source）义务。任何对外分发的 APK/二进制发行版，必须同步提供或确保可获取与该构建完全一致的精确源码版本（精确 Git 提交/标签、Gradle 构建配置、app/ 与 engine/ 源码以及 tools/bake/ 烘焙脚本与上游档案）。",
                        fg = fg, muted = muted, cardBg = cardBg, borderCol = borderCol,
                    )
                }
                item {
                    NoticeBlock(
                        title = "内置字体与第三方依赖声明（按构建配置核验）",
                        body = "• Long Cang（long_cang.ttf，子集）：Copyright 2018 The LongCang Project Authors (https://github.com/googlefonts/longcang) — SIL Open Font License 1.1\n" +
                            "• Martian Mono（martian_mono.ttf / SemiExpanded）：Copyright 2020/2021 The Martian Mono Project Authors / Evil Martians / Roman Shamin (https://github.com/evilmartians/mono) — SIL Open Font License 1.1\n" +
                            "• Kotlin Standard Library (2.1.21)、Kotlinx Coroutines (1.10.2)、Kotlinx Serialization Core (1.7.3)、JetBrains Annotations (23.0.0)、JSpecify (1.0.0)、AutoValue Annotations (1.6.3)、Guava ListenableFuture (1.0) — Apache License 2.0\n" +
                            "• Jetpack / AndroidX（core-ktx 1.16.0、activity-compose 1.10.1、lifecycle 2.9.1、compose-bom 2025.06.00、exifinterface 1.4.2、window 1.3.0、camera-* 1.5.3）— Apache License 2.0（其中 androidx.camera:camera-core:1.5.3 POM 另标明包含 BSD License 的 libyuv）\n" +
                            "• 胶片/相纸名称（如 Kodak、Portra、Vision3、Fujifilm、Velvia 等）仅用于说明上游档案模拟对象，商标归各自权利人所有。",
                        fg = fg, muted = muted, cardBg = cardBg, borderCol = borderCol,
                    )
                }
                item {
                    Text("本地内置完整许可文本（点击查看全文）", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = fg,
                        modifier = Modifier.padding(top = 4.dp))
                }
                items(BundledLicenses.docs, key = { it.id }) { doc ->
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(cardBg)
                            .border(1.dp, borderCol, RoundedCornerShape(10.dp))
                            .semantics { contentDescription = doc.title }
                            .clickable { selectedDoc = doc }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(doc.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = fg)
                        Spacer(Modifier.height(2.dp))
                        Text(doc.subtitle, fontFamily = Fonts.mono, fontSize = 10.sp, color = muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeBlock(title: String, body: String, fg: Color, muted: Color, cardBg: Color, borderCol: Color) {
    SelectionContainer {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(cardBg)
                .border(1.dp, borderCol, RoundedCornerShape(10.dp))
                .padding(12.dp),
        ) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = fg)
            Spacer(Modifier.height(6.dp))
            Text(body, fontSize = 12.sp, lineHeight = 18.sp, color = muted)
        }
    }
}

internal fun chunkLicenseText(text: String, linesPerChunk: Int = 36): List<String> {
    val lines = text.lines()
    if (lines.isEmpty()) return emptyList()
    return lines.chunked(linesPerChunk).map { it.joinToString("\n") }
}
