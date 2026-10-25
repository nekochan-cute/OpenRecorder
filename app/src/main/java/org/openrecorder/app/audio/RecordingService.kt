package org.openrecorder.app.audio

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.media.audiofx.NoiseSuppressor
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.openrecorder.app.*
import org.openrecorder.app.core.Levels
import org.openrecorder.app.core.Segment
import org.openrecorder.app.ai.LiveTranscriber
import java.io.RandomAccessFile

/**
 * [levels]: recent waveform levels (0–1, dB scale), one per 50 ms, newest last.
 * [live]: captions so far with speakers; [partial]: provisional text of the utterance being spoken;
 * [speaking] while the current utterance is still open.
 * [directional]: null when no direction was requested, else whether the device accepted it.
 */
data class CaptureState(val id: String? = null, val paused: Boolean = false, val durationMs: Long = 0,
    val levels: List<Float> = emptyList(), val error: String? = null, val live: List<Segment> = emptyList(), val partial: String = "", val speaking: Boolean = false,
    val liveStatus: String = "", val input: String = "", val noiseReduction: Boolean = false, val directional: Boolean? = null,
    val saving: Boolean = false, val silenced: Boolean = false)

/** Holds the SCO link for a Bluetooth microphone and restores the previous audio mode on close. */
class BluetoothRoute private constructor(private val manager: AudioManager, private val previousMode: Int) : AutoCloseable {
    override fun close() {
        if(Build.VERSION.SDK_INT >= 31) manager.clearCommunicationDevice() else legacySco(manager, false)
        manager.mode = previousMode
    }
    companion object {
        suspend fun open(manager: AudioManager, input: AudioDeviceInfo): BluetoothRoute {
            val route = BluetoothRoute(manager, manager.mode)
            try {
                manager.mode = AudioManager.MODE_IN_COMMUNICATION
                if(Build.VERSION.SDK_INT >= 31) {
                    val candidates = manager.availableCommunicationDevices.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                    val output = candidates.firstOrNull { it.address == input.address } ?: candidates.firstOrNull()
                    check(output != null && manager.setCommunicationDevice(output)) { "Bluetoothマイクに接続できませんでした" }
                } else { legacySco(manager, true); delay(1500) } // the legacy flag reports the request, not the link
                val linked = withTimeoutOrNull(4000) {
                    while(!connected(manager)) delay(100)
                    true
                }
                check(linked == true) { "Bluetoothマイクの接続がタイムアウトしました" }
                return route
            } catch(e: Exception) { runCatching { route.close() }; throw e }
        }
        @Suppress("DEPRECATION") // Android 10/11 have no communication-device API.
        private fun legacySco(manager: AudioManager, on: Boolean) {
            if(on) manager.startBluetoothSco() else manager.stopBluetoothSco()
            manager.isBluetoothScoOn = on
        }
        @Suppress("DEPRECATION")
        private fun connected(manager: AudioManager) = if(Build.VERSION.SDK_INT >= 31)
            manager.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO else manager.isBluetoothScoOn
    }
}

