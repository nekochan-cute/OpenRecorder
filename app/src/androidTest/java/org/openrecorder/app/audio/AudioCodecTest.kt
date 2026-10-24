package org.openrecorder.app.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.openrecorder.app.core.AudioProcessor
import org.openrecorder.app.core.ProcessOptions
import org.openrecorder.app.core.Wav
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class AudioCodecTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "codec-test").apply { deleteRecursively(); mkdirs() }

    private fun sine(rate: Int, channels: Int, seconds: Int): File = File(dir, "sine-$rate-$channels.wav").apply {
        val frames = rate * seconds
        val pcm = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { f -> val v = (0.3 * sin(2 * PI * 440 * f / rate) * 32767).toInt().toShort(); repeat(channels) { pcm.putShort(v) } }
        outputStream().use { it.write(Wav.header(pcm.capacity().toLong(), rate, channels)); it.write(pcm.array()) }
    }

    private fun rms(file: File): Double {
        val info = Wav.inspect(file)
        val bytes = ByteArray(info.bytes.toInt()).also { b -> RandomAccessFile(file, "r").use { it.seek(info.offset); it.readFully(b) } }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0.0; val n = bytes.size / 2
        repeat(n) { val v = buffer.short / 32768.0; sum += v * v }
        return sqrt(sum / n)
    }

    @Test fun aacRoundTripKeepsDurationFormatAndLevel() {
        for ((rate, channels) in listOf(48000 to 1, 44100 to 2, 16000 to 1)) {
            val source = sine(rate, channels, 2)
            val m4a = AudioCodec.encodeAac(source, File(dir, "out-$rate.m4a"))
            assertTrue(m4a.length() in 1..source.length() / 4)
            val decoded = AudioCodec.decodeToWav(m4a, File(dir, "back-$rate.wav"))
            assertEquals(rate, decoded.sampleRate); assertEquals(channels, decoded.channels)
            assertEquals("duration at $rate Hz", 2000.0, decoded.durationMs.toDouble(), 150.0)
            assertEquals("level at $rate Hz", rms(source), rms(File(dir, "back-$rate.wav")), 0.03)
        }
    }

    @Test fun decodingRejectsNonAudioWithoutLeavingOutput() {
        val junk = File(dir, "junk.bin").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        val target = File(dir, "junk.wav")
        assertThrows(Exception::class.java) { AudioCodec.decodeToWav(junk, target) }
        assertFalse(target.exists())
    }

    @Test fun processingRunsOnDevice() {
        val source = sine(48000, 2, 3)
        val out = AudioProcessor.process(source, File(dir, "processed.wav"), ProcessOptions())
        assertEquals(3000L, out.durationMs); assertEquals(2, out.channels)
    }
}
