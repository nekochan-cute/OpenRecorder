@file:OptIn(ExperimentalMaterial3Api::class)
package org.openrecorder.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import org.openrecorder.app.ui.icons.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openrecorder.app.audio.CaptureState
import org.openrecorder.app.core.Levels
import org.openrecorder.app.core.Recording
import java.util.Locale

class RecordActions(val pause: () -> Unit = {}, val stop: () -> Unit = {}, val mark: () -> Unit = {}, val photo: () -> Unit = {}, val back: () -> Unit = {})

@Composable
fun RecordScreen(capture: CaptureState, recording: Recording?, actions: RecordActions) {
    val colors = MaterialTheme.colorScheme
    Scaffold(topBar = {
        TopAppBar(title = {}, navigationIcon = { IconButton(actions.back) { Icon(Icons.Rounded.KeyboardArrowDown, "最小化") } },
            actions = { IconButton(actions.photo, enabled = capture.id != null && !capture.saving) { Icon(Icons.Rounded.AddAPhoto, "写真を追加") } })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().readable(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(recording?.title.orEmpty(), style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(clock(capture.durationMs), style = TimerStyle)
                Text(".%d".format(Locale.ROOT, capture.durationMs / 100 % 10), style = TimerStyle.copy(fontSize = 32.sp), color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp))
            }
            Status(capture)
            Spacer(Modifier.height(28.dp))
            val total = (capture.durationMs / (1000 / Levels.PER_SECOND)).toInt()
            ScrollingWaveform(LevelTrack.tail(capture.levels, total), capture.durationMs, capture.durationMs,
                recording?.marks?.map { it.timeMs }.orEmpty(), Modifier.fillMaxWidth().height(200.dp))
            LiveCaptions(capture, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp))
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 32.dp), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                val enabled = capture.id != null && !capture.saving
                RoundAction(Icons.Rounded.Flag, "マーク", enabled, badge = recording?.marks?.size ?: 0, onClick = actions.mark)
                FilledIconButton(actions.pause, Modifier.size(84.dp), enabled = enabled, shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.primary, contentColor = colors.onPrimary)) {
                    Icon(if (capture.paused) Icons.Rounded.FiberManualRecord else Icons.Rounded.Pause, if (capture.paused) "再開" else "一時停止", Modifier.size(40.dp))
                }
                RoundAction(Icons.Rounded.Stop, "停止", enabled, onClick = actions.stop)
            }
        }
    }
}

@Composable
private fun Status(capture: CaptureState) {
    val colors = MaterialTheme.colorScheme
    val blink by rememberInfiniteTransition(label = "rec").animateFloat(1f, 0.25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dot")
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).alpha(if (capture.paused || capture.saving) 1f else blink)
                .background(if (capture.paused) colors.onSurfaceVariant else colors.primary, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(when { capture.saving -> "保存中"; capture.paused -> "一時停止中"; else -> "録音中" }, style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant)
        }
        if (capture.silenced) Text("他のアプリがマイクを使用中です", style = MaterialTheme.typography.labelMedium, color = colors.error, textAlign = TextAlign.Center)
        else if (capture.input.isNotBlank()) Text(listOfNotNull(capture.input, when (capture.directional) { true -> "指向性オン"; false -> "指向性は非対応"; null -> null })
            .joinToString("  ·  "), style = MaterialTheme.typography.labelMedium, color = colors.outline)
    }
}

@Composable
fun RoundAction(icon: ImageVector, label: String, enabled: Boolean = true, badge: Int = 0, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BadgedBox(badge = { if (badge > 0) Badge { Text("$badge") } }) {
            FilledTonalIconButton(onClick, Modifier.size(60.dp), enabled = enabled, shape = CircleShape,
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Icon(icon, null, Modifier.size(26.dp))
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * All captions so far, newest at the bottom, with speaker colours matching the transcript. The text being
 * spoken is shown provisionally and replaced in place by its final result. The list follows new text
 * unless the user scrolls back to read; scrolling to the end or the button resumes following.
 */
@Composable
private fun LiveCaptions(capture: CaptureState, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val dark = isDark
    val speakers = capture.live.map { it.speaker }.filter(String::isNotBlank).distinct()
    val list = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect {
            when (it) {
                is DragInteraction.Start -> follow = false
                is DragInteraction.Stop, is DragInteraction.Cancel -> follow = !list.canScrollForward
            }
        }
    }
    LaunchedEffect(list) { snapshotFlow { list.canScrollForward }.collect { if (!it) follow = true } }
    LaunchedEffect(capture.live.size, capture.partial, capture.speaking, follow) {
        if (follow) { withFrameNanos {}; list.scrollToEnd() }
    }
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), list, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom)) {
                itemsIndexed(capture.live, key = { i, _ -> i }) { i, s ->
                    Column(Modifier.animateItem()) {
                        if (s.speaker.isNotBlank()) Text(s.speaker, style = MaterialTheme.typography.labelMedium, color = speakerColor(speakers.indexOf(s.speaker), dark))
                        Text(s.text, style = MaterialTheme.typography.bodyLarge, color = if (i == capture.live.lastIndex) colors.onSurface else colors.onSurfaceVariant)
                    }
                }
                // Keyed like the caption it turns into, so the final text takes its place without a jump.
                if (capture.partial.isNotBlank() || capture.speaking) item(key = capture.live.size) {
                    if (capture.partial.isBlank()) Text("…", Modifier.animateItem(), style = MaterialTheme.typography.titleLarge, color = colors.outline)
                    else Text(capture.partial, Modifier.animateItem(), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface.copy(alpha = 0.6f))
                }
            }
            if (!follow) SmallFloatingActionButton({ follow = true }, Modifier.align(Alignment.BottomEnd)) {
                Icon(Icons.Rounded.KeyboardArrowDown, "最新の文字起こしへ")
            }
        }
        if (capture.liveStatus.isNotBlank()) Text(capture.liveStatus, style = MaterialTheme.typography.labelMedium, color = colors.error)
    }
}

/** Scrolls to the very end of the list; the last stretch is animated. */
private suspend fun LazyListState.scrollToEnd() {
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return
    if (layoutInfo.visibleItemsInfo.none { it.index == lastIndex }) scrollToItem(lastIndex)
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull { it.index == lastIndex } ?: return
    (last.offset + last.size - info.viewportEndOffset).takeIf { it > 0 }?.let { animateScrollBy(it.toFloat()) }
}
