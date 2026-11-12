package app.spektralab.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spektralab.R

@Composable
fun SettingsScreen(state: AppState) {
    val language by state.language.collectAsState()
    val gray18 by state.gray18.collectAsState()
    val reveal by state.reveal.collectAsState()
    SettingsContent(
        language = language,
        gray18 = gray18,
        reveal = reveal,
        onBack = state::closeSettings,
        onLanguage = state::setLanguage,
        onGray18 = state::setGray18,
        onReveal = state::setReveal,
    )
}

@Composable
internal fun SettingsContent(
    language: AppLanguage,
    gray18: Boolean,
    reveal: Boolean,
    onBack: () -> Unit,
    onLanguage: (AppLanguage) -> Unit,
    onGray18: (Boolean) -> Unit,
    onReveal: (Boolean) -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Pal.glow, Pal.paper), radius = 1800f))
        .statusBarsPadding().navigationBarsPadding().testTag("settings_screen")) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.back_to_lightbox), color = Pal.ink, fontSize = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).springClick(onClick = onBack)
                        .padding(horizontal = 6.dp, vertical = 8.dp).testTag("settings_back"))
                Spacer(Modifier.weight(1f))
                SettingsIcon(Pal.ink, Modifier.size(24.dp))
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(48.dp))
            }
            Column(Modifier.align(Alignment.CenterHorizontally).widthIn(max = SheetMaxWidth).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.settings), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Pal.ink)
                Spacer(Modifier.height(24.dp))
                SettingsHeading(stringResource(R.string.language), stringResource(R.string.language_subtitle))
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppLanguage.entries.forEach { item ->
                        LanguageOption(item, item == language, onClick = { onLanguage(item) })
                    }
                }
                Spacer(Modifier.height(28.dp))
                SettingsHeading(stringResource(R.string.display), stringResource(R.string.display_subtitle))
                Spacer(Modifier.height(8.dp))
                SettingsToggleRow(
                    title = stringResource(R.string.gray_18_background),
                    summary = stringResource(R.string.gray_18_background_summary),
                    checked = gray18,
                    testTag = "settings_gray18",
                    onCheckedChange = onGray18,
                )
                SettingsToggleRow(
                    title = stringResource(R.string.development_animation),
                    summary = stringResource(R.string.development_animation_summary),
                    checked = reveal,
                    testTag = "settings_reveal",
                    onCheckedChange = onReveal,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SettingsHeading(title: String, subtitle: String) {
    Text(title, color = Pal.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    Text(subtitle, color = Pal.grey, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun LanguageOption(language: AppLanguage, selected: Boolean, onClick: () -> Unit) {
    val label = stringResource(when (language) {
        AppLanguage.SYSTEM -> R.string.language_system
        AppLanguage.ENGLISH -> R.string.language_english
        AppLanguage.SIMPLIFIED_CHINESE -> R.string.language_zh_hans
        AppLanguage.TRADITIONAL_CHINESE -> R.string.language_zh_hant
    })
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) Pal.card else Color(0x66FFFFFF))
            .border(1.dp, if (selected) Pal.red else Pal.grey.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("language_${language.storedValue}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = Pal.red, unselectedColor = Pal.grey))
        Text(label, color = Pal.ink, fontSize = 16.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun SettingsToggleRow(title: String, summary: String, checked: Boolean, testTag: String, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, color = Pal.ink, fontSize = 15.sp)
            Text(summary, color = Pal.grey, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.testTag(testTag),
            colors = SwitchDefaults.colors(checkedTrackColor = Pal.red, checkedThumbColor = Pal.paper))
    }
}

/** Small drawn settings icon; no additional icon dependency is required. */
@Composable
fun SettingsIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val unit = size.minDimension / 24f
        val center = Offset(size.width / 2, size.height / 2)
        drawCircle(tint, 7f * unit, center, style = Stroke(1.8f * unit))
        drawCircle(tint, 2.4f * unit, center, style = Stroke(1.8f * unit))
        for (i in 0 until 8) {
            val angle = i * Math.PI / 4
            val direction = Offset(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())
            drawLine(tint, center + direction * (8.5f * unit), center + direction * (11f * unit),
                strokeWidth = 2.2f * unit, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

/** Settings button with a localized accessibility label. */
@Composable
fun SettingsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.settings)
    Box(modifier.size(40.dp).clip(CircleShape).semantics { contentDescription = description }
        .springClick(onClick = onClick).testTag("settings_button"), contentAlignment = Alignment.Center) {
        SettingsIcon(Pal.ink, Modifier.size(22.dp))
    }
}
