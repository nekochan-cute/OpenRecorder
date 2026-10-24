@file:OptIn(ExperimentalMaterial3Api::class)
package org.openrecorder.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openrecorder.app.ai.DownloadState
import org.openrecorder.app.ai.ModelKind
import org.openrecorder.app.ai.ModelSpec
import org.openrecorder.app.ui.icons.*
import java.util.Locale

/** What the models screen needs to know about one model. */
data class ModelRow(val spec: ModelSpec, val installed: Boolean, val active: Boolean, val download: DownloadState?)

class ModelActions(
    val back: () -> Unit = {}, val download: (ModelSpec) -> Unit = {}, val cancel: (ModelSpec) -> Unit = {},
    val delete: (ModelSpec) -> Unit = {}, val choose: (ModelSpec) -> Unit = {},
)

@Composable
fun ModelsScreen(rows: List<ModelRow>, actions: ModelActions) {
    var deleting by remember { mutableStateOf<ModelSpec?>(null) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("モデル") }, navigationIcon = { IconButton(actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "戻る") } })
    }) { padding ->
        Column(Modifier.padding(padding).readable().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            fun section(kind: ModelKind) = rows.filter { it.spec.kind == kind }
            SectionHeader("文字起こし")
            section(ModelKind.Speech).forEach { ModelItem(it, actions, chooseable = true) { deleting = it.spec } }
            SectionHeader("話し声の検出・話者")
            (section(ModelKind.Vad) + section(ModelKind.Speaker)).forEach { ModelItem(it, actions, chooseable = false) { deleting = it.spec } }
        }
    }
    deleting?.let { spec ->
        AlertDialog({ deleting = null }, title = { Text("${spec.title}を削除しますか？") }, text = { Text("もう一度使うときは再ダウンロードが必要です。") },
            confirmButton = { TextButton({ actions.delete(spec); deleting = null }) { Text("削除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ deleting = null }) { Text("キャンセル") } })
    }
}

@Composable
private fun ModelItem(row: ModelRow, actions: ModelActions, chooseable: Boolean, delete: () -> Unit) {
    val spec = row.spec
    val d = row.download
    ListItem(
        headlineContent = { Text(spec.title) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(listOf(spec.detail, size(spec.size).takeIf { spec.size > 0 }).filterNotNull().joinToString("  ·  "), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (d != null && d.error == null) LinearProgressIndicator({ d.progress }, Modifier.fillMaxWidth())
                d?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        leadingContent = {
            if (chooseable) RadioButton(row.active, { actions.choose(spec) }, enabled = row.installed)
            else Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (row.installed) Icon(Icons.Rounded.CheckCircle, "ダウンロード済み", tint = MaterialTheme.colorScheme.primary)
            }
        },
        trailingContent = {
            when {
                d != null && d.error == null -> IconButton({ actions.cancel(spec) }) { Icon(Icons.Rounded.Close, "中止") }
                row.installed -> IconButton(delete) { Icon(Icons.Rounded.Delete, "削除") }
                else -> IconButton({ actions.download(spec) }) { Icon(Icons.Rounded.Download, "ダウンロード") }
            }
        },
        modifier = Modifier.clickable(enabled = row.installed && chooseable) { actions.choose(spec) }.padding(horizontal = 8.dp),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

private fun size(bytes: Long) = if (bytes >= 1_000_000_000) "%.1f GB".format(Locale.ROOT, bytes / 1e9) else "%d MB".format(Locale.ROOT, bytes / 1_000_000)
