package org.openrecorder.app.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class PcmTest {
    private fun wav(rate: Int, channels: Int, seconds: Double, value: (frame: Int, channel: Int) -> Float): File =
        File.createTempFile("pcm", ".wav").apply {
            deleteOnExit()
            val frames = (rate * seconds).toInt()
            val pcm = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames) { f -> repeat(channels) { c -> pcm.putShort(Pcm.toShort(value(f, c))) } }
            writeBytes(Wav.header(pcm.capacity().toLong(), rate, channels) + pcm.array())
        }

    /** Zero crossings per second of a tone tell its frequency. */
    private fun frequency(x: FloatArray, rate: Int): Double {
        var crossings = 0
        for (i in 1 until x.size) if ((x[i - 1] < 0) != (x[i] < 0)) crossings++
        return crossings / 2.0 / (x.size.toDouble() / rate)
    }

    @Test fun resamplingKeepsPitchAndLength() {
        for ((from, to) in listOf(48000 to 16000, 44100 to 16000, 16000 to 48000)) {
            val tone = FloatArray(from) { (0.5 * sin(2 * PI * 440 * it / from)).toFloat() }
            val r = Resampler(from, to)
            val out = r.process(tone.copyOfRange(0, from / 3)) + r.process(tone.copyOfRange(from / 3, from))
            assertEquals("$from→$to length", to.toDouble(), out.size.toDouble(), 2.0)
            assertEquals("$from→$to pitch", 440.0, frequency(out, to), 4.0)
        }
    }

    @Test fun speechStreamIsMono16k() {
        val file = wav(48000, 2, 1.0) { f, c -> if (c == 0) 0.5f else -0.5f }
        var total = 0; var peak = 0f
        Pcm.speech(file) { chunk, _ -> total += chunk.size; chunk.forEach { peak = maxOf(peak, abs(it)) } }
        assertEquals(16000.0, total.toDouble(), 2.0)
        assertTrue("opposite channels cancel in the mono mix", peak < 0.01f)
    }

    @Test fun joinsDifferentRatesAndChannelsInOrder() {
        val a = wav(16000, 1, 1.0) { _, _ -> 0.25f }
        val b = wav(48000, 2, 0.5) { _, c -> if (c == 0) -0.5f else 0.5f }
        val out = File.createTempFile("joined", ".wav").apply { deleteOnExit() }
        val info = Concat.join(listOf(a, b), out, 48000, 2)
        assertEquals(48000, info.sampleRate); assertEquals(2, info.channels)
        assertEquals(1500.0, info.durationMs.toDouble(), 5.0)
        val samples = mutableListOf<Float>()
        Pcm.read(out) { chunk, _ -> samples += chunk.toList() }
        assertEquals(0.25f, samples[48000], 0.01f)       // middle of the first part, left
        assertEquals(0.25f, samples[48001], 0.01f)       // mono is copied to both channels
        val second = 2 * (48000 + 12000)                  // a quarter into the second part
        assertEquals(-0.5f, samples[second], 0.01f); assertEquals(0.5f, samples[second + 1], 0.01f)
        assertEquals(listOf(0L, 1000L, 1500L), Concat.offsets(listOf(1000L, 500L, 200L)))
    }

    @Test fun joiningNeedsTwoRecordings() {
        val a = wav(16000, 1, 0.1) { _, _ -> 0f }
        assertThrows(IllegalArgumentException::class.java) { Concat.join(listOf(a), File.createTempFile("x", ".wav"), 16000, 1) }
    }
}
