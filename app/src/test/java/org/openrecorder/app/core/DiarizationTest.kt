package org.openrecorder.app.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class DiarizationTest {
    private val random = Random(3)
    private fun around(center: FloatArray, spread: Float) = FloatArray(center.size) { center[it] + (random.nextGaussian() * spread).toFloat() }
    private fun speaker() = FloatArray(32) { random.nextGaussian().toFloat() }

    @Test fun separatesTwoSpeakersInOrderOfAppearance() {
        val a = speaker(); val b = speaker()
        val pattern = listOf(1, 1, 0, 1, 0, 0, 1, 0)
        val utterances = pattern.map { Diarization.Utterance(around(if (it == 1) b else a, 0.3f), 300) }
        val result = Diarization.cluster(utterances)
        assertEquals(pattern.map { 1 - it }, result.toList()) // b speaks first, so b is speaker 0
    }

    @Test fun sameSpeakerStaysOne() {
        val a = speaker()
        assertEquals(setOf(0), Diarization.cluster(List(10) { Diarization.Utterance(around(a, 0.3f), 300) }).toSet())
    }

    @Test fun shortAndMissingVectorsInheritNeighbours() {
        val a = speaker(); val b = speaker()
        val utterances = listOf(
            Diarization.Utterance(null, 0), Diarization.Utterance(around(a, 0.2f), 400), Diarization.Utterance(null, 0),
            Diarization.Utterance(around(b, 0.2f), 400), Diarization.Utterance(around(b, 0.2f), 20))
        assertEquals(listOf(0, 0, 0, 1, 1), Diarization.cluster(utterances).toList())
    }

    @Test fun nothingClusterableYieldsNoSpeaker() {
        assertEquals(listOf(-1, -1), Diarization.cluster(listOf(Diarization.Utterance(null, 0), Diarization.Utterance(null, 0))).toList())
        assertEquals("", Diarization.label(-1)); assertEquals("話者2", Diarization.label(1))
    }

    @Test fun respectsSpeakerLimit() {
        val utterances = List(12) { Diarization.Utterance(speaker(), 300) }
        assertTrue(Diarization.cluster(utterances, maxSpeakers = 3).toSet().size <= 3)
    }
}