class RecordingService : Service() {
    companion object {
        private val mutableState = MutableStateFlow(CaptureState())
        val state = mutableState.asStateFlow()
        const val START = "start"; const val PAUSE = "pause"; const val STOP = "stop"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var paused = false
    @Volatile private var stopping = false
    private var wake: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when(intent?.action) {
            START -> if (job == null) {
                try {
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.createNotificationChannel(NotificationChannel("capture","録音中",NotificationManager.IMPORTANCE_LOW))
                    if(Build.VERSION.SDK_INT >= 30) startForeground(10, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                    else startForeground(10, notification())
                    paused = false; stopping = false
                    job = scope.launch { capture(intent) }
                } catch(e: Exception) { mutableState.value = CaptureState(error = "録音を開始できません: ${e.message}"); stopSelf() }
            }
            PAUSE -> if(job != null && !stopping) { paused = !paused; mutableState.update { it.copy(paused = paused) }; notifyUpdate() } else if(job == null) stopSelf()
            STOP -> if(job == null) stopSelf() else { stopping = true; mutableState.update { it.copy(saving = true) } }
        }
        return START_NOT_STICKY
    }
    private fun notification(): Notification {
        // The launcher's intent brings the existing screen back instead of stacking a new one on top.
        val open = PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun command(action: String, code: Int) = PendingIntent.getService(this,code,Intent(this,RecordingService::class.java).setAction(action),PendingIntent.FLAG_IMMUTABLE)
        val s = mutableState.value
        return NotificationCompat.Builder(this,"capture").setSmallIcon(R.drawable.ic_recorder)
            .setContentTitle(if(mutableState.value.saving) "保存中" else if(paused) "一時停止中" else "録音中")
            .setContentText(org.openrecorder.app.core.timeLabel(s.durationMs)).setOngoing(true).setContentIntent(open)
            .addAction(0, if(paused) "再開" else "一時停止",command(PAUSE,1))
            .addAction(0,"停止",command(STOP,2)).build()
    }
    private fun notifyUpdate() { getSystemService(NotificationManager::class.java).notify(10,notification()) }

    // Lint cannot follow the nullable field across this coroutine. Every acquired lock is
    // released in finally; each native release is isolated so it cannot bypass that cleanup.
    @SuppressLint("MissingPermission", "WakelockTimeout", "Wakelock")
    private suspend fun capture(intent: Intent) {
        val app = application as RecorderApp; val repo = app.repository
        var recorder: AudioRecord? = null; var suppressor: NoiseSuppressor? = null
        var recordingCallback: AudioManager.AudioRecordingCallback? = null
        var id: String? = null; var live: LiveTranscriber? = null; var sink: PcmSink? = null
        var frames = 0L; var rate = 48000
        val allLevels = java.io.ByteArrayOutputStream()
        val audioManager = getSystemService(AudioManager::class.java)
        var bluetooth: BluetoothRoute? = null
        try {
            check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            rate = intent.getIntExtra("rate",48000).also { require(it in listOf(16000,48000)) }
            val channels = intent.getIntExtra("channels",1).also { require(it in 1..2) }
            val format = RecordFormat.of(intent.getStringExtra("format") ?: "wav")
            val mask = if(channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
            val minBuffer = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
            check(minBuffer > 0) { "この録音形式は端末に対応していません" }
            val source = if(intent.getBooleanExtra("voice",false)) MediaRecorder.AudioSource.VOICE_RECOGNITION else MediaRecorder.AudioSource.MIC
            val audio = AudioRecord.Builder().setAudioSource(source).setAudioFormat(AudioFormat.Builder()
                .setSampleRate(rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minBuffer * 2, rate / 10 * channels * 2)).build()
            recorder = audio
            check(audio.state == AudioRecord.STATE_INITIALIZED)
            val deviceId = intent.getIntExtra("device",0)
            if(deviceId != 0) {
                val device = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == deviceId }
                    ?: error("選択したマイクが接続されていません")
                // A Bluetooth headset microphone is only available over an SCO (call-quality) link.
                if(device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) bluetooth = BluetoothRoute.open(audioManager, device)
                check(audio.setPreferredDevice(device)) { "マイクを選択できませんでした" }
            }
            // Directional capture is a hint to the device's audio processing (beamforming); support varies by phone.
            val direction = intent.getIntExtra("direction", MicrophoneDirection.MIC_DIRECTION_UNSPECIFIED)
            val directional = if(direction == MicrophoneDirection.MIC_DIRECTION_UNSPECIFIED) null else
                audio.setPreferredMicrophoneDirection(direction) && audio.setPreferredMicrophoneFieldDimension(intent.getFloatExtra("zoom",0f).coerceIn(-1f,1f))
            if(intent.getBooleanExtra("noise",false) && NoiseSuppressor.isAvailable()) {
                suppressor = NoiseSuppressor.create(audio.audioSessionId)?.apply { enabled = true }
            }
            val r = repo.create(rate,channels,format); id = r.id
            sink = when(format) {
                RecordFormat.Wav -> WavSink(repo.file(r), rate, channels)
                RecordFormat.Aac -> ThreadedSink(AdtsSink(repo.file(r), rate, channels))
                RecordFormat.Mp3 -> ThreadedSink(Mp3Sink(repo.file(r), rate, channels))
            }
            mutableState.value = CaptureState(id = id, noiseReduction = suppressor?.enabled == true, directional = directional)
            recordingCallback = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    val silenced = configs.any { it.clientAudioSessionId == audio.audioSessionId && it.isClientSilenced }
                    mutableState.update { if(it.id == r.id) it.copy(silenced = silenced) else it }
                }
            }
            audio.registerAudioRecordingCallback(mainExecutor,recordingCallback)
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"OpenRecorder:capture").apply { acquire() }
            if(intent.getBooleanExtra("live",false)) {
                live = try { LiveTranscriber(this, rate, channels, scope) { segments, partial, speaking ->
                    mutableState.update { if(it.id == r.id) it.copy(live = segments, partial = partial, speaking = speaking) else it } } }
                catch(e: Throwable) { mutableState.update { it.copy(liveStatus = "文字起こしを開始できませんでした") }; null }
            }
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            val buffer = ShortArray(rate / 50 * channels)
            var actuallyPaused = false; var lastNotify = 0L; var lastPublish = 0L
            val meter = ArrayDeque<Float>()
            val framesPerLevel = rate / Levels.PER_SECOND; var levelFrames = 0; var levelPeak = 0
            while(!stopping && currentCoroutineContext().isActive) {
                if(paused) {
                    if(!actuallyPaused) { audio.stop(); actuallyPaused = true }
                    delay(25); continue
                }
                if(actuallyPaused) { audio.startRecording(); actuallyPaused = false }
                val n = audio.read(buffer,0,buffer.size,AudioRecord.READ_BLOCKING)
                check(n >= 0) { "マイク読み取りエラー: $n" }
                if(n == 0 || paused) continue
                for(i in 0 until n) {
                    levelPeak = maxOf(levelPeak,kotlin.math.abs(buffer[i].toInt()))
                    if(i % channels == channels - 1 && ++levelFrames == framesPerLevel) {
                        val level = Levels.fromLinear(levelPeak / 32768f)
                        meter.addLast(level); if(meter.size > 600) meter.removeFirst()
                        allLevels.write(Math.round(level * 255))
                        levelFrames = 0; levelPeak = 0
                    }
                }
                sink.write(buffer, n)
                frames += n / channels
                live?.offer(buffer.copyOf(n))
                live?.problem?.let { problem -> if(mutableState.value.liveStatus.isEmpty()) mutableState.update { it.copy(liveStatus = problem) } }
                val duration = frames * 1000 / rate
                if(duration - lastPublish >= 50) {
                    lastPublish = duration
                    mutableState.update { it.copy(durationMs = duration, levels = meter.toList(), input = audio.routedDevice?.productName?.toString() ?: "マイク") }
                }
                if(duration - lastNotify >= 1000) { lastNotify = duration; notifyUpdate() }
            }
        } catch(e: Exception) {
            mutableState.update { it.copy(error = "録音: ${e.message ?: "処理が中断されました"}") }
        } finally {
            recordingCallback?.let { callback -> runCatching { recorder?.unregisterAudioRecordingCallback(callback) } }
            runCatching { recorder?.stop() }; runCatching { recorder?.release() }; runCatching { suppressor?.release() }
            runCatching { bluetooth?.close() }
            if(wake?.isHeld == true) wake?.release(); wake = null
            val closed = runCatching { sink?.close() }
            val liveSegments = runCatching { withTimeout(60000) { live?.finish() } }.getOrNull()?.takeIf { it.isNotEmpty() }
            id?.let { key ->
                runCatching {
                    closed.getOrThrow()
                    repo.complete(key, frames * 1000 / rate, liveSegments, allLevels.toByteArray())
                }.onFailure { mutableState.update { it.copy(error = "保存処理が中断されました。次回起動時に回収します。") } }
                repo.endCapture(key)
            }
            mutableState.update { it.copy(id = null,paused = false,saving = false) }
            withContext(NonCancellable + Dispatchers.Main) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
    }
    override fun onDestroy() { stopping = true; scope.cancel(); super.onDestroy() }
}
