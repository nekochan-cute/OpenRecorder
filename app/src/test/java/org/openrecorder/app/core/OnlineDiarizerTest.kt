package org.openrecorder.app.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class OnlineDiarizerTest {
    private val random = Random(5)
    private fun voice() = FloatArray(64) { random.nextGaussian().toFloat() }
    private fun near(v: FloatArray) = FloatArray(v.size) { v[it] + (random.nextGaussian() * 0.25).toFloat() }

    @Test fun assignsSpeakersAsTheyAppear() {
        val a = voice(); val b = voice()
        val d = OnlineDiarizer()
        val result = listOf(a, a, b, a, b, b).map { d.assign(near(it), 300) }
        assertEquals(listOf(0, 0, 1, 0, 1, 1), result)
    }

    @Test fun shortOrMissingUtterancesKeepThePreviousSpeaker() {
        val a = voice(); val b = voice()
        val d = OnlineDiarizer()
        assertEquals(-1, d.assign(null, 0))
        assertEquals(0, d.assign(near(a), 300))
        assertEquals(1, d.assign(near(b), 300))
        assertEquals(1, d.assign(near(a), 10))
        assertEquals(1, d.assign(null, 0))
    }
}
