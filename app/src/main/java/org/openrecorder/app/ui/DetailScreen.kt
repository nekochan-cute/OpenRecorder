@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package org.openrecorder.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import org.openrecorder.app.ui.icons.automirrored.Notes
import androidx.compose.material.icons.rounded.*
import org.openrecorder.app.ui.icons.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openrecorder.app.core.LlmTasks
import org.openrecorder.app.core.Mark
import org.openrecorder.app.core.ProcessOptions
import org.openrecorder.app.core.Recording
import org.openrecorder.app.core.Segment
import java.io.File
import java.util.Locale

class DetailState(
    val recording: Recording, val levels: ByteArray?, val position: Long, val playing: Boolean,
    val speed: Float = 1f, val skipSilence: Boolean = false, val busy: Boolean = false,
    val localModel: Boolean = false, val aiTranscription: Boolean = false, val aiSummary: Boolean = false,
    /** On-device LLM installed (generative summary, translation). */
    val llm: Boolean = false,
    val folders: List<String> = emptyList(),
)

class DetailActions(
    val back: () -> Unit = {}, val playPause: () -> Unit = {}, val seek: (Long) -> Unit = {},
    val speed: (Float) -> Unit = {}, val skipSilence: (Boolean) -> Unit = {},
    val favorite: () -> Unit = {}, val rename: (String) -> Unit = {}, val move: (String) -> Unit = {}, val trash: () -> Unit = {},
    val edit: () -> Unit = {}, val enhance: (ProcessOptions) -> Unit = {}, val share: (String) -> Unit = {},
    val transcribe: (remote: Boolean) -> Unit = {},
    /** engine: "llm" on-device model, "api" AI service, "extract" sentence extraction. */
    val summarize: (template: String, engine: String) -> Unit = { _, _ -> },
    val translate: (language: String, remote: Boolean) -> Unit = { _, _ -> }, val clearTranslation: () -> Unit = {},
    val addMark: (Long) -> Unit = {}, val addPhoto: (Long) -> Unit = {}, val editMark: (String, String) -> Unit = { _, _ -> },
    val deleteMark: (String) -> Unit = {}, val openPhoto: (String) -> Unit = {}, val photoFile: (String) -> File? = { null },
    val updateSegment: (Int, String, String) -> Unit = { _, _, _ -> }, val renameSpeaker: (String, String) -> Unit = { _, _ -> },
    val settings: () -> Unit = {},
)

private val tabs = listOf("音声", "文字起こし", "要約")

@Composable
fun DetailScreen(state: DetailState, actions: DetailActions, initialTab: Int = 0) {
    val r = state.recording
    var tab by rememberSaveable(r.id) { mutableIntStateOf(initialTab) }
    var sheet by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf("") }
    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                TopAppBar(
                    navigationIcon = { IconButton(actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "戻る") } },
                    title = { Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { dialog = "rename" }) },
                    actions = {
                        IconButton(actions.favorite) {
                            Icon(if (r.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (r.favorite) "お気に入りから外す" else "お気に入り",
                                tint = if (r.favorite) MaterialTheme.colorScheme.tertiary else LocalContentColor.current)
                        }
                        IconButton({ sheet = "share" }) { Icon(Icons.Rounded.Share, "共有") }
                        var menu by remember { mutableStateOf(false) }
                        IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "その他") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("切り取り") }, { menu = false; actions.edit() }, enabled = !state.busy, leadingIcon = { Icon(Icons.Rounded.ContentCut, null) })
                            DropdownMenuItem({ Text("音声を整える") }, { menu = false; sheet = "enhance" }, enabled = !state.busy, leadingIcon = { Icon(Icons.Rounded.GraphicEq, null) })
                            DropdownMenuItem({ Text("名前を変更") }, { menu = false; dialog = "rename" }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                            DropdownMenuItem({ Text("フォルダーに移動") }, { menu = false; dialog = "folder" }, leadingIcon = { Icon(Icons.Rounded.DriveFileMove, null) })
                            if (r.segments.isNotEmpty() && (state.llm || state.aiSummary)) DropdownMenuItem({ Text("翻訳") }, { menu = false; sheet = "translate" },
                                enabled = !state.busy, leadingIcon = { Icon(Icons.Rounded.Translate, null) })
                            if (r.translationLanguage.isNotEmpty()) DropdownMenuItem({ Text("翻訳を削除") }, { menu = false; actions.clearTranslation() },
                                leadingIcon = { Icon(Icons.Rounded.Close, null) })
                            if (r.segments.isNotEmpty()) DropdownMenuItem({ Text("文字起こしをやり直す") }, { menu = false; dialog = "retranscribe" },
                                enabled = !state.busy, leadingIcon = { Icon(Icons.Rounded.Refresh, null) })
                            HorizontalDivider()
                            DropdownMenuItem({ Text("削除") }, { menu = false; actions.trash() }, leadingIcon = { Icon(Icons.Rounded.Delete, null) })
                        }
                    })
                PrimaryTabRow(tab, containerColor = MaterialTheme.colorScheme.surface) {
                    tabs.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        },
        bottomBar = { PlayerBar(state, actions, showSeekBar = tab != 0) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> AudioTab(state, actions)
                1 -> TranscriptTab(state, actions)
                else -> SummaryTab(state, actions) { tab = 1 }
            }
        }
    }
    when (sheet) {
        "share" -> ShareSheet(r, { sheet = "" }) { actions.share(it); sheet = "" }
        "enhance" -> EnhanceSheet({ sheet = "" }) { actions.enhance(it); sheet = "" }
        "translate" -> TranslateSheet(r.translationLanguage, state.llm, state.aiSummary, { sheet = "" }) { language, remote -> actions.translate(language, remote); sheet = "" }
    }
    when (dialog) {
        "rename" -> TextDialog("名前を変更", r.title, { dialog = "" }) { actions.rename(it); dialog = "" }
        "folder" -> FolderDialog(r.folder, state.folders, { dialog = "" }) { actions.move(it); dialog = "" }
        "retranscribe" -> AlertDialog({ dialog = "" }, title = { Text("文字起こしをやり直しますか？") }, text = { Text("今の文字起こしと要約は置き換わります。") },
            confirmButton = { TextButton({ dialog = ""; actions.transcribe(!state.localModel) }) { Text("やり直す") } },
            dismissButton = { TextButton({ dialog = "" }) { Text("キャンセル") } })
    }
}

