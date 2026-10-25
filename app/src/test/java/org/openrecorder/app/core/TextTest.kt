package org.openrecorder.app.core

import org.junit.Assert.*
import org.junit.Test

class TextTest {
    @Test fun joinsSpaceSeparatedJapaneseTokensButKeepsLatinSpacing() {
        assertEquals("今日はいい天気です", Text.joinTokens("今日 は いい 天気 です"))
        assertEquals("Pythonを使ったopen sourceの話", Text.joinTokens("Python を 使った open source の 話"))
        assertEquals("hello world", Text.joinTokens("  hello   world "))
        assertEquals("", Text.joinTokens("   "))
    }

    @Test fun detectsScript() {
        assertTrue(Text.mostlyCjk("会議の議題を確認します OK"))
        assertFalse(Text.mostlyCjk("Let's review the agenda 了解"))
    }

    @Test fun webVttUsesDotMillisecondsAndVoiceSpans() {
        val old = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
            assertEquals("WEBVTT\n\n01:00:02.005 --> 01:00:03.000\n<v 話者1>こんにちは\n\n00:00:00.000 --> 00:00:01.000\na → b\n",
                webVtt(listOf(Segment(3602005, 3603000, "こんにちは", "話者1"), Segment(0, 1000, "a --> b"))))
        } finally { java.util.Locale.setDefault(old) }
    }

    @Test fun webVttEscapesCueTextButKeepsVoiceSpans() {
        assertEquals("WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n<v 話者1>a &lt;b&gt; &amp; c\n",
            webVtt(listOf(Segment(0, 1000, "a <b> & c", "話者1"))))
    }
}
