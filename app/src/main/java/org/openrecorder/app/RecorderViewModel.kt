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
    val player: ExoPlayer = ExoPlayer.Builder(app).setAudioAttributes(
        androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH).build(),true).build()
    private var loaded: String? = null
    init {
        message.value = repo.warning.value
        task("録音データを確認中") { repo.recoverInterrupted(); repo.warning.value?.let { message.value = it } }
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
    fun start(rate: Int, channels: Int, voice: Boolean, noise: Boolean, device: Int, live: Boolean) {
        if(busy.value != null) { message.value = "実行中の処理が終わってから録音してください"; return }
        player.pause()
        try {
            ContextCompat.startForegroundService(app,Intent(app,RecordingService::class.java).setAction(RecordingService.START)
                .putExtra("rate",rate).putExtra("channels",channels).putExtra("voice",voice).putExtra("noise",noise)
                .putExtra("device",device).putExtra("live",live))
        } catch(e: Exception) { message.value = "録音を開始できません: ${e.message}" }
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
    }
    fun stopPlayback() { player.pause() }
    fun togglePlay() {
        if(capture.value.id != null) { message.value = "録音を保存してから再生してください"; return }
        if(player.isPlaying) player.pause() else { if(player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() }
    }
    fun seek(ms: Long) { player.seekTo(ms.coerceAtLeast(0)); position.value = ms.coerceAtLeast(0) }
    fun mark(id: String, at: Long, text: String, photo: Uri? = null) = task("マークを保存中") {
        val name = photo?.let(repo::photo)
        repo.mark(id,at,text.ifBlank { "マーク" },name)
    }
    fun rename(id: String, title: String, folder: String) = task("保存中") {
        require(title.isNotBlank()) { "名前を入力してください" }
        repo.update(id) { it.copy(title=title.trim().take(200),folder=folder.trim().take(100)) }
    }
    fun favorite(id: String) = task("保存中") { repo.update(id) { it.copy(favorite = !it.favorite) } }
    fun trash(id: String) { if(loaded == id) { player.stop(); loaded = null }; task("ごみ箱へ移動中") { repo.update(id) { it.copy(deletedAt = System.currentTimeMillis()) } } }
    fun restore(id: String) = task("復元中") { repo.update(id) { it.copy(deletedAt = null) } }
    fun delete(id: String) = task("削除中") { repo.permanentlyDelete(id) }
    fun edit(id: String, start: Long, end: Long, remove: Boolean) = task("編集版を保存中") {
        val r = repo.get(id) ?: error("録音がありません")
        require(start >= 0 && end > start && end <= r.durationMs) { "範囲を確認してください" }
        val spans = if(remove) Timeline.remove(start,end,r.durationMs) else listOf(Span(start,end))
        val edited = repo.edit(id,spans)
        withContext(Dispatchers.Main) { load(edited.id); message.value = "元音声を残して編集版を保存しました" }
    }
    fun transcribe(id: String, remote: Boolean) = task(if(remote) "APIで文字起こし中" else "端末内で文字起こし中") {
        val r = repo.get(id) ?: error("録音がありません")
        val result = if(remote) RemoteAi().transcribe(repo.file(r),app.aiSettings.load(),r.durationMs)
        else LocalSpeech.transcribe(app,repo.file(r)) { p -> busy.value = "端末内で文字起こし中 ${(p*100).toInt()}%" }
        repo.update(id) { it.copy(segments = result,summary = "") }
        message.value = if(result.isEmpty()) "音声を認識できませんでした" else "文字起こしを保存しました"
    }
    /** Offline extractive summary; also proposes a title from the transcript's keywords. */
    fun summarizeLocal(id: String, template: String) = task("端末内で要約中") {
        val r = repo.get(id) ?: error("録音がありません")
        require(r.segments.isNotEmpty()) { "先に文字起こしを実行してください" }
        val result = withContext(Dispatchers.Default) { LocalSummary.summarize(r.segments,template) }
        repo.update(id) { it.copy(title = result.title.ifBlank { it.title }, summary = result.body) }
        message.value = "端末内で要約しました"
    }
    fun process(id: String, options: ProcessOptions) = task("音声を処理中") {
        val processed = repo.process(id,options) { p -> busy.value = "音声を処理中 ${(p*100).toInt()}%" }
        withContext(Dispatchers.Main) { load(processed.id); message.value = "元音声を残して処理版を保存しました" }
    }
    fun updateMark(id: String, markId: String, text: String) = task("マークを保存中") {
        repo.update(id) { it.copy(marks = it.marks.map { m -> if(m.id == markId) m.copy(text = text.ifBlank { "マーク" }) else m }) }
    }
    fun summarize(id: String, template: String) = task("要約を生成中") {
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
    fun import(uri: Uri, title: String) = task("音声を取り込み中") {
        val r = repo.importAudio(uri,title) { p -> busy.value = "音声を変換中 ${(p*100).toInt()}%" }
        withContext(Dispatchers.Main) { load(r.id) }
    }
    fun config() = app.aiSettings.load()
    fun saveConfig(config: AiConfig) = task("設定を保存中") { app.aiSettings.save(config); message.value = "設定を保存しました" }
    fun importModel(uri: Uri) = task("モデルを展開・検証中") {
        val speaker = LocalSpeech.importModel(app,uri)
        message.value = if(speaker) "話者モデルを取り込みました。次回の端末内文字起こしから話者を分離します" else "端末内の文字起こしモデルを取り込みました"
    }
    fun modelAvailable() = LocalSpeech.available(app)
    fun speakerModelAvailable() = LocalSpeech.speakerAvailable(app)
    /** AAC/M4A copy for sharing: roughly a tenth of the WAV size. */
    fun exportAac(r: Recording, done: (File) -> Unit) = task("M4Aへ変換中") {
        val out = File(app.cacheDir,"exports").apply { mkdirs() }.resolve("${safeName(r.title)}.m4a")
        AudioCodec.encodeAac(repo.file(r),out) { p -> busy.value = "M4Aへ変換中 ${(p*100).toInt()}%" }
        withContext(Dispatchers.Main) { done(out) }
    }
    fun exportText(r: Recording, kind: String): File {
        val (ext, text) = when(kind) {
            "srt" -> "srt" to subtitles(r.segments)
            "vtt" -> "vtt" to webVtt(r.segments)
            "summary" -> "md" to "# ${r.title}\n\n${r.summary}\n"
            "marks" -> "txt" to r.marks.joinToString("\n") { "${timeLabel(it.timeMs)} ${it.text}" }
            else -> "txt" to r.segments.joinToString("\n") { "[${timeLabel(it.startMs)}] ${it.speaker} ${it.text}" }
        }
        return File(app.cacheDir,"exports").apply { mkdirs() }.resolve("${safeName(r.title)}-$kind.$ext").apply { writeText(text) }
    }
    private fun safeName(title: String) = title.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"),"_").take(80).ifBlank { "recording" }
    override fun onCleared() { player.release(); super.onCleared() }
}
