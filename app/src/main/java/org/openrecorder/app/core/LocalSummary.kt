package org.openrecorder.app.core

import java.util.Locale
import kotlin.math.ln

/**
 * On-device extractive summary. Nothing is generated: every bullet is a sentence taken from the
 * transcript, ranked with TextRank and keyword weight, plus rule-based picks for decisions,
 * action items, key points and interview questions. Works offline for Japanese and English.
 */
object LocalSummary {
    data class Result(val title: String, val body: String)
    private data class Sentence(val index: Int, val text: String, val speaker: String, val startMs: Long, val tokens: Set<String>)

    private val decision = Regex("決定|決まり|決めま|決めた|合意|承認|確定|採用|にしましょう|にします|とします|ことにな|でいきま|で行きま|方針|\\b(decided|agreed|approved|final decision|let's go with|we will go with|settled on|conclusion)\\b", RegexOption.IGNORE_CASE)
    private val action = Regex("お願いします|お願いでき|お願いね|対応します|対応して|確認します|確認して|調べ|準備します|送ります|共有します|連絡します|作成します|やっておき|までに|担当|宿題|タスク|\\b(action item|todo|to-do|follow up|will send|will share|will check|need to|needs to|i'll|we'll|by (monday|tuesday|wednesday|thursday|friday|tomorrow|next week))\\b", RegexOption.IGNORE_CASE)
    private val keyPoint = Regex("重要|大事|ポイント|つまり|要するに|まとめると|結論|定義|とは|覚えて|注意|\\b(important|key point|in summary|to summarize|remember|definition|means that|in other words)\\b", RegexOption.IGNORE_CASE)
    private val question = Regex("[?？]$|(ですか|ますか|でしょうか|ませんか|のか|んですか)[。.]?$|^(what|why|how|when|where|who|which|do|does|did|can|could|would|is|are)\\b", RegexOption.IGNORE_CASE)
    private val stopJa = setOf("場合", "部分", "今回", "感じ", "自分", "本当", "一応", "全然", "結構", "大体", "時間", "最初", "最後", "以上", "以下", "問題", "必要", "可能", "状況", "関係", "意味", "大丈夫", "ちょっと")
    private val stopEn = setOf("the", "and", "that", "this", "with", "have", "for", "you", "are", "was", "were", "but", "not", "they", "there", "what", "about", "would", "could", "should", "just", "like", "really", "think", "know", "yeah", "okay", "right", "going", "gonna", "then", "than", "them", "their", "from", "will", "your", "which", "when", "been", "into", "also", "some", "can", "our", "its", "it's", "i'm", "we're", "that's", "don't", "very", "much", "more", "here", "well", "because", "actually", "something", "things", "thing")

