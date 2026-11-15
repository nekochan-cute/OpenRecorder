@file:OptIn(ExperimentalMaterial3Api::class)
package org.openrecorder.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import org.openrecorder.app.ui.icons.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.openrecorder.app.ai.DownloadState
import org.openrecorder.app.ai.ModelCatalog
import org.openrecorder.app.ai.ModelSpec
import org.openrecorder.app.ai.SpeechPreset
import org.openrecorder.app.data.RecordingMode

class SetupState(
    val micGranted: Boolean = false,
    /** Null where the Android version has no notification permission. */
    val notificationsGranted: Boolean? = false,
    /** Ids of the downloaded models. */
    val installed: Set<String> = emptySet(),
    val downloads: Map<String, DownloadState> = emptyMap(),
    val speechReady: Boolean = false, val live: Boolean = false, val mode: RecordingMode = RecordingMode.Custom,
    val llmSupported: Boolean = true,
)

class SetupActions(
    val requestPermissions: (done: () -> Unit) -> Unit = {}, val downloadSpeech: (SpeechPreset) -> Unit = {},
    val live: (Boolean) -> Unit = {}, val mode: (RecordingMode) -> Unit = {},
    val downloadLlm: (ModelSpec) -> Unit = {}, val aiService: () -> Unit = {}, val finish: () -> Unit = {},
)

private enum class SetupStep(val title: String) {
    Welcome("Open Recorderへようこそ"), Permissions("マイクと通知"), Speech("文字起こしのモデル"), Live("録音中の字幕"),
    Mode("録音モード"), Summary("要約・翻訳"),
}

