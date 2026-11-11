package org.openrecorder.app.data

import android.media.MicrophoneDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingOptionsTest {
    private val own = RecordingOptions(channels = 2, voice = false, noise = true, direction = MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER, zoom = 0.8f)

    @Test fun modeSettingsOverrideTheUsersOwn() {
        val lecture = own.copy(mode = RecordingMode.Lecture)
        assertEquals(RecordingMode.Lecture.mic, lecture.mic)
        assertEquals(MicSettings(MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER, 0.8f, noise = true, voice = false, stereo = true),
            lecture.copy(mode = RecordingMode.Custom).mic)
    }

    @Test fun changingOneSettingByHandKeepsTheModesOthersAndBecomesCustom() {
        val changed = own.copy(mode = RecordingMode.Lecture).custom { it.copy(stereo = true) }
        assertEquals(RecordingMode.Custom, changed.mode)
        assertEquals(RecordingMode.Lecture.mic!!.copy(stereo = true), changed.mic)
        assertEquals(2, changed.channels)
    }
}
