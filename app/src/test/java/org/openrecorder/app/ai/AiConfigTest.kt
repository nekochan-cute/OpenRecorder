package org.openrecorder.app.ai

import org.junit.Assert.*
import org.junit.Test

class AiConfigTest {
    @Test fun providersBuildOpenAiCompatibleEndpoints() {
        val c = AiConfig(summaryProvider = Provider.OpenRouter, summaryModel = "m", transcriptionProvider = Provider.Groq,
            keys = mapOf(Provider.OpenRouter to "or-key", Provider.Groq to "groq-key"))
        assertEquals("https://openrouter.ai/api/v1/chat/completions", c.summaryUrl)
        assertEquals("or-key", c.summaryKey)
        assertEquals("https://api.groq.com/openai/v1/audio/transcriptions", c.transcriptionUrl)
        assertEquals("groq-key", c.transcriptionKey)
        assertTrue(c.summaryReady); assertTrue(c.transcriptionReady)
    }

    @Test fun oneProviderKeyServesBothFeatures() {
        val c = AiConfig(summaryProvider = Provider.OpenAI, transcriptionProvider = Provider.OpenAI, keys = mapOf(Provider.OpenAI to "k"))
        assertEquals("k", c.summaryKey); assertEquals("k", c.transcriptionKey)
        assertFalse("summary needs a model", c.summaryReady)
    }

    @Test fun customBaseIsTrimmedAndKeptPerFeature() {
        val c = AiConfig(summaryProvider = Provider.Custom, summaryBase = " https://llm.example/v1/ ", transcriptionProvider = Provider.Custom,
            transcriptionBase = "https://stt.example/v1")
        assertEquals("https://llm.example/v1/chat/completions", c.summaryUrl)
        assertEquals("https://stt.example/v1/audio/transcriptions", c.transcriptionUrl)
    }

    @Test fun unconfiguredIsNotReady() {
        val c = AiConfig()
        assertEquals("", c.summaryUrl); assertFalse(c.summaryReady); assertFalse(c.transcriptionReady)
        assertFalse(AiConfig(summaryProvider = Provider.Custom, summaryModel = "m").summaryReady)
    }

    @Test fun googleUsesItsOpenAiCompatibilityEndpoint() {
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            AiConfig(summaryProvider = Provider.Google).summaryUrl)
        assertFalse(Provider.Google.transcription); assertTrue(Provider.OpenRouter.oauth)
    }
}
