package org.openrecorder.app.data

import android.content.Context
import android.media.MicrophoneDirection

data class RecordingOptions(
    val sampleRate: Int = 48000,
    val channels: Int = 1,
    /** "wav", "aac" or "mp3". */
    val format: String = "aac",
    val voice: Boolean = false,
    val noise: Boolean = false,
    val live: Boolean = false,
    /** AudioDeviceInfo id; 0 = system default. Ids change when a device reconnects. */
    val device: Int = 0,
    /** MicrophoneDirection constant: unspecified, towards the user, away from the user. */
    val direction: Int = MicrophoneDirection.MIC_DIRECTION_UNSPECIFIED,
    /** Field dimension: -1 wide … 0 default … 1 narrow (zoomed in). */
    val zoom: Float = 0.5f,
    val playbackSpeed: Float = 1f,
    val skipSilence: Boolean = false,
)

class RecordingPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("recording", Context.MODE_PRIVATE)

    fun load() = RecordingOptions(
        prefs.getInt("rate", 48000).takeIf { it == 16000 || it == 48000 } ?: 48000,
        prefs.getInt("channels", 1).coerceIn(1, 2),
        prefs.getString("format", "aac")!!,
        prefs.getBoolean("voice", false), prefs.getBoolean("noise", false), prefs.getBoolean("live", false),
        prefs.getInt("device", 0), prefs.getInt("direction", MicrophoneDirection.MIC_DIRECTION_UNSPECIFIED), prefs.getFloat("zoom", 0.5f),
        prefs.getFloat("speed", 1f), prefs.getBoolean("skip", false))

    fun save(o: RecordingOptions) {
        prefs.edit().putInt("rate", o.sampleRate).putInt("channels", o.channels).putString("format", o.format).putBoolean("voice", o.voice)
            .putBoolean("noise", o.noise).putBoolean("live", o.live).putInt("device", o.device).putInt("direction", o.direction)
            .putFloat("zoom", o.zoom).putFloat("speed", o.playbackSpeed).putBoolean("skip", o.skipSilence).apply()
    }
}
