package org.openrecorder.app.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class SpeakerClustersTest {
    private val random = Random(3)
    private fun voice() = FloatArray(32) { random.nextGaussian().toFloat() }
    private fun near(v: FloatArray) = FloatArray(v.size) { v[it] + (random.nextGaussian() * 0.3).toFloat() }
    private fun clusters(vararg utterances: Pair<FloatArray?, Int>) = SpeakerClusters().apply { utterances.forEach { (v, f) -> add(v, f) } }

    @Test fun separatesTwoSpeakersInOrderOfAppearance() {
        val a = voice(); val b = voice()
        val pattern = listOf(1, 1, 0, 1, 0, 0, 1, 0)
        val c = clusters(*pattern.map { near(if (it == 1) b else a) to 300 }.toTypedArray())
        val expected = pattern.map { 1 - it } // b speaks first, so b is speaker 0
        assertEquals(expected, c.final().toList())
        assertEquals(expected, c.current().toList())
    }

    @Test fun aVoiceHeardOnlyOnceIsNotASpeaker() {
        // One person, with one utterance that sounds unlike the rest (shouting, turned away): still one speaker.
        val a = voice()
        val c = clusters(*List(9) { near(a) to 300 }.toTypedArray(), voice() to 300, near(a) to 300)
        assertEquals(List(11) { 0 }, c.final().toList())
        assertEquals(List(11) { 0 }, c.current().toList())
    }

    @Test fun whileRecordingANewVoiceIsConfirmedWhenHeardAgain() {
        val a = voice(); val b = voice()
        val c = clusters(near(a) to 300, near(a) to 300, near(b) to 300)
        assertEquals(listOf(0, 0, 0), c.current().toList()) // heard once: shown as the nearest known speaker
        c.add(near(b), 300)
        assertEquals(listOf(0, 0, 1, 1), c.current().toList()) // confirmed: its first utterance is relabelled too
    }

    @Test fun shortAndMissingVectorsInheritNeighbours() {
        val a = voice(); val b = voice()
        val c = clusters(null to 0, near(a) to 400, null to 0, near(a) to 400, near(b) to 400, near(b) to 400, near(b) to 20)
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 1), c.final().toList())
        assertEquals(listOf(-1, 0, 0, 0, 1, 1, 1), c.current().toList()) // live: nobody is known before the first voice
    }

    @Test fun nothingClusterableYieldsNoSpeaker() {
        val c = clusters(null to 0, null to 0)
        assertEquals(listOf(-1, -1), c.final().toList())
        assertEquals(listOf(-1, -1), c.current().toList())
        assertEquals("", SpeakerClusters.label(-1)); assertEquals("話者2", SpeakerClusters.label(1))
    }

    @Test fun respectsSpeakerLimit() {
        val c = SpeakerClusters(maxSpeakers = 3)
        repeat(12) { val v = voice(); c.add(near(v), 300); c.add(near(v), 300) }
        assertEquals(3, c.final().toSet().size)
        assertTrue(c.current().toSet().size <= 3)
    }
}
