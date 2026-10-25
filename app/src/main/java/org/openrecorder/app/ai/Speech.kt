package org.openrecorder.app.ai

import android.content.Context
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.openrecorder.app.core.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * On-device speech with sherpa-onnx: Silero VAD cuts speech into utterances, an offline recognizer
 * (ReazonSpeech or SenseVoice) transcribes each one, and a speaker-embedding model labels who spoke.
 */
class SpeechEngine(store: ModelStore, speech: ModelSpec, speakers: Boolean) : AutoCloseable {
    private val vadModel = store.file(ModelCatalog.vad, "silero_vad.onnx").path
    private val recognizer: OfflineRecognizer
    private val extractor: SpeakerEmbeddingExtractor?

    init {
        val model = when (speech.id) {
            ModelCatalog.reazon.id -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(store.file(speech, "encoder.onnx").path, store.file(speech, "decoder.onnx").path, store.file(speech, "joiner.onnx").path),
                tokens = store.file(speech, "tokens.txt").path, numThreads = 2, modelType = "transducer")
            else -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(model = store.file(speech, "model.onnx").path, language = "auto", useInverseTextNormalization = true),
                tokens = store.file(speech, "tokens.txt").path, numThreads = 2)
        }
        recognizer = OfflineRecognizer(null, OfflineRecognizerConfig(featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80), modelConfig = model))
        extractor = if (speakers && store.installed(ModelCatalog.speaker))
            SpeakerEmbeddingExtractor(null, SpeakerEmbeddingExtractorConfig(model = store.file(ModelCatalog.speaker, "speaker.onnx").path, numThreads = 1))
        else null
    }

    fun newVad() = Vad(null, VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(model = vadModel, threshold = 0.5f,
        minSilenceDuration = 0.35f, minSpeechDuration = 0.25f, windowSize = WINDOW, maxSpeechDuration = 20f), sampleRate = RATE, numThreads = 1))

    fun recognize(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally { stream.release() }
    }

    fun embed(samples: FloatArray): FloatArray? {
        val e = extractor ?: return null
        val stream = e.createStream()
        try {
            stream.acceptWaveform(samples, RATE); stream.inputFinished()
            return if (e.isReady(stream)) e.compute(stream) else null
        } finally { stream.release() }
    }

    override fun close() { recognizer.release(); extractor?.release() }

    /** Feeds 16 kHz audio through a VAD in fixed windows and hands out finished utterances. */
    class Splitter(private val vad: Vad) {
        private val pending = FloatArray(WINDOW); private var filled = 0
        fun feed(samples: FloatArray, utterance: (startSample: Int, FloatArray) -> Unit) {
            for (v in samples) {
                pending[filled++] = v
                if (filled == WINDOW) { vad.acceptWaveform(pending.copyOf()); filled = 0; drain(utterance) }
            }
        }
        fun finish(utterance: (Int, FloatArray) -> Unit) { vad.flush(); drain(utterance); vad.release() }
        val speaking get() = vad.isSpeechDetected()
        private fun drain(utterance: (Int, FloatArray) -> Unit) {
            while (!vad.empty()) { val s = vad.front(); vad.pop(); utterance(s.start, s.samples) }
        }
    }

    companion object {
        const val RATE = 16000
        const val WINDOW = 512
        fun ready(store: ModelStore) = store.installed(ModelCatalog.vad) && store.active(ModelKind.Speech) != null
        fun open(context: Context, speakers: Boolean): SpeechEngine {
            val store = ModelStore(context)
            check(ready(store)) { "文字起こしモデルと発話検出モデルをダウンロードしてください" }
            return SpeechEngine(store, store.active(ModelKind.Speech)!!, speakers)
        }
    }
}

object Speech {
    /** Transcribes a WAV file; speakers are clustered over the whole recording when the speaker model is installed. */
    suspend fun transcribe(context: Context, wav: File, progress: (Float) -> Unit): List<Segment> = withContext(Dispatchers.Default) {
        SpeechEngine.open(context, speakers = true).use { engine ->
            val splitter = SpeechEngine.Splitter(engine.newVad())
            val found = mutableListOf<Diarized>()
            fun take(start: Int, samples: FloatArray) {
                val text = engine.recognize(samples)
                if (text.isNotBlank()) found += Diarized(Segment(start * 1000L / SpeechEngine.RATE, (start + samples.size) * 1000L / SpeechEngine.RATE, text),
                    engine.embed(samples), samples.size / 160)
            }
            Pcm.speech(wav) { chunk, p -> ensureActive(); splitter.feed(chunk, ::take); progress(p) }
            splitter.finish(::take)
            diarize(found)
        }
    }

    class Diarized(val segment: Segment, val vector: FloatArray?, val frames: Int)

