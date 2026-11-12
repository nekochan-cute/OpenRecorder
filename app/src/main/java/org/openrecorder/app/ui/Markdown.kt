package org.openrecorder.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.openrecorder.app.ui.icons.CheckBox
import org.openrecorder.app.ui.icons.CheckBoxOutlineBlank

/** A block of a summary. Every source line stays a line of its own, as AI and extracted summaries are written line by line. */
internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Line(val text: String, val depth: Int = 0) : MdBlock
    data class Bullet(val text: String, val depth: Int = 0) : MdBlock
    data class Numbered(val number: String, val text: String, val depth: Int = 0) : MdBlock
    data class Task(val done: Boolean, val text: String, val depth: Int = 0) : MdBlock
    data class Qa(val question: Boolean, val text: String) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Table(val header: List<String>, val align: List<TextAlign>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
    data object Gap : MdBlock
}

internal class InlineStyle(val code: SpanStyle, val link: TextLinkStyles)

/**
 * Markdown as AI services and on-device models write it (CommonMark with GitHub tables, task items and strikethrough), plus the
 * summaries' own `Q.`/`A.` lines and `・` bullets. Emphasis closes on any non-space character so that Japanese punctuation such as
 * `**「重要」**` works, `~` alone is a range (10~12時) rather than strikethrough, and only web and mail links can be opened.
 */
internal object Markdown {
    private val heading = Regex("^(#{1,6})\\s*(.*?)(?:\\s+#+)?\\s*$")
    private val rule = Regex("^([-*_])(?:\\s*\\1){2,}\\s*$")
    private val fence = Regex("^(`{3,}|~{3,})")
    private val task = Regex("^(?:[-*+]\\s+)?\\[([ xX])](?:\\s+(.*))?$")
    private val bullet = Regex("^(?:[-*+]\\s+|[・•]\\s*)(.*)$")
    private val numbered = Regex("^(\\d{1,9}[.)])\\s+(.*)$")
    private val delimiter = Regex("^:?-+:?$")
    private val br = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private const val PUNCT = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

