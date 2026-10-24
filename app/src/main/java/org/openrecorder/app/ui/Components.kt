package org.openrecorder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import org.openrecorder.app.ui.icons.CheckBoxOutlineBlank
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Content column for wide screens: phones use the full width, tablets get a readable measure. */
fun Modifier.readable() = this.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 640.dp).fillMaxWidth()

@Composable
fun EmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(72.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp))
}

fun clock(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(Locale.ROOT, s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(Locale.ROOT, s / 60, s % 60)
}

fun dayLabel(epochMs: Long, today: LocalDate = LocalDate.now()): String {
    val day = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
    return when {
        day == today -> "今日"
        day == today.minusDays(1) -> "昨日"
        day.year == today.year -> day.format(DateTimeFormatter.ofPattern("M月d日（E）", Locale.JAPAN))
        else -> day.format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.JAPAN))
    }
}

fun timeOfDay(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("H:mm", Locale.ROOT))

/** Renders the subset of Markdown that summaries use: headings, bullets, task items, numbered lists, quotes, bold. */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        markdown.lines().forEach { raw ->
            val line = raw.trimEnd()
            val indent = raw.length - raw.trimStart().length
            val t = line.trim()
            when {
                t.isEmpty() -> Spacer(Modifier.height(4.dp))
                t.startsWith("#") -> Text(inline(t.trimStart('#').trim()), style = if (t.startsWith("# ")) type.titleLarge else type.titleMedium,
                    modifier = Modifier.padding(top = 12.dp))
                t.startsWith("- [ ]") || t.startsWith("- [x]") || t.startsWith("[ ]") -> Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.CheckBoxOutlineBlank, null, Modifier.padding(top = 3.dp).size(18.dp), tint = colors.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(inline(t.substringAfter("]").trim()), style = type.bodyLarge)
                }
                t.startsWith("- ") || t.startsWith("* ") || t.startsWith("・") -> Row(Modifier.padding(start = (indent * 6).dp)) {
                    Text("•", style = type.bodyLarge, color = colors.onSurfaceVariant, modifier = Modifier.width(18.dp))
                    Text(inline(t.removePrefix("- ").removePrefix("* ").removePrefix("・")), style = type.bodyLarge)
                }
                Regex("^\\d+[.)] ").containsMatchIn(t) -> Row {
                    Text(t.substringBefore(' '), style = type.bodyLarge, color = colors.onSurfaceVariant, modifier = Modifier.width(26.dp))
                    Text(inline(t.substringAfter(' ')), style = type.bodyLarge)
                }
                t.startsWith("Q. ") || t.startsWith("A. ") -> Row(Modifier.padding(start = if (t.startsWith("A")) 18.dp else 0.dp)) {
                    Text(t.take(2), style = type.bodyLarge, fontWeight = FontWeight.Bold, color = if (t.startsWith("Q")) colors.primary else colors.onSurfaceVariant,
                        modifier = Modifier.width(26.dp))
                    Text(inline(t.drop(3)), style = type.bodyLarge)
                }
                t.startsWith(">") -> Text(inline(t.trimStart('>').trim()), style = type.bodyMedium, color = colors.onSurfaceVariant)
                else -> Text(inline(t), style = type.bodyLarge)
            }
        }
    }
}

private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var rest = text
    while (true) {
        val start = rest.indexOf("**")
        val end = if (start >= 0) rest.indexOf("**", start + 2) else -1
        if (start < 0 || end < 0) { append(rest); break }
        append(rest.substring(0, start))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(rest.substring(start + 2, end)) }
        rest = rest.substring(end + 2)
    }
}