    fun diarize(items: List<Diarized>, threshold: Float = 0.5f): List<Segment> {
        if (items.none { it.vector != null }) return items.map { it.segment }
        val labels = Diarization.cluster(items.map { Diarization.Utterance(it.vector, it.frames) }, threshold)
        return items.mapIndexed { i, d -> d.segment.copy(speaker = Diarization.label(labels[i])) }
    }
}

/**
 * Captions and speakers while recording. Audio is queued so recognition never blocks capture; if the
 * phone cannot keep up, captions stop and the audio is still saved. While someone is speaking, the open
 * utterance is decoded again every so often and shown as provisional text until its final result is in.
 */
class LiveTranscriber(context: Context, private val rate: Int, private val channels: Int, scope: CoroutineScope,
                      private val onUpdate: (segments: List<Segment>, partial: String, speaking: Boolean) -> Unit) {
    private val queue = Channel<ShortArray>(256)
    /** Chunks queued but not yet read; provisional decoding waits while recognition is behind. */
    private val backlog = AtomicInteger()
    private val result = CompletableDeferred<List<Segment>>()
    @Volatile private var overloaded = false
    /** Why captions stopped, if they did. Recording continues either way. */
    val problem get() = when { overloaded -> "文字起こしが追いつかないため停止しました"; result.isCompleted -> "文字起こしを停止しました"; else -> null }

    init {
        scope.launch(Dispatchers.Default) {
            try {
                SpeechEngine.open(context, speakers = true).use { engine ->
                    val splitter = SpeechEngine.Splitter(engine.newVad())
                    val resampler = Resampler(rate, SpeechEngine.RATE)
                    val online = OnlineDiarizer()
                    val items = mutableListOf<Speech.Diarized>()
                    val shown = mutableListOf<Segment>()
                    val open = Utterance()
                    var partial = ""
                    var finished = false
                    fun take(start: Int, samples: FloatArray) {
                        finished = true
                        val text = engine.recognize(samples)
                        if (text.isBlank()) return
                        val vector = engine.embed(samples)
                        val segment = Segment(start * 1000L / SpeechEngine.RATE, (start + samples.size) * 1000L / SpeechEngine.RATE, text)
                        items += Speech.Diarized(segment, vector, samples.size / 160)
                        shown += segment.copy(speaker = Diarization.label(online.assign(vector, samples.size / 160)))
                    }
                    var published = Triple(0, "", false)
                    var nextPartial = 0L
                    for (pcm in queue) {
                        val behind = backlog.decrementAndGet()
                        val mono = FloatArray(pcm.size / channels) { f -> var s = 0; for (c in 0 until channels) s += pcm[f * channels + c]; s / channels / 32768f }
                        val samples = resampler.process(mono)
                        splitter.feed(samples, ::take)
                        val speaking = splitter.speaking
                        // The pre-roll keeps the start of speech, which the VAD only recognises a moment later.
                        if (finished) { finished = false; open.clear(); partial = "" } else open.add(samples)
                        if (!speaking) { open.keepLast(PRE_ROLL); partial = "" }
                        else if (behind <= MAX_BACKLOG && open.size >= MIN_PARTIAL && SystemClock.elapsedRealtime() >= nextPartial) {
                            val started = SystemClock.elapsedRealtime()
                            partial = engine.recognize(open.samples())
                            val now = SystemClock.elapsedRealtime()
                            nextPartial = now + maxOf(PARTIAL_INTERVAL_MS, (now - started) * 2)
                        }
                        val state = Triple(shown.size, partial, speaking)
                        if (state != published) { published = state; onUpdate(shown.toList(), partial, speaking) }
                    }
                    splitter.finish(::take)
                    // Offline clustering over the whole recording is more accurate than the live labels.
                    result.complete(if (overloaded) emptyList() else Speech.diarize(items))
                }
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }
    }

    fun offer(pcm: ShortArray) {
        if (overloaded || result.isCompleted) return
        backlog.incrementAndGet()
        if (queue.trySend(pcm).isFailure) { backlog.decrementAndGet(); overloaded = true; queue.close() }
    }

    suspend fun finish(): List<Segment> { queue.close(); return result.await() }

    /** 16 kHz samples of the utterance being spoken, plus a short pre-roll. */
    private class Utterance {
        private var data = FloatArray(SpeechEngine.RATE * 4)
        var size = 0; private set
        fun add(samples: FloatArray) {
            if (size + samples.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + samples.size))
            samples.copyInto(data, size); size += samples.size
        }
        fun keepLast(n: Int) { if (size > n) { data.copyInto(data, 0, size - n, size); size = n } }
        fun clear() { size = 0 }
        fun samples() = data.copyOf(size)
    }

    private companion object {
        const val PRE_ROLL = SpeechEngine.RATE / 2
        const val MIN_PARTIAL = SpeechEngine.RATE * 3 / 10
        const val PARTIAL_INTERVAL_MS = 250L
        /** 20 ms chunks, so about 100 ms of unread audio. */
        const val MAX_BACKLOG = 5
    }
}
