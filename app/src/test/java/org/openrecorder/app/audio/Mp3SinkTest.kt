package org.openrecorder.app.audio

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

class Mp3SinkTest {
    @Test fun encodesMpegFramesAtTheRequestedBitRate() {
        for ((rate, channels) in listOf(48000 to 1, 44100 to 2, 16000 to 1)) {
            val file = File.createTempFile("rec", ".mp3").apply { deleteOnExit() }
            val bitRate = RecordFormat.bitRate(rate, channels)
            Mp3Sink(file, rate, channels).use { sink ->
                repeat(5) { second ->
                    val pcm = ShortArray(rate * channels) { i -> (8000 * sin(2 * PI * 440 * (i / channels) / rate)).toInt().toShort() }
                    sink.write(pcm, pcm.size)
                }
            }
            val bytes = file.readBytes()
            assertEquals("frame sync", 0xFF, bytes[0].toInt() and 0xFF)
            assertEquals("frame sync", 0xE0, bytes[1].toInt() and 0xE0)
            val expected = bitRate / 8.0 * 5
            assertEquals("size at $rate Hz × $channels", expected, bytes.size.toDouble(), expected * 0.15)
        }
    }

    @Test fun formatFollowsTheFileExtension() {
        assertEquals(RecordFormat.Aac, RecordFormat.ofFile("a.m4a")); assertEquals(RecordFormat.Aac, RecordFormat.ofFile("a.aac"))
        assertEquals(RecordFormat.Mp3, RecordFormat.ofFile("a.MP3")); assertEquals(RecordFormat.Wav, RecordFormat.ofFile("a.wav"))
    }
}
