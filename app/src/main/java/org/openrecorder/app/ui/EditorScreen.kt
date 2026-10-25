@file:OptIn(ExperimentalMaterial3Api::class)
package org.openrecorder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import org.openrecorder.app.ui.icons.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.openrecorder.app.core.Recording

class EditorActions(
    val close: () -> Unit = {}, val playRange: (Long, Long) -> Unit = { _, _ -> }, val pause: () -> Unit = {}, val seek: (Long) -> Unit = {},
    val save: (start: Long, end: Long, remove: Boolean) -> Unit = { _, _, _ -> },
)

/** Trim (keep the selection) or cut (remove it). The original stays; the result is a new recording. */
@Composable
fun EditorScreen(r: Recording, levels: ByteArray?, position: Long, playing: Boolean, busy: Boolean, actions: EditorActions) {
    val d = r.durationMs.coerceAtLeast(1)
    var start by remember(r.id) { mutableLongStateOf(d / 4) }
    var end by remember(r.id) { mutableLongStateOf(d * 3 / 4) }
    val gap = minOf(500L, d / 2) // shortest selection; never more than half, so very short recordings keep valid bounds
    Scaffold(topBar = {
        TopAppBar(title = { Text("切り取り") }, navigationIcon = { IconButton(actions.close) { Icon(Icons.Rounded.Close, "閉じる") } })
    }, bottomBar = {
        Row(Modifier.navigationBarsPadding().readable().padding(horizontal = 20.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton({ actions.save(start, end, true) }, Modifier.weight(1f).height(52.dp), enabled = !busy && !(start == 0L && end == d)) { Text("選択部分を削除") }
            Button({ actions.save(start, end, false) }, Modifier.weight(1f).height(52.dp), enabled = !busy && !(start == 0L && end == d)) { Text("選択部分だけ残す") }
        }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().readable(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(32.dp))
            Text("${clock(start)} – ${clock(end)}", style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"))
            Text(clock(end - start), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(40.dp))
            SelectionWaveform(LevelTrack.of(levels), d, start, end, position, Modifier.fillMaxWidth().height(160.dp).padding(horizontal = 20.dp),
                onSelection = { s, e -> start = s; end = e }, onSeek = actions.seek)
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("00:00", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.weight(1f))
                Text(clock(d), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedIconButton({ start = position.coerceAtMost(end - gap).coerceAtLeast(0) }) { Icon(Icons.Rounded.FirstPage, "再生位置を開始点に") }
                FilledIconButton({ if (playing) actions.pause() else actions.playRange(start, end) }, Modifier.size(64.dp)) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "一時停止" else "選択部分を再生", Modifier.size(32.dp))
                }
                OutlinedIconButton({ end = position.coerceAtLeast(start + gap).coerceAtMost(d) }) { Icon(Icons.Rounded.LastPage, "再生位置を終了点に") }
            }
        }
    }
}
