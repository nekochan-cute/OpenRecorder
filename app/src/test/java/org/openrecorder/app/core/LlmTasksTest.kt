package org.openrecorder.app.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LlmTasksTest {
    private val segments = List(5) { Segment(it * 1000L, it * 1000L + 900, "文$it", if (it % 2 == 0) "話者1" else "話者2") }

    @Test fun chunksNeverSplitLines() {
        val lines = List(10) { "x".repeat(40) }
        val chunks = LlmTasks.chunks(lines, 100)
        assertEquals(lines, chunks.flatMap { it.lines() })
        assertTrue(chunks.all { it.length <= 100 })
    }

    @Test fun shortTranscriptIsSummarisedInOneCall() = runBlocking {
        val prompts = mutableListOf<String>()
        val (title, body) = LlmTasks.summarize(segments, "会議", TextGenerator { _, user -> prompts += user; "## 新製品の打ち合わせ\n## 概要\n- 決めた" })
        assertEquals(1, prompts.size)
        assertTrue(prompts[0].contains("[00:00] 話者1: 文0"))
        assertEquals("新製品の打ち合わせ", title)
        assertEquals("## 概要\n- 決めた", body)
    }

    @Test fun longTranscriptIsSummarisedInPartsThenCombined() = runBlocking {
        val long = List(400) { Segment(it * 1000L, it * 1000L + 900, "これは長い会議の発言です。番号は$it です。") }
        var calls = 0; var last = ""
        LlmTasks.summarize(long, "講義", TextGenerator { _, user -> calls++; last = user; "要点$calls\n- a" })
        val parts = LlmTasks.chunks(LlmTasks.transcriptLines(long)).size
        assertTrue(parts > 1)
        assertEquals(parts + 1, calls)
        assertTrue(last.contains("部分ごとの要点")); assertTrue(last.contains("講義"))
    }

    @Test fun titleLineIsCleaned() {
        assertEquals("会議のまとめ" to "本文", LlmTasks.splitTitle("\n**タイトル：会議のまとめ**\n本文"))
        assertEquals("一行だけ" to "一行だけ", LlmTasks.splitTitle("一行だけ"))
    }
}
