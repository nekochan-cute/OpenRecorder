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
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipInputStream

object LocalSpeech {
    private val lock = Mutex()
    fun modelDir(context: Context) = File(context.filesDir,"speech-model").also { current ->
        val backup = File(context.filesDir,"speech-model-old")
        if(!current.exists() && backup.exists()) check(backup.renameTo(current))
    }
    fun available(context: Context) = File(modelDir(context),"am/final.mdl").isFile

    suspend fun importModel(context: Context, uri: Uri) = lock.withLock {
        val stage = File(context.filesDir,"speech-model-new").apply { deleteRecursively(); mkdirs() }
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
            val root = stage.walkTopDown().maxDepth(3).firstOrNull { it.isDirectory && File(it,"am/final.mdl").isFile && File(it,"conf/model.conf").isFile }
                ?: error("VoskモデルZIPではありません")
            Model(root.path).use { /* Validate before replacing the installed model. */ }
            val current = modelDir(context); val old = File(context.filesDir,"speech-model-old")
            old.deleteRecursively()
            if(current.exists()) check(current.renameTo(old))
            if(!root.renameTo(current)) { old.renameTo(current); error("モデルを配置できませんでした") }
            old.deleteRecursively()
        } finally { stage.deleteRecursively() }
    }

    suspend fun <T> withRecognizer(context: Context, rate: Int, block: suspend (Recognizer) -> T): T = lock.withLock {
        check(available(context)) { "設定からVoskモデルZIPを取り込んでください" }
        Model(modelDir(context).path).use { model ->
            Recognizer(model, rate.toFloat()).use { recognizer -> recognizer.setWords(true); block(recognizer) }
        }
    }
    fun mono(samples: ShortArray, channels: Int): ShortArray {
        if(channels == 1) return samples
        return ShortArray(samples.size / channels) { i -> (0 until channels).sumOf { samples[i*channels+it].toInt() }.div(channels).toShort() }
    }
    fun parse(json: String, endMs: Long): List<Segment> {
        val j = JSONObject(json); val text = j.optString("text").trim()
        if(text.isBlank()) return emptyList()
        val words = j.optJSONArray("result")
        val start = if(words != null && words.length() > 0) (words.getJSONObject(0).getDouble("start")*1000).toLong() else endMs
        val end = if(words != null && words.length() > 0) (words.getJSONObject(words.length()-1).getDouble("end")*1000).toLong() else endMs + 1
        return listOf(Segment(start.coerceAtLeast(0),maxOf(start+1,end),text))
    }
    suspend fun transcribe(context: Context, file: File, progress: (Float) -> Unit): List<Segment> {
        val info = Wav.inspect(file)
        return withRecognizer(context,info.sampleRate) { recognizer ->
            val segments = mutableListOf<Segment>()
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
                    if(recognizer.acceptWaveForm(mono,mono.size)) segments.addAll(parse(recognizer.result,read/info.frameBytes*1000/info.sampleRate))
                    progress(read.toFloat()/info.bytes)
                }
                segments.addAll(parse(recognizer.finalResult,info.durationMs))
            }
            segments
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
