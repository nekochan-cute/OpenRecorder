package org.openrecorder.app.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LevelsTest {
    @Test fun decibelScaleMakesSpeechVisible() {
        assertEquals(0f, Levels.fromLinear(0f), 0f)
        assertEquals(1f, Levels.fromLinear(1f), 1e-6f)
        // A -30 dBFS voice is 3% of full scale linearly but almost half height on the dB scale.
        assertEquals(24f / 54f, Levels.fromLinear(0.0316f), 0.01f)
        assertEquals(0f, Levels.fromLinear(0.001f), 0f) // -60 dBFS is below the floor
    }

    @Test fun oneLevelPer50msUsingTheLoudestChannel() {
        val rate = 16000; val frames = rate // one second, stereo
        val pcm = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { f -> pcm.putShort(if (f in 8000 until 8800) 32767 else 0); pcm.putShort(0) }
        val file = File.createTempFile("levels", ".wav").apply { deleteOnExit(); writeBytes(Wav.header(pcm.capacity().toLong(), rate, 2) + pcm.array()) }
        val levels = Levels.read(file)
        assertEquals(20, levels.size)
        assertEquals(255, levels[10].toInt() and 255)
        assertEquals(0, levels[0].toInt() and 255)
    }
}