    fun blocks(markdown: String): List<MdBlock> {
        val lines = markdown.replace("\r\n", "\n").replace("\t", "    ").split('\n')
        val out = mutableListOf<MdBlock>()
        val lists = ArrayList<Int>()
        fun depth(indent: Int): Int {
            while (lists.isNotEmpty() && lists.last() >= indent) lists.removeAt(lists.lastIndex)
            return lists.size.also { lists += indent }
        }
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val t = raw.trim()
            val indent = raw.length - raw.trimStart().length
            val previousLine = i > 0 && lines[i - 1].isNotBlank() && (out.lastOrNull() as? MdBlock.Line)?.depth == 0
            i++
            if (t.isEmpty()) { if (out.isNotEmpty() && out.last() != MdBlock.Gap) out += MdBlock.Gap; continue }
            val open = fence.find(t)?.value?.takeIf { it[0] != '`' || '`' !in t.substring(it.length) }
            if (open != null) {
                val body = mutableListOf<String>()
                while (i < lines.size && !lines[i].trim().let { it.length >= open.length && it.all { c -> c == open[0] } }) {
                    body += lines[i].drop(minOf(indent, lines[i].length - lines[i].trimStart().length)); i++
                }
                i++
                out += MdBlock.Code(body.joinToString("\n")); lists.clear(); continue
            }
            if (t.startsWith(">")) {
                val quoted = mutableListOf(t)
                while (i < lines.size && lines[i].trim().startsWith(">")) quoted += lines[i++].trim()
                out += MdBlock.Quote(blocks(quoted.joinToString("\n") { it.removePrefix(">").removePrefix(" ") })); lists.clear(); continue
            }
            val header = if ('|' in t && i < lines.size) cells(t) else null
            val delimiters = header?.let { cells(lines[i].trim()) }
            if (header != null && delimiters!!.size == header.size && delimiters.all(delimiter::matches)) {
                i++
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].isNotBlank() && '|' in lines[i]) rows += cells(lines[i++].trim()).let { r -> header.indices.map { r.getOrElse(it) { "" } } }
                val align = delimiters.map { d -> if (d.startsWith(':') && d.endsWith(':')) TextAlign.Center else if (d.endsWith(':')) TextAlign.End else TextAlign.Start }
                out += MdBlock.Table(header, align, rows); lists.clear(); continue
            }
            if (previousLine && (t.all { it == '=' } || t.all { it == '-' })) {
                out[out.lastIndex] = MdBlock.Heading(if (t[0] == '=') 1 else 2, (out.last() as MdBlock.Line).text); continue
            }
            if (rule.matches(t)) { out += MdBlock.Rule; lists.clear(); continue }
            heading.matchEntire(t)?.takeIf { it.groupValues[2].isNotEmpty() }?.let {
                out += MdBlock.Heading(it.groupValues[1].length, it.groupValues[2]); lists.clear(); continue
            }
            task.matchEntire(t)?.let { out += MdBlock.Task(it.groupValues[1] != " ", it.groupValues[2], depth(indent)); continue }
            bullet.matchEntire(t)?.let { out += MdBlock.Bullet(it.groupValues[1], depth(indent)); continue }
            numbered.matchEntire(t)?.let { out += MdBlock.Numbered(it.groupValues[1], it.groupValues[2], depth(indent)); continue }
            if (t.startsWith("Q. ") || t.startsWith("A. ")) { out += MdBlock.Qa(t[0] == 'Q', t.drop(3)); lists.clear(); continue }
            if (indent == 0) lists.clear()
            out += MdBlock.Line(t.removeSuffix("\\").trimEnd(), lists.count { it < indent })
        }
        while (out.lastOrNull() == MdBlock.Gap) out.removeAt(out.lastIndex)
        return out
    }

    /** Cells of a table row; `\|` is a pipe inside a cell, other escapes are left to [inline]. */
    private fun cells(row: String): List<String> {
        val s = row.removePrefix("|").let { if (it.endsWith("|") && !it.endsWith("\\|")) it.dropLast(1) else it }
        val out = mutableListOf<String>()
        val cell = StringBuilder()
        var k = 0
        while (k < s.length) {
            when {
                s[k] == '\\' && s.getOrNull(k + 1) == '|' -> { cell.append('|'); k++ }
                s[k] == '\\' && k + 1 < s.length -> { cell.append(s, k, k + 2); k++ }
                s[k] == '|' -> { out += cell.trim().toString(); cell.clear() }
                else -> cell.append(s[k])
            }
            k++
        }
        return out + cell.trim().toString()
    }

    fun inline(text: String, style: InlineStyle): AnnotatedString = buildAnnotatedString { appendInline(text.replace(br, "\n"), style, links = true) }

    private fun AnnotatedString.Builder.appendInline(s: String, st: InlineStyle, links: Boolean) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            i = when {
                c == '\\' && i + 1 < s.length && s[i + 1] in PUNCT -> { append(s[i + 1]); i + 2 }
                c == '`' -> code(s, i, st)
                c == '*' || c == '_' || c == '~' -> emphasis(s, i, st, links)
                links && c == '!' && s.getOrNull(i + 1) == '[' -> link(s, i + 1, st).takeIf { it > 0 } ?: run { append(c); i + 1 }
                links && c == '[' -> link(s, i, st).takeIf { it > 0 } ?: run { append(c); i + 1 }
                links && c == '<' -> autolink(s, i, st)
                links && (s.startsWith("https://", i) || s.startsWith("http://", i)) && (i == 0 || !s[i - 1].isLetterOrDigit()) -> url(s, i, st)
                else -> { append(c); i + 1 }
            }
        }
    }

    private fun run(s: String, i: Int, c: Char): Int { var j = i; while (j < s.length && s[j] == c) j++; return j - i }

    private fun AnnotatedString.Builder.code(s: String, i: Int, st: InlineStyle): Int {
        val n = run(s, i, '`')
        var j = i + n
        while (j < s.length) { if (s[j] == '`') { val r = run(s, j, '`'); if (r == n) break; j += r } else j++ }
        if (j >= s.length) { append(s, i, i + n); return i + n }
        val body = s.substring(i + n, j).let { if (it.length > 2 && it.first() == ' ' && it.last() == ' ' && it.isNotBlank()) it.substring(1, it.length - 1) else it }
        withStyle(st.code) { append(body) }
        return j + n
    }

    /** `*`/`_` runs of 1-3 (italic, bold, both) and `~~` strikethrough; a run that does not close stays as written. */
    private fun AnnotatedString.Builder.emphasis(s: String, i: Int, st: InlineStyle, links: Boolean): Int {
        val c = s[i]
        val n = run(s, i, c)
        val after = s.getOrNull(i + n)
        val opens = after != null && !after.isWhitespace() && !(c == '_' && i > 0 && s[i - 1].isLetterOrDigit()) && (if (c == '~') n == 2 else n <= 3)
        val accepted = when { c == '~' -> setOf(2); n == 1 -> setOf(1, 3); n == 2 -> setOf(2, 3); else -> setOf(3) }
        val close = if (opens) closer(s, i + n, c, accepted) else -1
        if (close < 0) { append(s, i, i + n); return i + n }
        val r = run(s, close, c)
        val style = when {
            c == '~' -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            n == 1 -> SpanStyle(fontStyle = FontStyle.Italic)
            n == 2 -> SpanStyle(fontWeight = FontWeight.Bold)
            else -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
        }
        withStyle(style) { appendInline(s.substring(i + n, close + r - n), st, links) }
        return close + r
    }

    private fun closer(s: String, from: Int, c: Char, accepted: Set<Int>): Int {
        var j = from
        while (j < s.length) {
            when (s[j]) {
                '\\' -> j += 2
                c -> {
                    val r = run(s, j, c)
                    if (r in accepted && j > from && !s[j - 1].isWhitespace() && !(c == '_' && s.getOrNull(j + r)?.isLetterOrDigit() == true)) return j
                    j += r
                }
                else -> j++
            }
        }
        return -1
    }

    /** `[text](target "title")` starting at the bracket; -1 when it is not one. */
    private fun AnnotatedString.Builder.link(s: String, i: Int, st: InlineStyle): Int {
        var depth = 0
        var j = i
        while (j < s.length) { when (s[j]) { '\\' -> j++; '[' -> depth++; ']' -> if (--depth == 0) break }; j++ }
        if (j >= s.length || s.getOrNull(j + 1) != '(') return -1
        var k = j + 2
        var parens = 1
        while (k < s.length) { when (s[k]) { '\\' -> k++; '(' -> parens++; ')' -> if (--parens == 0) break }; k++ }
        if (k >= s.length) return -1
        val target = s.substring(j + 2, k).trim().substringBefore(' ').removeSurrounding("<", ">")
        val text = s.substring(i + 1, j).ifEmpty { target }
        if (opens(target)) withLink(LinkAnnotation.Url(target, st.link)) { appendInline(text, st, links = false) } else appendInline(text, st, links = false)
        return k + 1
    }

    private fun AnnotatedString.Builder.autolink(s: String, i: Int, st: InlineStyle): Int {
        val end = s.indexOf('>', i)
        val target = if (end > 0) s.substring(i + 1, end) else ""
        if (!opens(target) || target.any { it.isWhitespace() }) { append('<'); return i + 1 }
        withLink(LinkAnnotation.Url(target, st.link)) { append(target) }
        return end + 1
    }

    /** A bare web address: printable ASCII only, so Japanese text right after it is not part of it. */
    private fun AnnotatedString.Builder.url(s: String, i: Int, st: InlineStyle): Int {
        var e = i
        while (e < s.length && s[e].code in 0x21..0x7E && s[e] != '<' && s[e] != '>' && s[e] != '"') e++
        while (e > i && (s[e - 1] in ".,:;!?'*_~" || s[e - 1] == ')' && s.substring(i, e).count { it == ')' } > s.substring(i, e).count { it == '(' })) e--
        val target = s.substring(i, e)
        if (target.substringAfter("://").isEmpty()) { append(s[i]); return i + 1 }
        withLink(LinkAnnotation.Url(target, st.link)) { append(target) }
        return e
    }

    private fun opens(target: String) = listOf("https://", "http://", "mailto:").any { target.startsWith(it, ignoreCase = true) && target.length > it.length }
}

