package org.openrecorder.app.core

import java.util.Locale

object Text {
    private fun cjk(c: Char) = Character.UnicodeScript.of(c.code).let {
        it == Character.UnicodeScript.HAN || it == Character.UnicodeScript.HIRAGANA || it == Character.UnicodeScript.KATAKANA
    } || c in '　'..'〿' || c in '＀'..'￯' || c == 'ー'

    /**
     * Vosk returns Japanese and Chinese as space-separated words ("今日 は 晴れ").
     * Removes spaces that touch a CJK character while keeping spaces between Latin words.
     */
    fun joinTokens(text: String): String {
        val words = text.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        if (words.isEmpty()) return ""
        return buildString {
            append(words[0])
            for (w in words.drop(1)) { if (!cjk(last()) && !cjk(w.first())) append(' '); append(w) }
        }
    }

    fun mostlyCjk(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        return letters.isNotEmpty() && letters.count(::cjk) * 3 >= letters.length
    }
}

fun vttTime(ms: Long): String = "%02d:%02d:%02d.%03d".format(Locale.ROOT, ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000)

/** WebVTT captions; speakers use the standard voice span. */
fun webVtt(segments: List<Segment>): String = "WEBVTT\n\n" + segments.joinToString("\n\n") { s ->
    val text = s.text.replace("\r", "").replace("\n", " ").replace("-->", "→")
    val speaker = s.speaker.replace(Regex("[\\r\\n<>&]"), " ").trim()
    "${vttTime(s.startMs)} --> ${vttTime(s.endMs)}\n" + (if (speaker.isBlank()) "" else "<v $speaker>") + text
} + "\n"
