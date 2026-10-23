package org.openrecorder.app

import android.app.Application
import org.openrecorder.app.data.RecordingRepository
import org.openrecorder.app.ai.AiSettings

class RecorderApp : Application() {
    val repository by lazy { RecordingRepository(this) }
    val aiSettings by lazy { AiSettings(this) }
}
