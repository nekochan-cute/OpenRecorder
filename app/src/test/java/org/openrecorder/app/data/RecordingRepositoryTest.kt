package org.openrecorder.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openrecorder.app.audio.RecordFormat
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordingRepositoryTest {
    private val context = RuntimeEnvironment.getApplication()

    /** 100 ms of 16 kHz mono audio, as the capture would have written it. */
    private val audio = ByteArray(3200)

    @Test fun recoveryLeavesTheRecordingBeingCapturedAlone() {
        val repo = RecordingRepository(context)
        val r = repo.create(16000, 1, RecordFormat.Wav)
        repo.file(r).appendBytes(audio)

        // A second screen (e.g. opened from the recording notification) runs recovery mid-capture.
        repo.recoverInterrupted()

        val after = repo.get(r.id)!!
        assertTrue(after.unfinished)
        assertEquals(r.title, after.title)
        assertEquals(r.fileName, after.fileName)
        assertEquals(44L + audio.size, repo.file(r).length())
    }

    @Test fun recoveryRepairsARecordingLeftByAnEarlierProcess() {
        val earlier = RecordingRepository(context)
        val r = earlier.create(16000, 1, RecordFormat.Wav)
        earlier.file(r).appendBytes(audio)

        val next = RecordingRepository(context)
        next.recoverInterrupted()

        val after = next.get(r.id)!!
        assertFalse(after.unfinished)
        assertEquals(100L, after.durationMs)
    }

    @Test fun recoveryRepairsACaptureThatEndedWithoutCompleting() {
        val repo = RecordingRepository(context)
        val r = repo.create(16000, 1, RecordFormat.Wav)
        repo.file(r).appendBytes(audio)
        repo.endCapture(r.id)

        repo.recoverInterrupted()

        val after = repo.get(r.id)!!
        assertFalse(after.unfinished)
        assertEquals(100L, after.durationMs)
    }

    @Test fun theRecordingModeIsKeptAndMergedOnlyWhenShared() {
        val repo = RecordingRepository(context)
        fun recorded(mode: String) = repo.create(16000, 1, RecordFormat.Wav).also {
            repo.file(it).appendBytes(audio); repo.complete(it.id, 100, null, null, mode); repo.endCapture(it.id)
        }
        val music = recorded("Music"); val music2 = recorded("Music"); val memo = recorded("Memo")

        assertEquals("Music", RecordingRepository(context).get(music.id)!!.mode)
        assertEquals("Music", repo.merge(listOf(music.id, music2.id)).mode)
        assertEquals("", repo.merge(listOf(music.id, memo.id)).mode)
    }
}
