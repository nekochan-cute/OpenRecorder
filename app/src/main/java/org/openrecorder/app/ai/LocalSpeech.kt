package org.openrecorder.app.ai

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.openrecorder.app.core.*
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipInputStream

object LocalSpeech {
    private val lock = Mutex()
    fun modelDir(context: Context) = installed(context, "speech-model")
    fun speakerDir(context: Context) = installed(context, "speaker-model")
    private fun installed(context: Context, name: String) = File(context.filesDir, name).also { current ->
        val backup = File(context.filesDir, "$name-old")
        if(!current.exists() && backup.exists()) check(backup.renameTo(current))
    }
    fun available(context: Context) = File(modelDir(context),"am/final.mdl").isFile
    fun speakerAvailable(context: Context) = isSpeakerModel(speakerDir(context))
    private fun isSpeechModel(dir: File) = File(dir,"am/final.mdl").isFile && File(dir,"conf/model.conf").isFile
    private fun isSpeakerModel(dir: File) = File(dir,"final.ext.raw").isFile && File(dir,"mfcc.conf").isFile

    /** Imports either a speech model or a speaker (x-vector) model ZIP, detected from its contents. Returns true for a speaker model. */
    suspend fun importModel(context: Context, uri: Uri): Boolean = lock.withLock {
        val stage = File(context.filesDir,"model-new").apply { deleteRecursively(); mkdirs() }
        try {
            var total = 0L; var entries = 0
            context.contentResolver.openInputStream(uri)!!.use { input ->
                ZipInputStream(input).use { zip ->
                    val bytes = ByteArray(64 * 1024)
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        val entry = zip.nextEntry ?: break
                        require(++entries <= 10000) { "モデルのファイル数が多すぎます" }
                        val output = File(stage,entry.name)
                        require(output.canonicalPath.startsWith(stage.canonicalPath + File.separator)) { "ZIPのパスが不正です" }
                        if(entry.isDirectory) output.mkdirs() else {
                            output.parentFile?.mkdirs()
                            output.outputStream().use { out ->
                                while(true) {
                                    val n = zip.read(bytes); if(n < 0) break
                                    total += n
                                    require(total <= 2L * 1024 * 1024 * 1024) { "モデルの展開サイズは2GBまでです" }
                                    require(stage.usableSpace > 16L * 1024 * 1024) { "空き容量が足りません" }
                                    out.write(bytes,0,n)
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            val candidates = stage.walkTopDown().maxDepth(3).filter { it.isDirectory }.toList()
            val speech = candidates.firstOrNull(::isSpeechModel)
            val speaker = if(speech == null) candidates.firstOrNull(::isSpeakerModel) else null
            val root = speech ?: speaker ?: error("VoskのモデルZIP（音声認識または話者モデル）ではありません")
            // Validate by loading before replacing the installed model.
            if(speech != null) Model(root.path).use { } else SpeakerModel(root.path).use { }
            val name = if(speech != null) "speech-model" else "speaker-model"
            val current = installed(context, name); val old = File(context.filesDir,"$name-old")
            old.deleteRecursively()
            if(current.exists()) check(current.renameTo(old))
            if(!root.renameTo(current)) { old.renameTo(current); error("モデルを配置できませんでした") }
            old.deleteRecursively()
            speaker != null
        } finally { stage.deleteRecursively() }
    }

    suspend fun <T> withRecognizer(context: Context, rate: Int, speakers: Boolean = false, block: suspend (Recognizer) -> T): T = lock.withLock {
        check(available(context)) { "設定からVoskモデルZIPを取り込んでください" }
        Model(modelDir(context).path).use { model ->
            val speakerModel = if(speakers && speakerAvailable(context)) SpeakerModel(speakerDir(context).path) else null
            try {
                val recognizer = if(speakerModel != null) Recognizer(model, rate.toFloat(), speakerModel) else Recognizer(model, rate.toFloat())
                recognizer.use { it.setWords(true); block(it) }
            } finally { speakerModel?.close() }
        }
    }
    fun mono(samples: ShortArray, channels: Int): ShortArray {
        if(channels == 1) return samples
        return ShortArray(samples.size / channels) { i -> (0 until channels).sumOf { samples[i*channels+it].toInt() }.div(channels).toShort() }
    }
    /** One recognized utterance with its optional speaker embedding. */
    class Utterance(val segment: Segment, val vector: FloatArray?, val frames: Int)

    fun parseUtterance(json: String, endMs: Long): Utterance? {
        val j = JSONObject(json); val text = Text.joinTokens(j.optString("text"))
        if(text.isBlank()) return null
        val words = j.optJSONArray("result")
        val start = if(words != null && words.length() > 0) (words.getJSONObject(0).getDouble("start")*1000).toLong() else endMs
        val end = if(words != null && words.length() > 0) (words.getJSONObject(words.length()-1).getDouble("end")*1000).toLong() else endMs + 1
        val spk = j.optJSONArray("spk")
        val vector = spk?.let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
        return Utterance(Segment(start.coerceAtLeast(0),maxOf(start+1,end),text), vector, j.optInt("spk_frames"))
    }
    fun parse(json: String, endMs: Long): List<Segment> = listOfNotNull(parseUtterance(json, endMs)?.segment)

    /** Assigns "話者N" labels from speaker embeddings; leaves labels empty when no embeddings were produced. */
    fun diarize(utterances: List<Utterance>, threshold: Float = 0.5f): List<Segment> {
        val labels = Diarization.cluster(utterances.map { Diarization.Utterance(it.vector, it.frames) }, threshold)
        return utterances.mapIndexed { i, u -> u.segment.copy(speaker = Diarization.label(labels[i])) }
    }
    suspend fun transcribe(context: Context, file: File, speakers: Boolean = true, progress: (Float) -> Unit): List<Segment> {
        val info = Wav.inspect(file)
        return withRecognizer(context,info.sampleRate,speakers) { recognizer ->
            val segments = mutableListOf<Utterance>()
            RandomAccessFile(file,"r").use { input ->
                input.seek(info.offset)
                val bytes = ByteArray(16000 * info.frameBytes)
                var read = 0L
                while(read < info.bytes) {
                    currentCoroutineContext().ensureActive()
                    val n = minOf(bytes.size.toLong(),info.bytes-read).toInt()
                    input.readFully(bytes,0,n); read += n
                    val pcm = ShortArray(n/2) { i -> ((bytes[2*i].toInt() and 255) or (bytes[2*i+1].toInt() shl 8)).toShort() }
                    val mono = mono(pcm,info.channels)
                    if(recognizer.acceptWaveForm(mono,mono.size)) parseUtterance(recognizer.result,read/info.frameBytes*1000/info.sampleRate)?.let(segments::add)
                    progress(read.toFloat()/info.bytes)
                }
                parseUtterance(recognizer.finalResult,info.durationMs)?.let(segments::add)
            }
            if(segments.any { it.vector != null }) diarize(segments) else segments.map { it.segment }
        }
    }
}

/** Bounded queue: recognition overload never stalls capture or drops recorded PCM. */
class LiveTranscriber(context: Context, rate: Int, channels: Int, scope: CoroutineScope, private val onText: (String) -> Unit) {
    private val queue = Channel<ShortArray>(128)
    private val result = CompletableDeferred<List<Segment>>()
    @Volatile private var overloaded = false
    init {
        scope.launch {
            try {
                val segments = LocalSpeech.withRecognizer(context,rate) { rec ->
                    val all = mutableListOf<Segment>(); var frames = 0L
                    for(pcm in queue) {
                        val mono = LocalSpeech.mono(pcm,channels); frames += mono.size
                        if(rec.acceptWaveForm(mono,mono.size)) all.addAll(LocalSpeech.parse(rec.result,frames*1000/rate))
                        val partial = JSONObject(rec.partialResult).optString("partial")
                        onText((all.takeLast(3).joinToString(" ") { it.text } + " " + partial).trim())
                    }
                    all.addAll(LocalSpeech.parse(rec.finalResult,frames*1000/rate)); all.toList()
                }
                if(overloaded) result.completeExceptionally(IllegalStateException("文字起こしが録音速度に追いつきませんでした。保存後に再実行してください"))
                else result.complete(segments)
            } catch(e: Throwable) { result.completeExceptionally(e); onText("文字起こしを中断しました。音声は保存されます。") }
        }
    }
    fun offer(pcm: ShortArray) {
        if(overloaded || result.isCompleted) return
        if(queue.trySend(pcm).isFailure) { overloaded = true; queue.close(); onText("文字起こしが追いつかないため停止しました。音声は保存されます。") }
    }
    suspend fun finish(): List<Segment> { queue.close(); return result.await() }
}