    fun summarize(segments: List<Segment>, template: String): Result {
        val sentences = split(segments)
        require(sentences.isNotEmpty()) { "要約できる文章がありません" }
        val ja = Text.mostlyCjk(sentences.joinToString("") { it.text })
        val frequency = HashMap<String, Int>()
        sentences.forEach { s -> s.tokens.forEach { frequency[it] = (frequency[it] ?: 0) + 1 } }
        val keywords = frequency.entries.filter { it.value >= 2 || frequency.size < 8 }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value * ln(1.0 + it.key.length) }.thenBy { it.key })
            .map { it.key }.let(::dropContained).take(6)
        val scores = rank(sentences, frequency)
        val candidates = sentences.filter { it.text.length >= (if (ja) 8 else 25) }.ifEmpty { sentences }
        val overviewCount = (sentences.size / 8).coerceIn(3, 7)
        val overview = candidates.sortedByDescending { scores[it.index] }.take(overviewCount).sortedBy { it.index }
        fun pick(rule: Regex, limit: Int) = sentences.filter { rule.containsMatchIn(it.text) }
            .sortedByDescending { scores[it.index] }.take(limit).sortedBy { it.index }
        val h = if (ja) Headings.Ja else Headings.En
        val body = buildString {
            section(h.overview, overview.map { it.text })
            when (template) {
                "会議", "meeting" -> {
                    section(h.decisions, pick(decision, 10).map { it.text }, h.none)
                    section(h.actions, pick(action, 10).map { "[ ] " + withSpeaker(it) }, h.none)
                }
                "講義", "lecture" -> section(h.keyPoints, (pick(keyPoint, 8) + overview).distinct().sortedBy { it.index }.take(10).map { it.text })
                "インタビュー", "interview" -> section(h.questions, questions(sentences, 8), h.none)
                else -> section(h.keyPoints, pick(keyPoint, 6).map { it.text }, h.none)
            }
            if (keywords.isNotEmpty()) append("## ${h.keywords}\n\n${keywords.joinToString(if (ja) "、" else ", ")}\n\n")
            append("> ${h.note}\n")
        }
        return Result(title(keywords, overview.firstOrNull()?.text ?: sentences.first().text, template, ja), body.trim() + "\n")
    }

    fun title(keywords: List<String>, fallback: String, template: String, ja: Boolean): String {
        val kind = when (template) {
            "会議", "meeting" -> if (ja) "会議" else "Meeting"
            "講義", "lecture" -> if (ja) "講義" else "Lecture"
            "インタビュー", "interview" -> if (ja) "インタビュー" else "Interview"
            else -> ""
        }
        val topic = keywords.take(3).joinToString(if (ja) "・" else ", ").ifBlank { fallback.take(30).trim() }
        return if (kind.isBlank()) topic else if (ja) "$topic（$kind）" else "$kind: $topic"
    }

    private enum class Headings(val overview: String, val decisions: String, val actions: String, val keyPoints: String, val questions: String, val keywords: String, val none: String, val note: String) {
        Ja("概要", "決定事項", "作業項目", "重要なポイント", "質問と回答", "キーワード", "該当する発言は見つかりませんでした。",
            "端末内で原文から文を抜き出した要約です。内容は音声・全文と照合してください。"),
        En("Overview", "Decisions", "Action items", "Key points", "Questions and answers", "Keywords", "No matching statements were found.",
            "Extractive on-device summary: sentences are quoted from the transcript. Check them against the audio.")
    }

    private fun StringBuilder.section(title: String, lines: List<String>, empty: String? = null) {
        if (lines.isEmpty() && empty == null) return
        append("## $title\n\n")
        if (lines.isEmpty()) append(empty).append("\n\n") else { lines.forEach { append("- ").append(it).append('\n') }; append('\n') }
    }

    private fun withSpeaker(s: Sentence) = if (s.speaker.isBlank()) s.text else "${s.speaker}: ${s.text}"

    private fun questions(sentences: List<Sentence>, limit: Int): List<String> = sentences.filter { question.containsMatchIn(it.text) }.take(limit).map { q ->
        val answer = sentences.drop(q.index + 1).take(3).filter { q.speaker.isBlank() || it.speaker != q.speaker }.firstOrNull()
            ?: sentences.getOrNull(q.index + 1)
        "Q. ${withSpeaker(q)}" + (answer?.let { "\n  A. ${withSpeaker(it)}" } ?: "")
    }

    private fun split(segments: List<Segment>): List<Sentence> {
        val out = mutableListOf<Sentence>()
        for (seg in segments) for (part in seg.text.split(Regex("(?<=[。！？!?])|(?<=[.])\\s+|\\n"))) {
            val text = part.trim()
            if (text.length >= 2) out += Sentence(out.size, text, seg.speaker, seg.startMs, tokens(text))
        }
        return out
    }

    fun tokens(text: String): Set<String> {
        val out = HashSet<String>()
        Regex("[\\p{IsHan}\\p{IsKatakana}ー]{2,}").findAll(text).forEach { m ->
            val t = m.value.trim('ー')
            if (t.length >= 2 && t !in stopJa) out += t
        }
        Regex("[A-Za-z][A-Za-z0-9'\\-]+").findAll(text).forEach { m ->
            val t = m.value.lowercase(Locale.ROOT)
            if ((t.length >= 3 || m.value.all { it.isUpperCase() }) && t !in stopEn) out += t
        }
        return out
    }

    /** "予算" is dropped when "予算案" is also a keyword, to avoid repeating the same topic. */
    private fun dropContained(words: List<String>) = words.filterNot { w -> words.any { it != w && it.contains(w) && it.length <= w.length + 2 } }

    /** TextRank with overlap similarity; very long transcripts fall back to keyword weight to stay linear. */
    private fun rank(sentences: List<Sentence>, frequency: Map<String, Int>): DoubleArray {
        val n = sentences.size
        val keywordScore = DoubleArray(n) { i -> sentences[i].tokens.sumOf { ln(1.0 + (frequency[it] ?: 0)) } / ln(2.0 + sentences[i].tokens.size) }
        if (n > 600 || n < 3) return keywordScore
        val weights = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in i + 1 until n) {
            val a = sentences[i].tokens; val b = sentences[j].tokens
            if (a.isEmpty() || b.isEmpty()) continue
            val common = a.count { it in b }
            if (common > 0) { val w = common / (ln(1.0 + a.size) + ln(1.0 + b.size)); weights[i][j] = w; weights[j][i] = w }
        }
        val out = DoubleArray(n) { i -> weights[i].sum() }
        var score = DoubleArray(n) { 1.0 }
        repeat(30) {
            score = DoubleArray(n) { i ->
                var s = 0.0
                for (j in 0 until n) if (weights[j][i] > 0) s += weights[j][i] / out[j] * score[j]
                0.15 + 0.85 * s
            }
        }
        val max = keywordScore.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        return DoubleArray(n) { score[it] + 0.5 * keywordScore[it] / max }
    }
}
