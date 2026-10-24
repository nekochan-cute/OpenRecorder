@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
package org.openrecorder.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openrecorder.app.audio.CaptureState
import org.openrecorder.app.core.Recording

class LibraryActions(
    val open: (String) -> Unit = {}, val record: () -> Unit = {}, val showRecording: () -> Unit = {},
    val import: () -> Unit = {}, val trash: () -> Unit = {}, val settings: () -> Unit = {},
    val favorite: (String) -> Unit = {}, val rename: (String, String) -> Unit = { _, _ -> }, val move: (String, String) -> Unit = { _, _ -> },
    val delete: (String) -> Unit = {},
)

@Composable
fun LibraryScreen(recordings: List<Recording>, capture: CaptureState, actions: LibraryActions) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("") } // "" all, "★" favourites, otherwise a folder
    var renaming by remember { mutableStateOf<Recording?>(null) }
    var moving by remember { mutableStateOf<Recording?>(null) }
    val active = recordings.filter { it.deletedAt == null && !it.unfinished }
    val folders = active.map { it.folder }.filter(String::isNotBlank).distinct().sorted()
    val shown = active.filter { r ->
        (filter.isEmpty() || (filter == FAVORITES && r.favorite) || r.folder == filter) && matches(r, query)
    }
    Scaffold(
        topBar = {
            AnimatedContent(searching, label = "search") { s ->
                if (s) SearchBar(query, { query = it }) { searching = false; query = "" }
                else TopAppBar(title = { Text("録音") }, actions = {
                    IconButton({ searching = true }) { Icon(Icons.Rounded.Search, "検索") }
                    var menu by remember { mutableStateOf(false) }
                    IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "その他") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("音声を取り込む") }, { menu = false; actions.import() }, leadingIcon = { Icon(Icons.Rounded.FileOpen, null) })
                        DropdownMenuItem({ Text("ごみ箱") }, { menu = false; actions.trash() }, leadingIcon = { Icon(Icons.Rounded.Delete, null) })
                        DropdownMenuItem({ Text("設定") }, { menu = false; actions.settings() }, leadingIcon = { Icon(Icons.Rounded.Settings, null) })
                    }
                })
            }
        },
        bottomBar = { RecordBar(capture, actions) },
    ) { padding ->
        Column(Modifier.padding(padding).readable()) {
            if (folders.isNotEmpty() || active.any { it.favorite }) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val chipColors = FilterChipDefaults.filterChipColors(iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer)
                FilterChip(filter.isEmpty(), { filter = "" }, { Text("すべて") }, colors = chipColors)
                if (active.any { it.favorite }) FilterChip(filter == FAVORITES, { filter = FAVORITES }, { Text("お気に入り") }, colors = chipColors,
                    leadingIcon = { Icon(Icons.Rounded.StarBorder, null, Modifier.size(18.dp)) })
                folders.forEach { f -> FilterChip(filter == f, { filter = f }, { Text(f) }, colors = chipColors,
                    leadingIcon = { Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp)) }) }
            }
            when {
                active.isEmpty() -> EmptyState(Icons.Rounded.Mic, "録音はまだありません")
                shown.isEmpty() -> EmptyState(Icons.Rounded.SearchOff, "見つかりませんでした")
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    shown.groupBy { dayLabel(it.createdAt) }.forEach { (day, items) ->
                        item(key = "day-$day") {
                            Text(day, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp))
                        }
                        items(items, key = { it.id }) { r -> RecordingRow(r, actions, { renaming = r }, { moving = r }) }
                    }
                }
            }
        }
    }
    renaming?.let { r -> TextDialog("名前を変更", r.title, { renaming = null }) { actions.rename(r.id, it); renaming = null } }
    moving?.let { r -> FolderDialog(r.folder, folders, { moving = null }) { actions.move(r.id, it); moving = null } }
}

private const val FAVORITES = "★"

