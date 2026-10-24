package org.openrecorder.app.core

import java.util.UUID

data class Mark(val id: String = UUID.randomUUID().toString(), val timeMs: Long, val text: String, val photo: String? = null)
data class Segment(val startMs: Long, val endMs: Long, val text: String, val speaker: String = "", val translation: String = "")
data class Recording(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val fileName: String,
    val durationMs: Long = 0,
    val sampleRate: Int = 48000,
    val channels: Int = 1,
    val folder: String = "",
    val favorite: Boolean = false,
    val deletedAt: Long? = null,
    val marks: List<Mark> = emptyList(),
    val segments: List<Segment> = emptyList(),
    val summary: String = "",
    /** Language code of [Segment.translation], empty when the transcript has not been translated. */
    val translationLanguage: String = "",
    val sourceId: String? = null,
    val unfinished: Boolean = false,
    val peaks: List<Float> = emptyList()
)

data class Span(val startMs: Long, val endMs: Long) {
    init { require(startMs >= 0 && endMs > startMs) }
    val durationMs get() = endMs - startMs
}

/** Source-timeline to output-timeline mapping for trims and interval deletion. */
object Timeline {
    fun validate(spans: List<Span>, durationMs: Long): List<Span> {
        require(spans.isNotEmpty()) { "音声がすべて削除されます" }
        var lastEnd = 0L
        spans.forEach { require(it.startMs >= lastEnd && it.endMs <= durationMs); lastEnd = it.endMs }
        return spans
    }
    fun position(sourceMs: Long, spans: List<Span>): Long? {
        var offset = 0L
        for (span in spans) {
            if (sourceMs >= span.startMs && sourceMs < span.endMs) return offset + sourceMs - span.startMs
            offset += span.durationMs
        }
        return null
    }
    fun marks(marks: List<Mark>, spans: List<Span>) = marks.mapNotNull { m ->
        position(m.timeMs, spans)?.let { m.copy(timeMs = it) }
    }
    fun segments(segments: List<Segment>, spans: List<Span>): List<Segment> {
        // A clipped sentence must be re-transcribed; retaining it would misrepresent the edit.
        return segments.mapNotNull { s ->
            spans.firstOrNull { s.startMs >= it.startMs && s.endMs <= it.endMs }?.let {
                val start = position(s.startMs, spans) ?: return@mapNotNull null
                s.copy(startMs = start, endMs = start + s.endMs - s.startMs)
            }
        }
    }
    fun remove(start: Long, end: Long, duration: Long): List<Span> {
        require(start >= 0 && end > start && end <= duration)
        return validate(buildList {
            if (start > 0) add(Span(0, start))
            if (end < duration) add(Span(end, duration))
        }, duration)
    }
}

fun timeLabel(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%02d:%02d".format(seconds / 60, seconds % 60)
}

fun subtitleTime(ms: Long): String = "%02d:%02d:%02d,%03d".format(java.util.Locale.ROOT,
    ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000)

fun subtitles(segments: List<Segment>): String = segments.mapIndexed { i, s ->
    "${i + 1}\n${subtitleTime(s.startMs)} --> ${subtitleTime(s.endMs)}\n" +
        (if (s.speaker.isBlank()) "" else "[${s.speaker.replace("\r", "").replace("\n", " ")}] ") + s.text.replace("\r", "").replace("\n", " ")
}.joinToString("\n\n") + "\n"
