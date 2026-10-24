@file:OptIn(ExperimentalMaterial3Api::class)
package org.openrecorder.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import org.openrecorder.app.ui.icons.automirrored.OpenInNew
import androidx.compose.material.icons.rounded.*
import org.openrecorder.app.ui.icons.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.openrecorder.app.ai.AiConfig
import org.openrecorder.app.data.RecordingOptions

class SettingsActions(
    val back: () -> Unit = {}, val options: (RecordingOptions) -> Unit = {}, val addModel: () -> Unit = {},
    val getModels: () -> Unit = {}, val ai: () -> Unit = {}, val licenses: () -> Unit = {}, val source: () -> Unit = {},
)

@Composable
fun SettingsScreen(o: RecordingOptions, devices: List<Pair<Int, String>>, speechModel: Boolean, speakerModel: Boolean, ai: AiConfig,
                   version: String, actions: SettingsActions) {
    var dialog by remember { mutableStateOf("") }
    Scaffold(topBar = {
        TopAppBar(title = { Text("設定") }, navigationIcon = { IconButton(actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "戻る") } })
    }) { padding ->
        Column(Modifier.padding(padding).readable().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            SectionHeader("録音")
            SettingRow("音質", if (o.sampleRate == 16000) "標準（16 kHz）" else "高音質（48 kHz）", Icons.Rounded.HighQuality) { dialog = "quality" }
            SettingRow("マイク", devices.firstOrNull { it.first == o.device }?.second ?: "自動", Icons.Rounded.Mic) { dialog = "mic" }
            SwitchRow("ステレオ", o.channels == 2, icon = Icons.Rounded.SurroundSound) { actions.options(o.copy(channels = if (it) 2 else 1)) }
            SwitchRow("ノイズ抑制", o.noise, icon = Icons.Rounded.NoiseAware) { actions.options(o.copy(noise = it)) }
            SwitchRow("声を優先", o.voice, icon = Icons.Rounded.RecordVoiceOver) { actions.options(o.copy(voice = it)) }
            SwitchRow("録音中に文字起こし", o.live && speechModel, if (speechModel) null else "文字起こしモデルが必要です", speechModel, Icons.Rounded.ClosedCaption) { actions.options(o.copy(live = it)) }

            SectionHeader("文字起こし")
            SettingRow("文字起こしモデル", if (speechModel) "追加済み" else "未追加", Icons.Rounded.Subtitles, check = speechModel)
            SettingRow("話者モデル", if (speakerModel) "追加済み" else "未追加", Icons.Rounded.Groups, check = speakerModel)
            SettingRow("モデルを追加", "ZIPファイルを選択", Icons.Rounded.Add, onClick = actions.addModel)
            SettingRow("モデルを入手", "alphacephei.com/vosk/models", Icons.Rounded.Download, external = true, onClick = actions.getModels)

            SectionHeader("AI連携")
            SettingRow("AIサービス", aiSummary(ai), Icons.Rounded.AutoAwesome, chevron = true, onClick = actions.ai)

            SectionHeader("このアプリについて")
            SettingRow("バージョン", version, Icons.Rounded.Info)
            SettingRow("ライセンス", null, Icons.Rounded.Description, chevron = true, onClick = actions.licenses)
            SettingRow("ソースコード", "github.com/nekochan-cute/OpenRecorder", Icons.Rounded.Code, external = true, onClick = actions.source)
        }
    }
    when (dialog) {
        "quality" -> Choice("音質", listOf(16000 to "標準（16 kHz）", 48000 to "高音質（48 kHz）"), o.sampleRate, { dialog = "" }) {
            actions.options(o.copy(sampleRate = it)); dialog = ""
        }
        "mic" -> Choice("マイク", listOf(0 to "自動") + devices, o.device, { dialog = "" }) { actions.options(o.copy(device = it)); dialog = "" }
    }
}

private fun aiSummary(c: AiConfig): String {
    val parts = listOfNotNull(c.summaryProvider?.let { "要約: ${it.label}" }, c.transcriptionProvider?.let { "文字起こし: ${it.label}" })
    return parts.joinToString(" / ").ifBlank { "未設定" }
}

@Composable
private fun SettingRow(title: String, value: String?, icon: ImageVector, check: Boolean = false, chevron: Boolean = false, external: Boolean = false,
                onClick: (() -> Unit)? = null) {
    ListItem({ Text(title) }, if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        supportingContent = value?.let { { Text(it) } }, leadingContent = { Icon(icon, null) },
        trailingContent = when {
            check -> { { Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) } }
            chevron -> { { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) } }
            external -> { { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(20.dp)) } }
            else -> null
        }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
}

@Composable
fun <T> Choice(title: String, options: List<Pair<T, String>>, selected: T, dismiss: () -> Unit, choose: (T) -> Unit) {
    AlertDialog(dismiss, title = { Text(title) }, text = {
        Column {
            options.forEach { (value, label) ->
                ListItem({ Text(label) }, Modifier.clickable { choose(value) }, leadingContent = { RadioButton(value == selected, { choose(value) }) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            }
        }
    }, confirmButton = { TextButton(dismiss) { Text("閉じる") } })
}