/** First-launch setup, one step per screen. Each step can be skipped; a step changes nothing until its main button is pressed. */
@Composable
fun SetupScreen(state: SetupState, actions: SetupActions, initialStep: Int = 0) {
    var index by rememberSaveable { mutableIntStateOf(initialStep) }
    var preset by rememberSaveable { mutableStateOf(SpeechPreset.Standard) }
    var live by rememberSaveable { mutableStateOf(state.live) }
    var mode by rememberSaveable { mutableStateOf(state.mode) }
    /** A model id, "api", or nothing chosen. */
    var summary by rememberSaveable { mutableStateOf<String?>(null) }
    val step = SetupStep.entries[index]
    fun next() { if (index == SetupStep.entries.lastIndex) actions.finish() else index++ }
    BackHandler(index > 0) { index-- }

    fun downloading(spec: ModelSpec) = state.downloads[spec.id]?.let { it.error == null } == true
    val missing = preset.models.filter { it.id !in state.installed && !downloading(it) }
    val llms = if (state.llmSupported) listOf(ModelCatalog.gemma, ModelCatalog.swallow) else emptyList()
    val action: Pair<String, () -> Unit> = when (step) {
        SetupStep.Welcome -> "次へ" to { next() }
        SetupStep.Permissions -> if (state.micGranted && state.notificationsGranted != false) "次へ" to { next() }
            else "許可する" to { actions.requestPermissions { next() } }
        SetupStep.Speech -> if (missing.isEmpty()) "次へ" to { next() } else "ダウンロード" to { actions.downloadSpeech(preset) }
        SetupStep.Live -> "次へ" to { actions.live(live); next() }
        SetupStep.Mode -> "次へ" to { actions.mode(mode); next() }
        SetupStep.Summary -> when (val chosen = summary) {
            null -> "完了" to actions.finish
            "api" -> "AIサービスを設定" to actions.aiService
            else -> llms.first { it.id == chosen }.let { spec ->
                (if (spec.id in state.installed) "完了" else "ダウンロードして完了") to { actions.downloadLlm(spec); actions.finish() }
            }
        }
    }
    val (label, primary) = action

    Scaffold(topBar = {
        TopAppBar(title = { Text("セットアップ（${index + 1}/${SetupStep.entries.size}）") },
            actions = { IconButton(actions.finish) { Icon(Icons.Rounded.Close, "セットアップを閉じる") } })
    }, bottomBar = {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step != SetupStep.Welcome) TextButton({ next() }) { Text("スキップ") }
            Spacer(Modifier.weight(1f))
            Button(primary) { Text(label) }
        }
    }) { padding ->
        Column(Modifier.padding(padding).readable().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(step.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
            when (step) {
                SetupStep.Welcome -> {
                    Paragraph("録音、文字起こし、話者の聞き分け、要約、翻訳ができます。")
                    Paragraph("モデルをダウンロードすれば、これらは端末内で行えます。音声や文字が外部へ送られるのは、AIサービスを設定してその機能を使ったときだけです。")
                    Paragraph("このあと、許可、文字起こしのモデル、録音中の字幕、録音モード、要約・翻訳を順に設定します。どれもスキップでき、あとから設定画面で変えられます。")
                }
                SetupStep.Permissions -> {
                    Paragraph("録音にはマイクの許可が必要です。" + if (state.notificationsGranted != null) "通知は、録音中とモデルのダウンロード中の表示に使います。" else "")
                    Paragraph("許可しなくても先へ進めます。その場合は、録音するときに改めて確認します。")
                    Status("マイク", state.micGranted, Icons.Rounded.Mic)
                    state.notificationsGranted?.let { Status("通知", it, Icons.Rounded.Notifications) }
                }
                SetupStep.Speech -> {
                    Paragraph("文字起こしは端末内のモデルで行います。使うモデルを選んでダウンロードしてください。発話の検出と話者の聞き分けのモデル（約30 MB）も一緒に入ります。")
                    SpeechPreset.entries.forEach { p ->
                        val installed = p.models.all { it.id in state.installed }
                        Option(preset == p, "${p.label}  ·  ${sizeLabel(p.size)}", p.detail, if (installed) "ダウンロード済み" else null) { preset = p }
                    }
                    val active = preset.models.filter(::downloading)
                    if (active.isNotEmpty()) {
                        val done = preset.models.sumOf { m -> if (m.id in state.installed) m.size.toDouble() else (state.downloads[m.id]?.progress ?: 0f) * m.size.toDouble() }
                        val progress = (done / preset.size).toFloat()
                        LinearProgressIndicator({ progress }, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
                        Paragraph("ダウンロード中 ${(progress * 100).toInt()}%。この画面を離れても続きます。")
                    }
                    preset.models.firstNotNullOfOrNull { state.downloads[it.id]?.error }?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
                    }
                }
                SetupStep.Live -> {
                    Paragraph("録音しながら、話した内容を画面に文字で表示します。端末の処理が追いつかない場合は字幕だけが止まり、録音は続きます。")
                    SwitchRow("録音中に文字起こし", live, if (state.speechReady) null else "文字起こしのモデルが入ると使えます", icon = Icons.Rounded.ClosedCaption) { live = it }
                }
                SetupStep.Mode -> {
                    Paragraph("録音の状況に合わせて、マイクの設定と文字起こしの処理をまとめて切り替えます。録音ボタンの上からも変えられます。")
                    RecordingMode.entries.forEach { m -> Option(mode == m, m.label, m.detail) { mode = m } }
                }
                SetupStep.Summary -> {
                    Paragraph("要約と翻訳には、端末内のAIかAIサービスを使います。AIサービスでは、文字起こしの内容がそのサービスへ送られます。文を抜き出す要約は、どちらがなくても使えます。")
                    if (!state.llmSupported) Paragraph("この端末では端末内のAIを使用できません。")
                    llms.forEach { spec ->
                        Option(summary == spec.id, "${spec.title}  ·  ${sizeLabel(spec.size)}", spec.detail,
                            if (spec.id in state.installed) "ダウンロード済み" else null) { summary = spec.id }
                    }
                    Option(summary == "api", "AIサービスを設定する", "OpenRouter、Google AI Studio、OpenAI、Groq、OpenAI互換のAPI") { summary = "api" }
                }
            }
        }
    }
}

@Composable
private fun Paragraph(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
}

@Composable
private fun Status(title: String, granted: Boolean, icon: ImageVector) {
    ListItem({ Text(title) }, Modifier.padding(horizontal = 8.dp), supportingContent = { Text(if (granted) "許可済み" else "未許可") },
        leadingContent = { Icon(icon, null) },
        trailingContent = if (granted) { { Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) } } else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent))
}

@Composable
private fun Option(selected: Boolean, title: String, detail: String, note: String? = null, choose: () -> Unit) {
    ListItem({ Text(title) }, Modifier.clickable(onClick = choose).padding(horizontal = 8.dp),
        supportingContent = { Text(listOfNotNull(detail, note).joinToString("  ·  ")) }, leadingContent = { RadioButton(selected, choose) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent))
}
