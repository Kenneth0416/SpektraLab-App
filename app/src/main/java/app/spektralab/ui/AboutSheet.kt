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
    @androidx.annotation.StringRes val titleRes: Int,
    @androidx.annotation.StringRes val subtitleRes: Int,
    val assetPath: String,
)

object BundledLicenses {
    val docs = listOf(
        BundledLicenseDoc("gpl3", R.string.license_gpl_title, R.string.license_gpl_subtitle, "licenses/GPL-3.0.txt"),
        BundledLicenseDoc("ccbysa4", R.string.license_cc_title, R.string.license_cc_subtitle, "spektra/SPEKTRAFILM_LICENSE.txt"),
        BundledLicenseDoc("changelog", R.string.license_changelog_title, R.string.license_changelog_subtitle, "spektra/CHANGELOG.txt"),
        BundledLicenseDoc("notice", R.string.license_notice_title, R.string.license_notice_subtitle, "licenses/NOTICE.txt"),
        BundledLicenseDoc("ofl_longcang", R.string.license_ofl_longcang_title, R.string.license_ofl_longcang_subtitle, "licenses/OFL-LongCang.txt"),
        BundledLicenseDoc("ofl_martian", R.string.license_ofl_martian_title, R.string.license_ofl_martian_subtitle, "licenses/OFL-MartianMono.txt"),
        BundledLicenseDoc("apache2", R.string.license_apache_title, R.string.license_apache_subtitle, "licenses/APACHE-2.0.txt"),
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
            loadError = context.getString(R.string.license_load_error, doc.assetPath)
        }
    }

    val fg = if (dark) Pal.text else Pal.ink
    val muted = if (dark) Pal.dim else Pal.grey
    val cardBg = if (dark) Color(0xFF221310) else Color.White
    val borderCol = if (dark) Pal.off else Pal.ink.copy(alpha = 0.2f)

    val selectedTitle = selectedDoc?.let { stringResource(it.titleRes) }
    val selectedSubtitle = selectedDoc?.let { stringResource(it.subtitleRes) }
    Column(Modifier.fillMaxWidth().heightIn(max = 580.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (selectedDoc != null) {
                Text(stringResource(R.string.license_back), color = fg, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { selectedDoc = null }.padding(end = 10.dp, top = 4.dp, bottom = 4.dp))
            }
            Text(
                text = selectedTitle ?: stringResource(R.string.about_title),
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = fg,
                modifier = Modifier.weight(1f),
            )
            Text(stringResource(R.string.close), color = muted, fontSize = 15.sp,
                modifier = Modifier.clickable { state.sheet.value = Sheet.NONE }.padding(6.dp))
        }
        Spacer(Modifier.height(10.dp))

        val activeDoc = selectedDoc
        if (activeDoc != null) {
            Text(selectedSubtitle.orEmpty(), fontFamily = Fonts.mono, fontSize = 10.sp, color = muted)
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
                        body = stringResource(R.string.about_identity, brand),
                        fg = fg, muted = muted, cardBg = cardBg, borderCol = borderCol,
                    )
                }
                item {
                    NoticeBlock(
                        title = stringResource(R.string.about_upstream_title),
                        body = stringResource(R.string.about_upstream_body),
                        fg = fg, muted = muted, cardBg = cardBg, borderCol = borderCol,
                    )
                }
                item {
                    NoticeBlock(
                        title = stringResource(R.string.about_license_split_title),
                        body = stringResource(R.string.about_license_split_body),
                        fg = fg, muted = muted, cardBg = cardBg, borderCol = borderCol,
                    )
                }
                item {
                    NoticeBlock(
                        title = stringResource(R.string.about_source_title),
                        body = stringResource(R.string.about_source_body),
                        fg = fg, muted = muted, cardBg = cardBg, borderCol = borderCol,
                    )
                }
                item {
                    NoticeBlock(
                        title = stringResource(R.string.about_dependencies_title),
                        body = stringResource(R.string.about_dependencies_body),
                        fg = fg, muted = muted, cardBg = cardBg, borderCol = borderCol,
                    )
                }
                item {
                    Text(stringResource(R.string.about_full_texts), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = fg,
                        modifier = Modifier.padding(top = 4.dp))
                }
                items(BundledLicenses.docs, key = { it.id }) { doc ->
                    val title = stringResource(doc.titleRes)
                    val subtitle = stringResource(doc.subtitleRes)
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(cardBg)
                            .border(1.dp, borderCol, RoundedCornerShape(10.dp))
                            .semantics { contentDescription = title }
                            .clickable { selectedDoc = doc }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = fg)
                        Spacer(Modifier.height(2.dp))
                        Text(subtitle, fontFamily = Fonts.mono, fontSize = 10.sp, color = muted)
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
