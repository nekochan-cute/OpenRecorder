package org.openrecorder.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import org.openrecorder.app.core.Levels
import kotlin.math.max
import kotlin.math.roundToInt

/** Level source at [Levels.PER_SECOND]: index 0 is the start of the audio. */
class LevelTrack(val count: Int, private val first: Int = 0, private val read: (Int) -> Float) {
    operator fun get(i: Int): Float = if (i < first || i >= count) 0f else read(i)
    companion object {
        fun of(bytes: ByteArray?) = bytes?.let { b -> LevelTrack(b.size) { (b[it].toInt() and 255) / 255f } } ?: LevelTrack(0) { 0f }
        /** The newest [recent] levels of an ongoing recording that has [count] levels in total. */
        fun tail(recent: List<Float>, count: Int) = LevelTrack(count, count - recent.size) { recent[it - (count - recent.size)] }
    }
}

private const val MS_PER_LEVEL = 1000 / Levels.PER_SECOND

/**
 * Playhead fixed in the centre, audio scrolls underneath. Drag to scrub (seek on release),
 * tap a point to jump there. Marks are drawn as flags.
 */
@Composable
fun ScrollingWaveform(track: LevelTrack, positionMs: Long, durationMs: Long, marks: List<Long>, modifier: Modifier = Modifier,
                      onSeek: ((Long) -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val step = with(LocalDensity.current) { 4.dp.toPx() }
    val bar = with(LocalDensity.current) { 2.5.dp.toPx() }
    var scrub by remember { mutableStateOf<Long?>(null) }
    val shown = scrub ?: positionMs
    val pxPerMs = step / MS_PER_LEVEL
    Canvas(modifier.semantics { contentDescription = "波形" }
        .pointerInput(onSeek, durationMs) {
            if (onSeek != null) detectHorizontalDragGestures(
                onDragStart = { scrub = positionMs },
                onDragEnd = { scrub?.let(onSeek); scrub = null },
                onDragCancel = { scrub = null },
            ) { change, dx -> change.consume(); scrub = ((scrub ?: positionMs) - (dx / pxPerMs).toLong()).coerceIn(0, durationMs) }
        }
        .pointerInput(onSeek, durationMs, positionMs) {
            if (onSeek != null) detectTapGestures { o -> onSeek((positionMs + ((o.x - size.width / 2) / pxPerMs).toLong()).coerceIn(0, durationMs)) }
        }) {
        val ruler = 22.dp.toPx()
        val mid = ruler + (size.height - ruler) / 2
        val half = (size.height - ruler) / 2 * 0.9f
        val center = size.width / 2
        val origin = center - shown * pxPerMs
        val firstIndex = max(0, ((-origin) / step).toInt() - 1)
        val lastIndex = minOf(track.count - 1, ((size.width - origin) / step).toInt() + 1)
        for (i in firstIndex..lastIndex) {
            val x = origin + i * step
            val h = max(1.5.dp.toPx(), track[i] * half)
            drawRoundRect(if (x <= center) colors.onSurface else colors.outline, Offset(x, mid - h), Size(bar, h * 2), CornerRadius(bar / 2))
        }
        ruler(measurer, labelStyle, origin, pxPerMs, durationMs, ruler, colors.outlineVariant)
        marks.forEach { m -> flag(origin + m * pxPerMs, ruler, size.height, colors.tertiary) }
        drawLine(colors.primary, Offset(center, ruler - 4.dp.toPx()), Offset(center, size.height), 2.dp.toPx())
        drawCircle(colors.primary, 4.dp.toPx(), Offset(center, ruler - 4.dp.toPx()))
    }
}

private fun DrawScope.ruler(measurer: TextMeasurer, style: androidx.compose.ui.text.TextStyle, origin: Float, pxPerMs: Float, durationMs: Long, height: Float, tick: androidx.compose.ui.graphics.Color) {
    val firstSecond = max(0, (-origin / pxPerMs / 1000).toInt())
    val lastSecond = ((size.width - origin) / pxPerMs / 1000).toInt() + 1
    for (s in firstSecond..lastSecond) {
        if (s * 1000L > durationMs + 1000) break
        val x = origin + s * 1000 * pxPerMs
        val major = s % 2 == 0
        drawLine(tick, Offset(x, height - (if (major) 8 else 4).dp.toPx()), Offset(x, height), 1.dp.toPx())
        if (major) {
            val text = "%02d:%02d".format(java.util.Locale.ROOT, s / 60, s % 60)
            drawText(measurer, text, Offset(x + 3.dp.toPx(), 0f), style)
        }
    }
}

private fun DrawScope.flag(x: Float, top: Float, bottom: Float, color: androidx.compose.ui.graphics.Color) {
    if (x < -20 || x > size.width + 20) return
    drawLine(color, Offset(x, top), Offset(x, bottom), 1.5.dp.toPx())
    val path = Path().apply {
        moveTo(x, top); lineTo(x + 10.dp.toPx(), top + 4.dp.toPx()); lineTo(x, top + 8.dp.toPx()); close()
    }
    drawPath(path, color)
}

/**
 * Whole recording fitted to the width with a draggable selection, for trimming.
 * Drag a handle to resize the selection; tap elsewhere to move the playhead.
 */
@Composable
fun SelectionWaveform(track: LevelTrack, durationMs: Long, start: Long, end: Long, positionMs: Long, modifier: Modifier = Modifier,
                      onSelection: (Long, Long) -> Unit, onSeek: (Long) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val minLength = 500L
    val currentStart by rememberUpdatedState(start); val currentEnd by rememberUpdatedState(end)
    var dragging by remember { mutableIntStateOf(0) } // -1 start, 1 end
    Canvas(modifier.semantics { contentDescription = "選択範囲" }
        .pointerInput(durationMs) {
            fun at(x: Float) = (x / size.width * durationMs).toLong().coerceIn(0, durationMs)
            detectHorizontalDragGestures(
                onDragStart = { o ->
                    val s = currentStart.toFloat() / durationMs * size.width; val e = currentEnd.toFloat() / durationMs * size.width
                    dragging = if (kotlin.math.abs(o.x - s) < kotlin.math.abs(o.x - e)) -1 else 1
                },
                onDragEnd = { dragging = 0 }, onDragCancel = { dragging = 0 },
            ) { change, _ ->
                change.consume()
                val t = at(change.position.x)
                if (dragging < 0) onSelection(t.coerceAtMost(currentEnd - minLength).coerceAtLeast(0), currentEnd)
                else onSelection(currentStart, t.coerceAtLeast(currentStart + minLength).coerceAtMost(durationMs))
            }
        }
        .pointerInput(durationMs) { detectTapGestures { o -> onSeek((o.x / size.width * durationMs).toLong().coerceIn(0, durationMs)) } }) {
        val bars = (size.width / 4.dp.toPx()).roundToInt().coerceAtLeast(1)
        val barWidth = size.width / bars
        val mid = size.height / 2
        val sx = start.toFloat() / durationMs * size.width; val ex = end.toFloat() / durationMs * size.width
        drawRoundRect(colors.primary.copy(alpha = 0.10f), Offset(sx, 0f), Size(ex - sx, size.height), CornerRadius(8.dp.toPx()))
        for (b in 0 until bars) {
            val from = (b.toLong() * track.count / bars).toInt(); val to = max(from + 1, ((b + 1).toLong() * track.count / bars).toInt())
            var level = 0f
            for (i in from until to) level = max(level, track[i])
            val x = b * barWidth
            val h = max(1.5.dp.toPx(), level * mid * 0.85f)
            val inside = x + barWidth / 2 in sx..ex
            drawRoundRect(if (inside) colors.onSurface else colors.outlineVariant, Offset(x + barWidth * 0.2f, mid - h), Size(barWidth * 0.6f, h * 2), CornerRadius(barWidth * 0.3f))
        }
        for (x in listOf(sx, ex)) {
            drawLine(colors.primary, Offset(x, 0f), Offset(x, size.height), 2.5.dp.toPx())
            drawRoundRect(colors.primary, Offset(x - 7.dp.toPx(), mid - 18.dp.toPx()), Size(14.dp.toPx(), 36.dp.toPx()), CornerRadius(7.dp.toPx()))
        }
        val px = positionMs.toFloat() / durationMs * size.width
        drawLine(colors.onSurfaceVariant, Offset(px, 0f), Offset(px, size.height), 1.5.dp.toPx())
    }
}

/** Thin seek bar: drag or tap anywhere; seeks on release so playback does not stutter. */
@Composable
fun SeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    var drag by remember { mutableStateOf<Float?>(null) }
    val fraction = drag ?: (positionMs.toFloat() / durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
    Canvas(modifier.semantics { contentDescription = "再生位置" }
        .pointerInput(durationMs) { detectTapGestures { o -> onSeek((o.x / size.width * durationMs).toLong().coerceIn(0, durationMs)) } }
        .pointerInput(durationMs) {
            detectHorizontalDragGestures(onDragStart = { drag = (it.x / size.width).coerceIn(0f, 1f) },
                onDragEnd = { drag?.let { onSeek((it * durationMs).toLong()) }; drag = null }, onDragCancel = { drag = null }) { change, _ ->
                change.consume(); drag = (change.position.x / size.width).coerceIn(0f, 1f)
            }
        }) {
        val y = size.height / 2; val h = 4.dp.toPx()
        drawRoundRect(colors.outlineVariant, Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2))
        drawRoundRect(colors.primary, Offset(0f, y - h / 2), Size(size.width * fraction, h), CornerRadius(h / 2))
        drawCircle(colors.primary, (if (drag != null) 9 else 7).dp.toPx(), Offset(size.width * fraction, y))
    }
}

/** Thin value slider matching [SeekBar]; for settings such as strength or zoom. */
@Composable
fun ThinSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier, range: ClosedFloatingPointRange<Float> = 0f..1f) {
    val colors = MaterialTheme.colorScheme
    val span = range.endInclusive - range.start
    fun at(x: Float, width: Int) = (range.start + (x / width).coerceIn(0f, 1f) * span)
    Canvas(modifier.height(32.dp).semantics { contentDescription = "%.0f%%".format(java.util.Locale.ROOT, (value - range.start) / span * 100) }
        .pointerInput(range) { detectTapGestures { onChange(at(it.x, size.width)) } }
        .pointerInput(range) { detectHorizontalDragGestures { change, _ -> change.consume(); onChange(at(change.position.x, size.width)) } }) {
        val y = size.height / 2; val h = 4.dp.toPx(); val f = ((value - range.start) / span).coerceIn(0f, 1f)
        drawRoundRect(colors.outlineVariant, Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2))
        drawRoundRect(colors.primary, Offset(0f, y - h / 2), Size(size.width * f, h), CornerRadius(h / 2))
        drawCircle(colors.primary, 8.dp.toPx(), Offset(size.width * f, y))
    }
}
