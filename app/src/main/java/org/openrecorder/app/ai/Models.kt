package org.openrecorder.app.ai

import android.content.Context
import java.io.File

enum class ModelKind { Speech, Vad, Speaker }

data class ModelFile(val name: String, val url: String, val size: Long)

data class ModelSpec(val id: String, val kind: ModelKind, val title: String, val detail: String, val license: String, val files: List<ModelFile>) {
    val size get() = files.sumOf { it.size }
}

private fun hf(repo: String, path: String, size: Long, name: String = path.substringAfterLast('/')) =
    ModelFile(name, "https://huggingface.co/$repo/resolve/main/$path", size)

/** Models the app can download. All are published on Hugging Face by their authors or the sherpa-onnx project. */
object ModelCatalog {
    val reazon = ModelSpec("reazonspeech-k2-v2", ModelKind.Speech, "ReazonSpeech v2", "日本語・高精度", "Apache-2.0", listOf(
        hf("csukuangfj/reazonspeech-k2-v2", "encoder-epoch-99-avg-1.int8.onnx", 154_670_139, "encoder.onnx"),
        hf("csukuangfj/reazonspeech-k2-v2", "decoder-epoch-99-avg-1.int8.onnx", 2_959_337, "decoder.onnx"),
        hf("csukuangfj/reazonspeech-k2-v2", "joiner-epoch-99-avg-1.int8.onnx", 2_696_970, "joiner.onnx"),
        hf("csukuangfj/reazonspeech-k2-v2", "tokens.txt", 45_754)))
    val senseVoice = ModelSpec("sense-voice-2024-07-17", ModelKind.Speech, "SenseVoice Small", "日本語・英語・中国語・韓国語・広東語", "FunASR Model License", listOf(
        hf("csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17", "model.int8.onnx", 239_233_841, "model.onnx"),
        hf("csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17", "tokens.txt", 315_894)))
    val vad = ModelSpec("silero-vad", ModelKind.Vad, "Silero VAD", "発話区間の検出", "MIT", listOf(
        hf("csukuangfj/vad", "silero_vad.onnx", 1_807_522)))
    val speaker = ModelSpec("campplus-zh-en", ModelKind.Speaker, "3D-Speaker CAM++", "話者の聞き分け", "Apache-2.0", listOf(
        hf("csukuangfj/speaker-embedding-models", "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx", 28_281_164, "speaker.onnx")))
    val all = listOf(reazon, senseVoice, vad, speaker)
    fun byId(id: String) = all.firstOrNull { it.id == id }
}

/** Installed models live in `files/models/<id>/`; a model counts as installed once every file is complete. */
class ModelStore(private val context: Context) {
    val root = File(context.filesDir, "models").apply { mkdirs() }
    fun dir(spec: ModelSpec) = File(root, spec.id)
    fun file(spec: ModelSpec, name: String) = File(dir(spec), name)
    fun installed(spec: ModelSpec) = File(dir(spec), ".complete").isFile && spec.files.all { file(spec, it.name).isFile }
    fun installed(kind: ModelKind) = ModelCatalog.all.filter { it.kind == kind && installed(it) }
    fun delete(spec: ModelSpec) { dir(spec).deleteRecursively() }
    fun bytesOnDisk(spec: ModelSpec) = dir(spec).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private val prefs = context.getSharedPreferences("models", Context.MODE_PRIVATE)
    /** The chosen model of a kind if installed, else the first installed one. */
    fun active(kind: ModelKind): ModelSpec? {
        val all = installed(kind)
        return all.firstOrNull { it.id == prefs.getString(kind.name, null) } ?: all.firstOrNull()
    }
    fun choose(spec: ModelSpec) { prefs.edit().putString(spec.kind.name, spec.id).apply() }
}