private fun matches(r: Recording, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return listOf(r.title, r.folder, r.summary).any { it.contains(q, true) } ||
        r.segments.any { it.text.contains(q, true) || it.speaker.contains(q, true) } || r.marks.any { it.text.contains(q, true) }
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, close: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(navigationIcon = { IconButton(close) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "閉じる") } }, title = {
        TextField(query, onQuery, Modifier.fillMaxWidth().focusRequester(focus), placeholder = { Text("録音、文字起こし、マーク") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            trailingIcon = { if (query.isNotEmpty()) IconButton({ onQuery("") }) { Icon(Icons.Rounded.Close, "消去") } })
    })
}

@Composable
private fun RecordingRow(r: Recording, actions: LibraryActions, rename: () -> Unit, move: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = { actions.open(r.id) }, onLongClick = { menu = true })
            .padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(r.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${timeOfDay(r.createdAt)}  ·  ${clock(r.durationMs)}", style = MaterialTheme.typography.bodyMedium, color = muted)
                    if (r.folder.isNotBlank()) {
                        Text("  ·  ", style = MaterialTheme.typography.bodyMedium, color = muted)
                        Text(r.folder, style = MaterialTheme.typography.bodyMedium, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (r.segments.isNotEmpty()) Icon(Icons.AutoMirrored.Rounded.Notes, "文字起こしあり", Modifier.padding(start = 12.dp).size(18.dp), tint = muted)
            if (r.favorite) Icon(Icons.Rounded.Star, "お気に入り", Modifier.padding(start = 12.dp).size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
        }
        DropdownMenu(menu, { menu = false }, offset = androidx.compose.ui.unit.DpOffset(24.dp, 0.dp)) {
            DropdownMenuItem({ Text(if (r.favorite) "お気に入りから外す" else "お気に入り") }, { menu = false; actions.favorite(r.id) },
                leadingIcon = { Icon(if (r.favorite) Icons.Rounded.StarBorder else Icons.Rounded.Star, null) })
            DropdownMenuItem({ Text("名前を変更") }, { menu = false; rename() }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
            DropdownMenuItem({ Text("フォルダーに移動") }, { menu = false; move() }, leadingIcon = { Icon(Icons.Rounded.DriveFileMove, null) })
            DropdownMenuItem({ Text("削除") }, { menu = false; actions.delete(r.id) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) })
        }
    }
}

@Composable
private fun RecordBar(capture: CaptureState, actions: LibraryActions) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 8.dp, bottom = 20.dp), contentAlignment = Alignment.Center) {
        if (capture.id != null) {
            Button(actions.showRecording, shape = CircleShape, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)) {
                Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.onPrimary, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text("${if (capture.paused) "一時停止中" else "録音中"}  ${clock(capture.durationMs)}", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            LargeFloatingActionButton(actions.record, shape = CircleShape, containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary) { Icon(Icons.Rounded.Mic, "録音", Modifier.size(36.dp)) }
        }
    }
}

@Composable
fun TextDialog(title: String, initial: String, dismiss: () -> Unit, label: String? = null, confirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(dismiss, title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().focusRequester(focus), label = label?.let { { Text(it) } }, minLines = 1, maxLines = 6) },
        confirmButton = { TextButton({ confirm(text) }) { Text("保存") } }, dismissButton = { TextButton(dismiss) { Text("キャンセル") } })
}

@Composable
fun FolderDialog(current: String, folders: List<String>, dismiss: () -> Unit, choose: (String) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    if (creating) { TextDialog("新しいフォルダー", "", dismiss, "フォルダー名") { if (it.isNotBlank()) choose(it.trim()) }; return }
    AlertDialog(dismiss, title = { Text("フォルダーに移動") }, text = {
        Column {
            (listOf("") + folders).forEach { f ->
                ListItem({ Text(f.ifBlank { "なし" }) }, Modifier.combinedClickable { choose(f) },
                    leadingContent = { RadioButton(f == current, { choose(f) }) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            }
            ListItem({ Text("新しいフォルダー") }, Modifier.combinedClickable { creating = true },
                leadingContent = { Icon(Icons.Rounded.CreateNewFolder, null, Modifier.padding(12.dp)) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
        }
    }, confirmButton = { TextButton(dismiss) { Text("キャンセル") } })
}
