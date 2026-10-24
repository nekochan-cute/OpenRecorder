package org.openrecorder.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openrecorder.app.ai.*
import org.openrecorder.app.audio.AudioCodec
import org.openrecorder.app.audio.RecordingService
import org.openrecorder.app.core.*
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RecorderViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as RecorderApp
    val repo = app.repository
    val recordings = repo.recordings
    val capture = RecordingService.state
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow<String?>(null)
    val position = MutableStateFlow(0L)
    val playing = MutableStateFlow(false)
    val selected = MutableStateFlow<String?>(null)
    /** Waveform levels of the loaded recording (see [Levels]). */
    val levels = MutableStateFlow<ByteArray?>(null)
    private val prefs = org.openrecorder.app.data.RecordingPrefs(app)
    val options = MutableStateFlow(prefs.load())
    val ai = MutableStateFlow(runCatching { app.aiSettings.load() }.getOrDefault(AiConfig()))
    private var rangeStop: Job? = null
    val player: ExoPlayer = ExoPlayer.Builder(app).setAudioAttributes(
        androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH).build(),true).build()
    private var loaded: String? = null
    init {
        message.value = repo.warning.value
        task("録音データを確認中") { repo.recoverInterrupted(); repo.warning.value?.let { message.value = it } }
        player.setPlaybackSpeed(options.value.playbackSpeed); player.skipSilenceEnabled = options.value.skipSilence
        player.addListener(object: Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing.value = isPlaying }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { message.value = "再生できませんでした: ${error.errorCodeName}" }
        })
        viewModelScope.launch {
            while(isActive) { position.value = player.currentPosition.coerceAtLeast(0); delay(100) }
        }
    }
    fun task(label: String, work: suspend () -> Unit) {
        if(busy.value != null) { message.value = "処理の完了をお待ちください"; return }
        busy.value = label
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { work() } }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { message.value = e.message ?: "処理できませんでした" }
            finally { busy.value = null }
        }
    }
    fun setOptions(o: org.openrecorder.app.data.RecordingOptions) {
        options.value = o; prefs.save(o)
        player.setPlaybackSpeed(o.playbackSpeed); player.skipSilenceEnabled = o.skipSilence
    }
    fun start() {
        player.pause()
        val o = options.value
        // A remembered microphone that is no longer connected falls back to the default input.
        val device = o.device.takeIf { id -> app.getSystemService(android.media.AudioManager::class.java)
            .getDevices(android.media.AudioManager.GET_DEVICES_INPUTS).any { it.id == id } } ?: 0
        try {
            ContextCompat.startForegroundService(app,Intent(app,RecordingService::class.java).setAction(RecordingService.START)
                .putExtra("rate",o.sampleRate).putExtra("channels",o.channels).putExtra("voice",o.voice).putExtra("noise",o.noise)
                .putExtra("format",o.format).putExtra("direction",o.direction).putExtra("zoom",o.zoom)
                .putExtra("device",device).putExtra("live",o.live && speechReady()))
        } catch(e: Exception) { message.value = "録音を開始できません" }
    }
    fun command(action: String) {
        try { app.startService(Intent(app,RecordingService::class.java).setAction(action)) }
        catch(e: Exception) { message.value = e.message }
    }
    fun load(id: String) {
        selected.value = id
        if(loaded == id) return
        val r = repo.get(id) ?: return
        if(r.unfinished || r.deletedAt != null) return
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(repo.file(r))))
        player.prepare(); loaded = id; position.value = 0
        levels.value = null
        viewModelScope.launch { levels.value = runCatching { withContext(Dispatchers.IO) { repo.levels(r) } }.getOrNull() }
    }
    /** Plays [start, end) once, for previewing an edit selection. */
    fun playRange(start: Long, end: Long) {
        rangeStop?.cancel()
        seek(start); player.play()
        rangeStop = viewModelScope.launch {
            while(isActive && player.isPlaying && player.currentPosition < end) delay(30)
            if(player.currentPosition >= end) player.pause()
        }
    }
    fun stopPlayback() { player.pause() }
    fun togglePlay() {
        if(capture.value.id != null) { message.value = "録音を保存してから再生してください"; return }
        if(player.isPlaying) player.pause() else { if(player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() }
    }
    fun seek(ms: Long) { player.seekTo(ms.coerceAtLeast(0)); position.value = ms.coerceAtLeast(0) }
    /** Marks never wait for other work: they are taken while recording. */
    fun mark(id: String, at: Long, text: String = "", photo: Uri? = null) {
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { repo.mark(id,at,text.trim(),photo?.let(repo::photo)) } }
            catch(e: Exception) { message.value = e.message ?: "マークを保存できませんでした" }
        }
    }
    fun rename(id: String, title: String) = task("保存中") {
        require(title.isNotBlank()) { "名前を入力してください" }
        repo.update(id) { it.copy(title=title.trim().take(200)) }
    }
    fun moveToFolder(id: String, folder: String) = task("保存中") { repo.update(id) { it.copy(folder=folder.trim().take(100)) } }
    fun emptyTrash() = task("削除中") { repo.recordings.value.filter { it.deletedAt != null && !it.unfinished }.forEach { repo.permanentlyDelete(it.id) } }
    fun favorite(id: String) = task("保存中") { repo.update(id) { it.copy(favorite = !it.favorite) } }
    fun trash(id: String) { if(loaded == id) { player.stop(); loaded = null }; task("ごみ箱へ移動中") { repo.update(id) { it.copy(deletedAt = System.currentTimeMillis()) } } }
    fun restore(id: String) = task("復元中") { repo.update(id) { it.copy(deletedAt = null) } }
    fun delete(id: String) = task("削除中") { repo.permanentlyDelete(id) }
    fun edit(id: String, start: Long, end: Long, remove: Boolean, done: (String) -> Unit) = task("保存中") {
        val r = repo.get(id) ?: error("録音がありません")
        require(start >= 0 && end > start && end <= r.durationMs) { "範囲を選択してください" }
        val spans = if(remove) Timeline.remove(start,end,r.durationMs) else listOf(Span(start,end))
        val edited = repo.edit(id,spans)
        withContext(Dispatchers.Main) { load(edited.id); done(edited.id); message.value = "編集版を保存しました" }
    }
    fun transcribe(id: String, remote: Boolean) = task("文字起こし中") {
        val r = repo.get(id) ?: error("録音がありません")
        val wav = repo.wav(r) { p -> busy.value = "準備中 ${(p*100).toInt()}%" }
        val result = if(remote) RemoteAi().transcribe(wav,app.aiSettings.load(),r.durationMs)
        else Speech.transcribe(app,wav) { p -> busy.value = "文字起こし中 ${(p*100).toInt()}%" }
        repo.update(id) { it.copy(segments = result,summary = "") }
        if(result.isEmpty()) message.value = "音声を認識できませんでした"
    }
    /** Offline extractive summary; also proposes a title from the transcript's keywords. */
    fun summarizeLocal(id: String, template: String) = task("要約中") {
        val r = repo.get(id) ?: error("録音がありません")
        require(r.segments.isNotEmpty()) { "先に文字起こしを実行してください" }
        val result = withContext(Dispatchers.Default) { LocalSummary.summarize(r.segments,template) }
        repo.update(id) { it.copy(title = result.title.ifBlank { it.title }, summary = result.body) }
    }
    fun process(id: String, options: ProcessOptions, done: (String) -> Unit) = task("音声を処理中") {
        val processed = repo.process(id,options) { p -> busy.value = "音声を処理中 ${(p*100).toInt()}%" }
        withContext(Dispatchers.Main) { load(processed.id); done(processed.id); message.value = "処理した音声を保存しました" }
    }
    fun updateMark(id: String, markId: String, text: String) = task("マークを保存中") {
        repo.update(id) { it.copy(marks = it.marks.map { m -> if(m.id == markId) m.copy(text = text.trim()) else m }) }
    }
    /** Generative summary with the on-device LLM, in parts for long recordings. */
    fun summarizeWithLlm(id: String, template: String) = task("要約中") {
        val r = repo.get(id) ?: error("録音がありません")
        val (title, body) = LocalLlm.use(app) { model -> LlmTasks.summarize(r.segments, template, model) { p -> busy.value = "要約中 ${(p*100).toInt()}%" } }
        repo.update(id) { it.copy(title = title.ifBlank { it.title }, summary = body) }
    }
    /** Translates the transcript sentence by sentence with the on-device LLM or the AI service. */
    fun translate(id: String, language: String, remote: Boolean) = task("翻訳中") {
        val r = repo.get(id) ?: error("録音がありません")
        require(r.segments.isNotEmpty()) { "先に文字起こしをしてください" }
        val progress = { p: Float -> busy.value = "翻訳中 ${(p*100).toInt()}%" }
        val translated = if(remote) LlmTasks.translate(r.segments, language, RemoteAi().generator(app.aiSettings.load()), progress = progress)
            else LocalLlm.use(app) { model -> LlmTasks.translate(r.segments, language, model, progress = progress) }
        repo.update(id) { it.copy(segments = translated, translationLanguage = language) }
    }
    fun clearTranslation(id: String) = task("保存中") { repo.update(id) { it.copy(segments = it.segments.map { s -> s.copy(translation = "") }, translationLanguage = "") } }
    fun merge(ids: List<String>, done: (String) -> Unit) = task("結合中") {
        val merged = repo.merge(ids) { p -> busy.value = "結合中 ${(p*100).toInt()}%" }
        withContext(Dispatchers.Main) { done(merged.id) }
    }
    fun summarize(id: String, template: String) = task("要約中") {
        val r = repo.get(id) ?: error("録音がありません")
        val (title, summary) = RemoteAi().summarize(r.segments,app.aiSettings.load(),template)
        repo.update(id) { it.copy(title = title.ifBlank { it.title }, summary = summary) }
    }
    fun updateSegment(id: String, index: Int, text: String, speaker: String) = task("文章を保存中") {
        repo.update(id) { r -> r.copy(segments = r.segments.mapIndexed { i,s -> if(i == index) s.copy(text=text,speaker=speaker.trim()) else s }, summary="") }
    }
    fun renameSpeaker(id: String, old: String, next: String) = task("話者名を保存中") {
        repo.update(id) { it.copy(segments = it.segments.map { s -> if(s.speaker == old) s.copy(speaker=next.trim()) else s }, summary="") }
    }
    fun deleteMark(id: String, markId: String) = task("マークを削除中") { repo.update(id) { it.copy(marks=it.marks.filterNot { m -> m.id == markId }) } }
    fun import(uri: Uri, title: String) = task("取り込み中") {
        repo.importAudio(uri,title) { p -> busy.value = "取り込み中 ${(p*100).toInt()}%" }
    }
    fun saveAi(config: AiConfig) = task("保存中") { app.aiSettings.save(config); ai.value = config }
    suspend fun models(provider: Provider, config: AiConfig, forTranscription: Boolean): List<String> {
        val all = RemoteAi().listModels(config.baseUrl(provider,forTranscription), config.keys[provider].orEmpty())
        return if(forTranscription) all.filter { it.contains("whisper") || it.contains("transcribe") }.ifEmpty { all } else all
    }
    /** OpenRouter sign-in; returns the issued key. */
    suspend fun loginOpenRouter(openBrowser: (String) -> Unit): String =
        OpenRouterAuth.login(app.packageName) { url -> withContext(Dispatchers.Main) { openBrowser(url) } }
    val models = ModelStore(app)
    val downloads = ModelDownloadService.state
    /** Bumped when installed models change, so screens re-read the store. */
    val modelsVersion = MutableStateFlow(0)
    fun download(spec: ModelSpec) = ModelDownloadService.start(app, spec.id)
    fun cancelDownload(spec: ModelSpec) = ModelDownloadService.cancel(app, spec.id)
    fun deleteModel(spec: ModelSpec) { models.delete(spec); modelsVersion.value++ }
    fun chooseModel(spec: ModelSpec) { models.choose(spec); modelsVersion.value++ }
    fun importLlm(uri: Uri) = task("モデルを追加中") {
        models.importLlm(uri) { p -> busy.value = "モデルを追加中 ${(p*100).toInt()}%" }
        models.choose(ModelCatalog.customLlm); modelsVersion.value++
    }
    fun speechReady() = SpeechEngine.ready(models)
    fun speakerReady() = models.installed(ModelCatalog.speaker)
    fun llmReady() = LocalLlm.supported && LocalLlm.ready(app)
    /** AAC/M4A copy for sharing: roughly a tenth of the WAV size. */
    fun exportAac(r: Recording, done: (File) -> Unit) = task("変換中") {
        val out = File(app.cacheDir,"exports").apply { mkdirs() }.resolve("${safeName(r.title)}.m4a")
        if(repo.format(r) == org.openrecorder.app.audio.RecordFormat.Aac) repo.file(r).copyTo(out, overwrite = true)
        else AudioCodec.encodeAac(repo.wav(r),out) { p -> busy.value = "変換中 ${(p*100).toInt()}%" }
        withContext(Dispatchers.Main) { done(out) }
    }
    fun exportMp3(r: Recording, done: (File) -> Unit) = task("変換中") {
        val out = File(app.cacheDir,"exports").apply { mkdirs() }.resolve("${safeName(r.title)}.mp3")
        if(repo.format(r) == org.openrecorder.app.audio.RecordFormat.Mp3) repo.file(r).copyTo(out, overwrite = true)
        else org.openrecorder.app.audio.Mp3.encode(repo.wav(r),out) { p -> busy.value = "変換中 ${(p*100).toInt()}%" }
        withContext(Dispatchers.Main) { done(out) }
    }
    fun exportWav(r: Recording, done: (File) -> Unit) = task("変換中") {
        val wav = repo.wav(r) { p -> busy.value = "変換中 ${(p*100).toInt()}%" }
        withContext(Dispatchers.Main) { done(wav) }
    }
    fun exportText(r: Recording, kind: String): File {
        val (ext, text) = when(kind) {
            "srt" -> "srt" to subtitles(r.segments)
            "vtt" -> "vtt" to webVtt(r.segments)
            "srt-translation" -> "srt" to subtitles(r.segments.map { it.copy(text = it.translation.ifBlank { it.text }) })
            "vtt-translation" -> "vtt" to webVtt(r.segments.map { it.copy(text = it.translation.ifBlank { it.text }) })
            "translation" -> "txt" to r.segments.joinToString("\n") { "[${timeLabel(it.startMs)}] ${it.speaker} ${it.translation.ifBlank { it.text }}".trim() }
            "summary" -> "md" to "# ${r.title}\n\n${r.summary}\n"
            "marks" -> "txt" to r.marks.joinToString("\n") { "${timeLabel(it.timeMs)} ${it.text}".trim() }
            else -> "txt" to r.segments.joinToString("\n") { "[${timeLabel(it.startMs)}] ${it.speaker} ${it.text}" }
        }
        return File(app.cacheDir,"exports").apply { mkdirs() }.resolve("${safeName(r.title)}-$kind.$ext").apply { writeText(text) }
    }
    private fun safeName(title: String) = title.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"),"_").take(80).ifBlank { "recording" }
    override fun onCleared() { player.release(); super.onCleared() }
}
