package org.openrecorder.app.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class RecordingFormatsTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "formats-test").apply { deleteRecursively(); mkdirs() }

    /** Writes [seconds] of a tone in 20 ms buffers, as the recorder does. */
    private fun record(sink: PcmSink, rate: Int, channels: Int, seconds: Int) = sink.use {
        val block = rate / 50
        repeat(seconds * 50) { b ->
            val pcm = ShortArray(block * channels) { i -> (8000 * sin(2 * PI * 440 * (b * block + i / channels) / rate)).toInt().toShort() }
            it.write(pcm, pcm.size)
        }
    }

    @Test fun aacIsRecordedAsAdtsAndRemuxedToM4a() {
        for ((rate, channels) in listOf(48000 to 1, 16000 to 1, 48000 to 2)) {
            val adts = File(dir, "rec-$rate-$channels.aac")
            record(ThreadedSink(AdtsSink(adts, rate, channels)), rate, channels, 3)
            assertEquals(0xFF, adts.readBytes()[0].toInt() and 0xFF)
            val m4a = File(dir, "rec-$rate-$channels.m4a")
            AudioCodec.remuxAdts(adts, m4a)
            assertEquals("m4a duration", 3000.0, AudioCodec.durationMs(m4a).toDouble(), 150.0)
            val wav = AudioCodec.decodeToWav(m4a, File(dir, "back-$rate-$channels.wav"))
            assertEquals(rate, wav.sampleRate); assertEquals(channels, wav.channels)
            assertEquals("decoded duration", 3000.0, wav.durationMs.toDouble(), 150.0)
        }
    }

    @Test fun mp3RecordingPlaysBackWithPlatformDecoder() {
        val mp3 = File(dir, "rec.mp3")
        record(ThreadedSink(Mp3Sink(mp3, 44100, 2)), 44100, 2, 3)
        val wav = AudioCodec.decodeToWav(mp3, File(dir, "mp3.wav"))
        assertEquals(44100, wav.sampleRate); assertEquals(2, wav.channels)
        assertEquals(3000.0, wav.durationMs.toDouble(), 150.0)
    }

    @Test fun nativeSpeechAndLlmLibrariesLoad() {
        System.loadLibrary("sherpa-onnx-jni")
        if (android.os.Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }) System.loadLibrary("litertlm_jni")
    }
}
