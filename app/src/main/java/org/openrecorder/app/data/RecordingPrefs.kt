package org.openrecorder.app.data

import android.content.Context
import android.media.MicrophoneDirection
import org.openrecorder.app.core.SummaryDetail

/** The microphone settings a recording situation calls for. [zoom] matters only with a [direction]. */
data class MicSettings(val direction: Int, val zoom: Float, val noise: Boolean, val voice: Boolean, val stereo: Boolean)

private const val ANY = MicrophoneDirection.MIC_DIRECTION_UNSPECIFIED
private const val FRONT = MicrophoneDirection.MIC_DIRECTION_AWAY_FROM_USER
private const val USER = MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER

/**
 * Recording situations, each with its microphone settings and the [onset] (seconds of audio kept before each detected
 * utterance) that transcribed it best. [Auto] starts with its own settings and, once it has heard a few seconds,
 * switches the direction, noise suppression and onset to those of the situation it recognised (the audio source and
 * the channel count cannot change during a recording). [Custom] uses the settings the user set one by one.
 */
enum class RecordingMode(val label: String, val detail: String, val mic: MicSettings?, val onset: Float = 0.5f) {
    Auto("自動", "最初の数秒の音から判断", MicSettings(ANY, 0.5f, noise = false, voice = true, stereo = false)),
    Conference("会議", "大人数・広い部屋", MicSettings(ANY, 0.5f, noise = false, voice = true, stereo = false)),
    Meeting("ミーティング", "少人数・机を囲む", MicSettings(ANY, 0.5f, noise = false, voice = true, stereo = false)),
    Lecture("講義・セミナー", "前方の話者", MicSettings(FRONT, 0.5f, noise = false, voice = true, stereo = false)),
    Interview("インタビュー", "向かいの相手", MicSettings(FRONT, -0.5f, noise = false, voice = true, stereo = false)),
    Memo("音声メモ", "自分の声", MicSettings(USER, 0.5f, noise = true, voice = true, stereo = false)),
    Music("音楽・環境音", "ステレオでそのまま", MicSettings(ANY, 0.5f, noise = false, voice = false, stereo = true), onset = 0.8f),
    Custom("カスタム", "設定画面の各項目のとおり", null);

    companion object {
        /** The mode with this name; unknown or empty names (imports, older recordings) are [Custom]. */
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: Custom
    }
}

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
    val mode: RecordingMode = RecordingMode.Custom,
    val summaryDetail: SummaryDetail = SummaryDetail.Standard,
) {
    /** The microphone settings recordings use: the mode's, or in [RecordingMode.Custom] the user's own. */
    val mic get() = mode.mic ?: MicSettings(direction, zoom, noise, voice, channels == 2)

    /** These options with the microphone settings changed by hand, which makes them [RecordingMode.Custom]. */
    fun custom(change: (MicSettings) -> MicSettings): RecordingOptions = change(mic).let {
        copy(direction = it.direction, zoom = it.zoom, noise = it.noise, voice = it.voice, channels = if (it.stereo) 2 else 1, mode = RecordingMode.Custom)
    }
}

class RecordingPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("recording", Context.MODE_PRIVATE)

    fun load() = RecordingOptions(
        prefs.getInt("rate", 48000).takeIf { it == 16000 || it == 48000 } ?: 48000,
        prefs.getInt("channels", 1).coerceIn(1, 2),
        prefs.getString("format", "aac")!!,
        prefs.getBoolean("voice", false), prefs.getBoolean("noise", false), prefs.getBoolean("live", false),
        prefs.getInt("device", 0), prefs.getInt("direction", MicrophoneDirection.MIC_DIRECTION_UNSPECIFIED), prefs.getFloat("zoom", 0.5f),
        prefs.getFloat("speed", 1f), prefs.getBoolean("skip", false),
        RecordingMode.of(prefs.getString("mode", null)),
        SummaryDetail.entries.firstOrNull { it.name == prefs.getString("summaryDetail", null) } ?: SummaryDetail.Standard)

    fun save(o: RecordingOptions) {
        prefs.edit().putInt("rate", o.sampleRate).putInt("channels", o.channels).putString("format", o.format).putBoolean("voice", o.voice)
            .putBoolean("noise", o.noise).putBoolean("live", o.live).putInt("device", o.device).putInt("direction", o.direction)
            .putFloat("zoom", o.zoom).putFloat("speed", o.playbackSpeed).putBoolean("skip", o.skipSilence).putString("mode", o.mode.name)
            .putString("summaryDetail", o.summaryDetail.name).apply()
    }
}
