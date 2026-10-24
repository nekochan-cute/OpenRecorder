package org.openrecorder.app.core

import org.junit.Assert.*
import org.junit.Test

class LocalSummaryTest {
    private val meeting = listOf(
        Segment(0, 4000, "今日は新製品の発売スケジュールと予算について話します", "話者1"),
        Segment(4000, 8000, "発売スケジュールは来月末を目標にしています", "話者1"),
        Segment(8000, 12000, "予算は先月の見積もりより少し増えそうです", "話者2"),
        Segment(12000, 16000, "それでは発売日は十一月三十日に決定します", "話者1"),
        Segment(16000, 20000, "田中さんは金曜日までに予算の見積もりを作成してください。お願いします", "話者1"),
        Segment(20000, 24000, "わかりました、見積もりを作成して共有します", "話者2"),
        Segment(24000, 28000, "広告の予算は別途検討することにしましょう", "話者2"),
        Segment(28000, 32000, "スケジュールの詳細は次回確認します", "話者1"),
    )

    @Test fun meetingSummaryExtractsDecisionsActionsAndTitle() {
        val result = LocalSummary.summarize(meeting, "会議")
        assertTrue(result.title, result.title.endsWith("（会議）"))
        assertTrue(result.title, result.title.contains("予算") || result.title.contains("スケジュール") || result.title.contains("見積"))
        val decisions = result.body.substringAfter("## 決定事項").substringBefore("## 作業項目")
        assertTrue(decisions, decisions.contains("十一月三十日に決定します"))
        val actions = result.body.substringAfter("## 作業項目").substringBefore("## キーワード")
        assertTrue(actions, actions.contains("[ ] 話者1: 田中さんは金曜日までに"))
        assertTrue(result.body.contains("## 概要"))
    }

    @Test fun everyOverviewLineIsQuotedFromTheTranscript() {
        val sentences = meeting.flatMap { it.text.split("。") }.map { it.trim() }
        val overview = LocalSummary.summarize(meeting, "会議").body.substringAfter("## 概要").substringBefore("## ")
            .lines().filter { it.startsWith("- ") }.map { it.removePrefix("- ") }
        assertTrue(overview.isNotEmpty())
        overview.forEach { line -> assertTrue(line, sentences.any { line.removeSuffix("。") == it.removeSuffix("。") }) }
    }

    @Test fun interviewPairsQuestionsWithTheOtherSpeaker() {
        val interview = listOf(
            Segment(0, 3000, "What made you start this project?", "Host"),
            Segment(3000, 8000, "I wanted an open recorder that works offline on any phone.", "Guest"),
            Segment(8000, 11000, "How long did the first version take to build?", "Host"),
            Segment(11000, 15000, "About three months of evenings and weekends.", "Guest"),
        )
        val result = LocalSummary.summarize(interview, "インタビュー")
        assertTrue(result.title.startsWith("Interview: "))
        assertTrue(result.body, result.body.contains("Q. Host: What made you start this project?\n  A. Guest: I wanted an open recorder"))
    }

    @Test fun lectureListsKeyPoints() {
        val lecture = listOf(
            Segment(0, 5000, "今日はフーリエ変換について説明します"),
            Segment(5000, 9000, "フーリエ変換とは信号を周波数成分に分解する方法です"),
            Segment(9000, 13000, "ここが重要なポイントで、時間と周波数は表裏の関係にあります"),
            Segment(13000, 17000, "次回は離散フーリエ変換と高速フーリエ変換を扱います"),
        )
        val body = LocalSummary.summarize(lecture, "講義").body
        assertTrue(body.contains("## 重要なポイント"))
        assertTrue(body.contains("フーリエ変換とは信号を周波数成分に分解する方法です"))
        assertTrue(LocalSummary.summarize(lecture, "講義").title.contains("フーリエ変換"))
    }

    @Test fun emptyTranscriptIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { LocalSummary.summarize(emptyList(), "会議") }
    }
}
