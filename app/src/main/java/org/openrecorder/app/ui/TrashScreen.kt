@file:OptIn(ExperimentalMaterial3Api::class)
package org.openrecorder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import org.openrecorder.app.ui.icons.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openrecorder.app.core.Recording

@Composable
fun TrashScreen(recordings: List<Recording>, back: () -> Unit, restore: (String) -> Unit, delete: (String) -> Unit, empty: () -> Unit) {
    val trashed = recordings.filter { it.deletedAt != null && !it.unfinished }.sortedByDescending { it.deletedAt }
    var confirm by remember { mutableStateOf<String?>(null) } // id, or "" for all
    Scaffold(topBar = {
        TopAppBar(title = { Text("ごみ箱") }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "戻る") } },
            actions = { if (trashed.isNotEmpty()) TextButton({ confirm = "" }) { Text("空にする") } })
    }) { padding ->
        if (trashed.isEmpty()) EmptyState(Icons.Rounded.Delete, "ごみ箱は空です", Modifier.padding(padding))
        else LazyColumn(Modifier.padding(padding).readable()) {
            items(trashed, key = { it.id }) { r ->
                ListItem({ Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, Modifier.padding(horizontal = 8.dp),
                    supportingContent = { Text("${dayLabel(r.createdAt)}  ·  ${clock(r.durationMs)}") },
                    trailingContent = {
                        Row {
                            IconButton({ restore(r.id) }) { Icon(Icons.Rounded.Restore, "元に戻す") }
                            IconButton({ confirm = r.id }) { Icon(Icons.Rounded.DeleteForever, "完全に削除") }
                        }
                    }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            }
        }
    }
    confirm?.let { id ->
        val title = if (id.isEmpty()) "ごみ箱を空にしますか？" else "完全に削除しますか？"
        AlertDialog({ confirm = null }, title = { Text(title) }, text = { Text("元に戻せません。") },
            confirmButton = { TextButton({ if (id.isEmpty()) empty() else delete(id); confirm = null }) { Text("削除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ confirm = null }) { Text("キャンセル") } })
    }
}
