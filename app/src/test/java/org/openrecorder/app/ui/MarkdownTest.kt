package org.openrecorder.app.ui

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {
    private val style = InlineStyle(SpanStyle(fontFamily = FontFamily.Monospace), TextLinkStyles())
    private fun inline(text: String) = Markdown.inline(text, style)
    private fun spans(text: String) = inline(text).let { s -> s.spanStyles.map { it.item to s.text.substring(it.start, it.end) } }

    @Test fun summaryAsAnAiWritesIt() {
        val md = """
            # 定例会議
            ## 決定事項
            - 発売日を**11月30日**に決定
              - 広告は別途
                1. 予算案
            ・印刷物
            - [x] 見積もりを共有
            - [ ] 契約書を確認
            1) 次回の議題

            > 注意:
            > - 未確定
            ---
            ```kotlin
            val a = *b*
            ```
            Q. 開発の動機は？
            A. 不便だったからです
        """.trimIndent()
        assertEquals(listOf(
            MdBlock.Heading(1, "定例会議"), MdBlock.Heading(2, "決定事項"),
            MdBlock.Bullet("発売日を**11月30日**に決定", 0), MdBlock.Bullet("広告は別途", 1), MdBlock.Numbered("1.", "予算案", 2),
            MdBlock.Bullet("印刷物", 0), MdBlock.Task(true, "見積もりを共有", 0), MdBlock.Task(false, "契約書を確認", 0), MdBlock.Numbered("1)", "次回の議題", 0),
            MdBlock.Gap, MdBlock.Quote(listOf(MdBlock.Line("注意:"), MdBlock.Bullet("未確定"))), MdBlock.Rule,
            MdBlock.Code("val a = *b*"), MdBlock.Qa(true, "開発の動機は？"), MdBlock.Qa(false, "不便だったからです"),
        ), Markdown.blocks(md))
    }

    @Test fun lookalikesAreToldApart() {
        assertEquals(listOf(MdBlock.Heading(2, "概要")), Markdown.blocks("概要\n---"))
        assertEquals(listOf(MdBlock.Heading(1, "題")), Markdown.blocks("題\n==="))
        assertEquals(listOf(MdBlock.Bullet("項目"), MdBlock.Rule), Markdown.blocks("- 項目\n---"))
        assertEquals(listOf(MdBlock.Line("本文"), MdBlock.Gap, MdBlock.Rule), Markdown.blocks("本文\n\n***"))
        assertEquals(listOf(MdBlock.Line("```npm install```"), MdBlock.Line("本文")), Markdown.blocks("```npm install```\n本文"))
        assertEquals(listOf(MdBlock.Line("[x](https://example.com) を参照")), Markdown.blocks("[x](https://example.com) を参照"))
    }

    @Test fun tablesKeepEscapedPipesAlignmentAndBreaks() {
        assertEquals(listOf(MdBlock.Table(listOf("項目", "担当|期限"), listOf(TextAlign.Start, TextAlign.End),
            listOf(listOf("見積もり<br>再提出", "田中"), listOf("広告", ""))),
        ), Markdown.blocks("| 項目 | 担当\\|期限 |\n|:--|--:|\n| 見積もり<br>再提出 | 田中 |\n| 広告 |"))
        assertEquals("見積もり\n再提出", inline("見積もり<br>再提出").text)
    }

    @Test fun emphasisClosesAroundJapanesePunctuation() {
        assertEquals("「重要」です。注と両方", inline("**「重要」**です。*注*と***両方***").text)
        assertEquals(listOf(SpanStyle(fontWeight = FontWeight.Bold) to "「重要」", SpanStyle(fontStyle = FontStyle.Italic) to "注",
            SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic) to "両方"), spans("**「重要」**です。*注*と***両方***"))
        assertEquals(listOf(SpanStyle(fontWeight = FontWeight.Bold) to "太字と斜体", SpanStyle(fontStyle = FontStyle.Italic) to "斜体",
            SpanStyle(textDecoration = TextDecoration.LineThrough) to "取り消し"), spans("**太字と*斜体***、~~取り消し~~"))
    }

    @Test fun marksThatAreNotFormattingStayAsWritten() {
        val text = "`a*b*c` は10~12時、file_name_v2 と 2 * 3 と \\*強調しない\\*"
        assertEquals("a*b*c は10~12時、file_name_v2 と 2 * 3 と *強調しない*", inline(text).text)
        assertEquals(listOf(SpanStyle(fontFamily = FontFamily.Monospace) to "a*b*c"), spans(text))
    }

    @Test fun onlyWebAndMailLinksOpen() {
        val s = inline("[資料](https://example.com/a_(b) \"題\")、[危険](javascript:alert(1))、<mailto:a@example.com>、https://example.com/p。次")
        assertEquals("資料、危険、mailto:a@example.com、https://example.com/p。次", s.text)
        assertEquals(listOf("https://example.com/a_(b)", "mailto:a@example.com", "https://example.com/p"),
            s.getLinkAnnotations(0, s.length).map { (it.item as LinkAnnotation.Url).url })
    }
}