@Composable
private fun PlayerBar(state: DetailState, actions: DetailActions, showSeekBar: Boolean) {
    val d = state.recording.durationMs.coerceAtLeast(1)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.navigationBarsPadding().readable().padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (showSeekBar) {
                SeekBar(state.position, d, actions.seek, Modifier.fillMaxWidth().height(28.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text(clock(state.position), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(clock(state.recording.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                var speedMenu by remember { mutableStateOf(false) }
                Box(Modifier.width(72.dp)) {
                    TextButton({ speedMenu = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                        Text(speedLabel(state.speed), style = MaterialTheme.typography.titleSmall)
                    }
                    DropdownMenu(speedMenu, { speedMenu = false }) {
                        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f).forEach { v ->
                            DropdownMenuItem({ Text(speedLabel(v)) }, { speedMenu = false; actions.speed(v) },
                                trailingIcon = { if (v == state.speed) Icon(Icons.Rounded.Check, null) })
                        }
                    }
                }
                IconButton({ actions.seek((state.position - 10_000).coerceAtLeast(0)) }) { Icon(Icons.Rounded.Replay10, "10秒戻る", Modifier.size(28.dp)) }
                FilledIconButton(actions.playPause, Modifier.size(64.dp), shape = CircleShape) {
                    Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "一時停止" else "再生", Modifier.size(34.dp))
                }
                IconButton({ actions.seek((state.position + 10_000).coerceAtMost(state.recording.durationMs)) }) { Icon(Icons.Rounded.Forward10, "10秒進む", Modifier.size(28.dp)) }
                Box(Modifier.width(72.dp), contentAlignment = Alignment.Center) {
                    IconToggleButton(state.skipSilence, actions.skipSilence) {
                        Icon(Icons.Rounded.FastForward, if (state.skipSilence) "無音スキップ：オン" else "無音スキップ：オフ",
                            tint = if (state.skipSilence) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun speedLabel(v: Float) = (if (v % 1f == 0f) "%.1f".format(Locale.ROOT, v) else v.toString()) + "×"

@Composable
private fun AudioTab(state: DetailState, actions: DetailActions) {
    val r = state.recording
    var editing by remember { mutableStateOf<Mark?>(null) }
    LazyColumn(Modifier.fillMaxSize().readable(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 28.dp)) {
                Text(clock(state.position), style = MaterialTheme.typography.displayMedium)
                Text(clock(r.durationMs), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                ScrollingWaveform(LevelTrack.of(state.levels), state.position, r.durationMs, r.marks.map { it.timeMs },
                    Modifier.fillMaxWidth().height(180.dp), onSeek = actions.seek)
                Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton({ actions.addMark(state.position) }) { Icon(Icons.Rounded.Flag, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("マーク") }
                    FilledTonalIconButton({ actions.addPhoto(state.position) }) { Icon(Icons.Rounded.AddAPhoto, "写真付きマーク") }
                }
            }
        }
        if (r.marks.isNotEmpty()) item { SectionHeader("マーク") }
        items(r.marks, key = { it.id }) { m -> MarkRow(m, actions, { editing = m }) }
    }
    editing?.let { m -> TextDialog("メモ", m.text, { editing = null }) { actions.editMark(m.id, it); editing = null } }
}

@Composable
private fun MarkRow(m: Mark, actions: DetailActions, edit: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        ListItem(
            leadingContent = {
                val photo = m.photo
                if (photo != null) Thumbnail(actions.photoFile(photo), Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).clickable { actions.openPhoto(photo) })
                else Icon(Icons.Rounded.Flag, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(10.dp))
            },
            headlineContent = { Text(m.text.ifBlank { "マーク" }, color = if (m.text.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified, maxLines = 2) },
            trailingContent = { Text(clock(m.timeMs), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            modifier = Modifier.combinedClickable(onClick = { actions.seek(m.timeMs) }, onLongClick = { menu = true }).padding(horizontal = 8.dp),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        DropdownMenu(menu, { menu = false }, offset = androidx.compose.ui.unit.DpOffset(24.dp, 0.dp)) {
            DropdownMenuItem({ Text("メモを編集") }, { menu = false; edit() }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
            DropdownMenuItem({ Text("削除") }, { menu = false; actions.deleteMark(m.id) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) })
        }
    }
}

@Composable
private fun Thumbnail(file: File?, modifier: Modifier) {
    var bitmap by remember(file) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(file) {
        bitmap = file?.let { f -> withContext(Dispatchers.IO) { runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(f.path, it) }
            val sample = Integer.highestOneBit((maxOf(bounds.outWidth, bounds.outHeight) / 160).coerceAtLeast(1))
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }.getOrNull() } }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, "写真", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Icon(Icons.Rounded.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TranscriptTab(state: DetailState, actions: DetailActions) {
    val r = state.recording
    if (r.segments.isEmpty()) {
        val canLocal = state.localModel; val canRemote = state.aiTranscription
        EmptyState(Icons.AutoMirrored.Rounded.Notes, if (canLocal || canRemote) "文字起こしはまだありません" else "文字起こしにはモデルかAIサービスが必要です",
            Modifier.verticalScroll(rememberScrollState())) {
            when {
                canLocal -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Button({ actions.transcribe(false) }, enabled = !state.busy) { Text("文字起こし") }
                    if (canRemote) TextButton({ actions.transcribe(true) }, enabled = !state.busy) { Text("AIサービスで文字起こし") }
                }
                canRemote -> Button({ actions.transcribe(true) }, enabled = !state.busy) { Text("文字起こし") }
                else -> OutlinedButton(actions.settings) { Text("設定を開く") }
            }
        }
        return
    }
    val speakers = r.segments.map { it.speaker }.filter(String::isNotBlank).distinct()
    val dark = isDark
    var editing by remember { mutableStateOf<Int?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    val current = r.segments.indexOfFirst { state.position >= it.startMs && state.position < it.endMs }
    val list = rememberLazyListState()
    LaunchedEffect(current, state.playing) {
        if (state.playing && current >= 0 && list.layoutInfo.visibleItemsInfo.none { it.index == current + 1 }) list.animateScrollToItem(current + 1)
    }
    LazyColumn(Modifier.fillMaxSize().readable(), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            if (speakers.isNotEmpty()) Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                speakers.forEach { s ->
                    AssistChip({ renaming = s }, { Text(s) }, leadingIcon = { SpeakerDot(speakers.indexOf(s), dark) })
                }
            } else Spacer(Modifier.height(12.dp))
        }
        itemsIndexed(r.segments) { i, s ->
            val showSpeaker = s.speaker.isNotBlank() && (i == 0 || r.segments[i - 1].speaker != s.speaker)
            SegmentRow(s, showSpeaker, speakers.indexOf(s.speaker), dark, i == current, { actions.seek(s.startMs) }, { editing = i })
        }
    }
    editing?.let { i -> r.segments.getOrNull(i)?.let { s -> SegmentDialog(s, speakers, { editing = null }) { text, sp -> actions.updateSegment(i, text, sp); editing = null } } }
    renaming?.let { s -> TextDialog("話者の名前", s, { renaming = null }) { actions.renameSpeaker(s, it); renaming = null } }
}

@Composable
private fun SpeakerDot(index: Int, dark: Boolean) = Box(Modifier.size(10.dp).background(speakerColor(index, dark), CircleShape))

@Composable
private fun SegmentRow(s: Segment, showSpeaker: Boolean, speakerIndex: Int, dark: Boolean, current: Boolean, seek: () -> Unit, edit: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = seek, onLongClick = edit)
        .background(if (current) colors.primaryContainer.copy(alpha = 0.3f) else Color.Transparent)
        .padding(horizontal = 24.dp, vertical = 8.dp)) {
        if (showSpeaker) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
            SpeakerDot(speakerIndex, dark); Spacer(Modifier.width(8.dp))
            Text(s.speaker, style = MaterialTheme.typography.labelLarge, color = speakerColor(speakerIndex, dark))
        }
        Row {
            Text(clock(s.startMs), style = MaterialTheme.typography.labelMedium, color = colors.outline, modifier = Modifier.width(52.dp).padding(top = 4.dp))
            Column {
                Text(s.text, style = MaterialTheme.typography.bodyLarge, color = if (current) colors.onSurface else colors.onSurface.copy(alpha = 0.92f))
                if (s.translation.isNotBlank()) Text(s.translation, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun SegmentDialog(s: Segment, speakers: List<String>, dismiss: () -> Unit, save: (String, String) -> Unit) {
    var text by remember { mutableStateOf(s.text) }
    var speaker by remember { mutableStateOf(s.speaker) }
    AlertDialog(dismiss, title = { Text(clock(s.startMs)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (speakers.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                speakers.forEach { sp -> FilterChip(speaker == sp, { speaker = sp }, { Text(sp) }) }
            }
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 8)
        }
    }, confirmButton = { TextButton({ save(text, speaker) }) { Text("保存") } }, dismissButton = { TextButton(dismiss) { Text("キャンセル") } })
}

private val templates = listOf("会議", "講義", "インタビュー")

@Composable
private fun SummaryTab(state: DetailState, actions: DetailActions, toTranscript: () -> Unit) {
    val r = state.recording
    var template by rememberSaveable { mutableStateOf(templates[0]) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var engineMenu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val engines = buildList {
        if (state.llm) add("llm" to "端末のAIで要約")
        add("extract" to if (state.llm) "文を抜き出して要約" else "要約する")
        if (state.aiSummary) add("api" to "AIサービスで要約")
    }
    Column(Modifier.fillMaxSize().readable().verticalScroll(rememberScrollState())) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            templates.forEachIndexed { i, t ->
                SegmentedButton(template == t, { template = t }, SegmentedButtonDefaults.itemShape(i, templates.size)) { Text(t) }
            }
        }
        when {
            r.segments.isEmpty() -> EmptyState(Icons.Rounded.AutoAwesome, "要約には文字起こしが必要です") { OutlinedButton(toTranscript) { Text("文字起こしへ") } }
            r.summary.isBlank() -> EmptyState(Icons.Rounded.AutoAwesome, "要約はまだありません") {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Button({ actions.summarize(template, engines[0].first) }, enabled = !state.busy) { Text(if (engines[0].first == "llm") "要約する" else engines[0].second) }
                    engines.drop(1).forEach { (engine, label) -> TextButton({ actions.summarize(template, engine) }, enabled = !state.busy) { Text(label) } }
                }
            }
            else -> {
                MarkdownText(r.summary, Modifier.padding(horizontal = 24.dp))
                Row(Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
                    Box {
                        TextButton({ if (engines.size == 1) confirm = engines[0].first else engineMenu = true }, enabled = !state.busy) {
                            Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("作り直す")
                        }
                        DropdownMenu(engineMenu, { engineMenu = false }) {
                            engines.forEach { (engine, label) -> DropdownMenuItem({ Text(label) }, { engineMenu = false; confirm = engine }) }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton({ clipboard.setText(AnnotatedString(r.summary)) }) { Icon(Icons.Rounded.ContentCopy, "コピー") }
                }
            }
        }
    }
    confirm?.let { engine ->
        AlertDialog({ confirm = null }, title = { Text("要約を作り直しますか？") }, text = { Text("今の要約とタイトルは置き換わります。") },
            confirmButton = { TextButton({ confirm = null; actions.summarize(template, engine) }) { Text("作り直す") } },
            dismissButton = { TextButton({ confirm = null }) { Text("キャンセル") } })
    }
}

@Composable
private fun ShareSheet(r: Recording, dismiss: () -> Unit, share: (String) -> Unit) {
    ModalBottomSheet(dismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text("共有", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            ShareRow(Icons.Rounded.AudioFile, "音声", "M4A") { share("m4a") }
            ShareRow(Icons.Rounded.AudioFile, "音声", "MP3") { share("mp3") }
            ShareRow(Icons.Rounded.AudioFile, "音声（無圧縮）", "WAV") { share("wav") }
            if (r.segments.isNotEmpty()) {
                ShareRow(Icons.AutoMirrored.Rounded.Notes, "文字起こし", "テキスト") { share("transcript") }
                ShareRow(Icons.Rounded.Subtitles, "字幕", "SRT") { share("srt") }
                ShareRow(Icons.Rounded.Subtitles, "字幕", "WebVTT") { share("vtt") }
                if (r.translationLanguage.isNotEmpty()) {
                    val language = LlmTasks.languages[r.translationLanguage] ?: r.translationLanguage
                    ShareRow(Icons.Rounded.Translate, "翻訳（$language）", "テキスト") { share("translation") }
                    ShareRow(Icons.Rounded.Translate, "翻訳の字幕（$language）", "SRT") { share("srt-translation") }
                    ShareRow(Icons.Rounded.Translate, "翻訳の字幕（$language）", "WebVTT") { share("vtt-translation") }
                }
            }
            if (r.summary.isNotBlank()) ShareRow(Icons.Rounded.AutoAwesome, "要約", "Markdown") { share("summary") }
            if (r.marks.isNotEmpty()) ShareRow(Icons.Rounded.Flag, "マーク一覧", "テキスト") { share("marks") }
        }
    }
}

@Composable
private fun ShareRow(icon: ImageVector, title: String, format: String, onClick: () -> Unit) {
    ListItem({ Text(title) }, Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp), leadingContent = { Icon(icon, null) },
        trailingContent = { Text(format, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent))
}

@Composable
private fun EnhanceSheet(dismiss: () -> Unit, apply: (ProcessOptions) -> Unit) {
    var noise by rememberSaveable { mutableStateOf(true) }
    var strength by rememberSaveable { mutableFloatStateOf(0.6f) }
    var enhance by rememberSaveable { mutableStateOf(true) }
    var normalize by rememberSaveable { mutableStateOf(true) }
    ModalBottomSheet(dismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("音声を整える", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            SwitchRow("ノイズを減らす", noise) { noise = it }
            if (noise) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("弱", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(strength, { strength = it }, Modifier.weight(1f).padding(horizontal = 12.dp))
                Text("強", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SwitchRow("声を聞き取りやすく", enhance) { enhance = it }
            SwitchRow("音量をそろえる", normalize) { normalize = it }
            Button({ apply(ProcessOptions(noise, strength, enhance, normalize)) }, Modifier.fillMaxWidth().padding(top = 16.dp),
                enabled = noise || enhance || normalize) { Text("新しい録音として保存") }
        }
    }
}

@Composable
fun SwitchRow(title: String, value: Boolean, supporting: String? = null, enabled: Boolean = true, icon: ImageVector? = null, onChange: (Boolean) -> Unit) {
    ListItem({ Text(title) }, Modifier.clickable(enabled = enabled) { onChange(!value) }, supportingContent = supporting?.let { { Text(it) } },
        leadingContent = icon?.let { { Icon(it, null) } },
        trailingContent = { Switch(value, onChange, enabled = enabled) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
}

@Composable
private fun TranslateSheet(current: String, llm: Boolean, remote: Boolean, dismiss: () -> Unit, translate: (String, Boolean) -> Unit) {
    var language by rememberSaveable { mutableStateOf(current.ifBlank { "en" }) }
    var useRemote by rememberSaveable { mutableStateOf(!llm) }
    ModalBottomSheet(dismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("翻訳", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LlmTasks.languages.forEach { (code, name) -> FilterChip(language == code, { language = code }, { Text(name) }) }
            }
            if (llm && remote) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                SegmentedButton(!useRemote, { useRemote = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("端末のAI") }
                SegmentedButton(useRemote, { useRemote = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("AIサービス") }
            }
            Button({ translate(language, useRemote && remote || !llm) }, Modifier.fillMaxWidth().padding(top = 20.dp)) { Text("翻訳する") }
        }
    }
}