/** Renders a Markdown summary; see [Markdown] for what is understood. */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { Markdown.blocks(markdown) }
    val colors = MaterialTheme.colorScheme
    val style = InlineStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.surfaceContainerHighest),
        TextLinkStyles(SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline)))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) { Blocks(blocks, style) }
}

@Composable
private fun Blocks(blocks: List<MdBlock>, st: InlineStyle) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    fun indent(depth: Int) = (depth * 18).dp
    blocks.forEach { b ->
        when (b) {
            MdBlock.Gap -> Spacer(Modifier.height(4.dp))
            MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 8.dp), color = colors.outlineVariant)
            is MdBlock.Heading -> Text(Markdown.inline(b.text, st), Modifier.padding(top = if (b.level <= 2) 12.dp else 8.dp),
                style = when (b.level) { 1 -> type.titleLarge; 2 -> type.titleMedium; else -> type.titleSmall })
            is MdBlock.Line -> Text(Markdown.inline(b.text, st), Modifier.padding(start = indent(b.depth)), style = type.bodyLarge)
            is MdBlock.Bullet -> Row(Modifier.padding(start = indent(b.depth))) {
                Text(if (b.depth == 0) "•" else "◦", Modifier.width(18.dp), color = colors.onSurfaceVariant, style = type.bodyLarge)
                Text(Markdown.inline(b.text, st), style = type.bodyLarge)
            }
            is MdBlock.Numbered -> Row(Modifier.padding(start = indent(b.depth))) {
                Text(b.number, Modifier.widthIn(min = 26.dp).padding(end = 4.dp), color = colors.onSurfaceVariant, style = type.bodyLarge)
                Text(Markdown.inline(b.text, st), style = type.bodyLarge)
            }
            is MdBlock.Task -> Row(Modifier.padding(start = indent(b.depth)), verticalAlignment = Alignment.Top) {
                Icon(if (b.done) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank, if (b.done) "完了" else "未完了",
                    Modifier.padding(top = 3.dp).size(18.dp), tint = colors.primary)
                Spacer(Modifier.width(10.dp))
                Text(Markdown.inline(b.text, st), color = if (b.done) colors.onSurfaceVariant else LocalContentColor.current, style = type.bodyLarge)
            }
            is MdBlock.Qa -> Row(Modifier.padding(start = if (b.question) 0.dp else 18.dp)) {
                Text(if (b.question) "Q." else "A.", Modifier.width(26.dp), color = if (b.question) colors.primary else colors.onSurfaceVariant,
                    fontWeight = FontWeight.Bold, style = type.bodyLarge)
                Text(Markdown.inline(b.text, st), style = type.bodyLarge)
            }
            is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(colors.outlineVariant, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(12.dp))
                CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Blocks(b.blocks, st) }
                }
            }
            is MdBlock.Code -> Text(b.text, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.surfaceContainerHighest)
                .horizontalScroll(rememberScrollState()).padding(12.dp), fontFamily = FontFamily.Monospace, softWrap = false, style = type.bodyMedium)
            is MdBlock.Table -> Table(b, st)
        }
    }
}

/** Columns share the width by their longest cell, so short columns stay narrow and long text wraps. */
@Composable
private fun Table(t: MdBlock.Table, st: InlineStyle) {
    val colors = MaterialTheme.colorScheme
    val rows = listOf(t.header) + t.rows
    val weights = t.header.indices.map { c -> rows.maxOf { it[c].length }.coerceIn(3, 24).toFloat() }
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(shape).border(1.dp, colors.outlineVariant, shape)) {
        rows.forEachIndexed { r, row ->
            if (r > 0) HorizontalDivider(color = colors.outlineVariant)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).then(if (r == 0) Modifier.background(colors.surfaceContainerHigh) else Modifier)) {
                row.forEachIndexed { c, cell ->
                    if (c > 0) VerticalDivider(color = colors.outlineVariant)
                    Text(Markdown.inline(cell, st), Modifier.weight(weights[c]).padding(horizontal = 8.dp, vertical = 6.dp),
                        fontWeight = if (r == 0) FontWeight.SemiBold else null, textAlign = t.align[c], style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
