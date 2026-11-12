package org.openrecorder.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openrecorder.app.core.Recording
import org.openrecorder.app.core.Segment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SummaryTabTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val calls = mutableListOf<String>()
    private val recording = Recording(id = "a", title = "自分で付けた題", fileName = "a.wav", durationMs = 10_000,
        segments = listOf(Segment(0, 4000, "発売日は十一月三十日に決定します", "話者1")),
        summary = "## 概要\n- 発売日の決定", summaryTemplate = "講義", summaryDetail = "Detailed", summaryEngine = "api")

    private fun show(r: Recording, llm: Boolean = true, aiSummary: Boolean = true, busy: Boolean = false) = rule.setContent {
        RecorderTheme(false) {
            DetailScreen(DetailState(r, null, 0, false, busy = busy, aiSummary = aiSummary, llm = llm),
                DetailActions(summarize = { t, d, e, keepTitle -> calls += "$t/$d/$e/$keepTitle" }, summaryDetail = { calls += "remember:$it" }), initialTab = 2)
        }
    }

    @Test fun switchingRemakesTheSummaryTheWayItWasMadeKeepingTheTitle() {
        show(recording)
        rule.onNodeWithText("簡潔").performClick()
        rule.onNodeWithText("会議").performClick()
        assertEquals(listOf("remember:Brief", "講義/Brief/api/true", "会議/Brief/api/true"), calls)
    }

    @Test fun anEngineThatIsGoneFallsBackToTheFirstAvailable() {
        show(recording.copy(summaryEngine = "llm"), llm = false, aiSummary = false)
        rule.onNodeWithText("インタビュー").performClick()
        assertEquals(listOf("インタビュー/Detailed/extract/true"), calls)
    }

    @Test fun withoutASummarySwitchingOnlyChooses() {
        show(recording.copy(summary = ""))
        rule.onNodeWithText("会議").performClick()
        rule.onNodeWithText("簡潔").performClick()
        assertEquals(listOf("remember:Brief"), calls)
    }

    @Test fun switchesWaitWhileASummaryIsBeingMade() {
        show(recording, busy = true)
        rule.onNodeWithText("簡潔").assertIsNotEnabled()
        rule.onNodeWithText("会議").assertIsNotEnabled()
    }
}
