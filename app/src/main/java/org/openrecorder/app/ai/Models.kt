package org.openrecorder.app.ai

import android.content.Context
import android.net.Uri
import java.io.File

enum class ModelKind { Speech, Vad, Speaker, Llm }

data class ModelFile(val name: String, val url: String, val size: Long)

data class ModelSpec(val id: String, val kind: ModelKind, val title: String, val detail: String, val license: String, val files: List<ModelFile>) {
    val size get() = files.sumOf { it.size }
}

private fun hf(repo: String, path: String, size: Long, name: String = path.substringAfterLast('/')) =
    ModelFile(name, "https://huggingface.co/$repo/resolve/main/$path", size)

/** Models the app can download. All are published on Hugging Face by their authors or the sherpa-onnx / LiteRT projects. */
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
    val gemma = ModelSpec("gemma-4-e2b", ModelKind.Llm, "Gemma 4 E2B", "多言語・要約と翻訳", "Apache-2.0", listOf(
        hf("litert-community/gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it.litertlm", 2_588_147_712, "model.litertlm")))
    val swallow = ModelSpec("tinyswallow-1.5b", ModelKind.Llm, "TinySwallow 1.5B", "日本語に強い小型モデル", "Apache-2.0", listOf(
        hf("litert-community/TinySwallow-1.5B-Instruct", "TinySwallow-1.5B-Instruct.litertlm", 1_567_604_736, "model.litertlm")))
    /** A .litertlm file the user picked; not downloadable. */
    val customLlm = ModelSpec("custom-llm", ModelKind.Llm, "自分で追加したモデル", ".litertlm", "", listOf(ModelFile("model.litertlm", "", 0)))

    val all = listOf(reazon, senseVoice, vad, speaker, gemma, swallow)
    fun byId(id: String) = (all + customLlm).firstOrNull { it.id == id }
}

/** Installed models live in `files/models/<id>/`; a model counts as installed once every file is complete. */
class ModelStore(private val context: Context) {
    val root = File(context.filesDir, "models").apply { mkdirs() }
    fun dir(spec: ModelSpec) = File(root, spec.id)
    fun file(spec: ModelSpec, name: String) = File(dir(spec), name)
    fun installed(spec: ModelSpec) = File(dir(spec), ".complete").isFile && spec.files.all { file(spec, it.name).isFile }
    fun installed(kind: ModelKind) = (ModelCatalog.all + ModelCatalog.customLlm).filter { it.kind == kind && installed(it) }
    fun delete(spec: ModelSpec) { dir(spec).deleteRecursively() }
    fun bytesOnDisk(spec: ModelSpec) = dir(spec).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private val prefs = context.getSharedPreferences("models", Context.MODE_PRIVATE)
    /** The chosen model of a kind if installed, else the first installed one. */
    fun active(kind: ModelKind): ModelSpec? {
        val all = installed(kind)
        return all.firstOrNull { it.id == prefs.getString(kind.name, null) } ?: all.firstOrNull()
    }
    fun choose(spec: ModelSpec) { prefs.edit().putString(spec.kind.name, spec.id).apply() }

    /** Copies a user-picked LiteRT-LM model into the store. */
    fun importLlm(uri: Uri, progress: (Float) -> Unit) {
        val spec = ModelCatalog.customLlm
        val target = file(spec, "model.litertlm").also { it.parentFile?.mkdirs() }
        File(dir(spec), ".complete").delete()
        val total = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }?.takeIf { it > 0 } ?: -1L
        require(total < 0 || root.usableSpace > total + 256L * 1024 * 1024) { "空き容量が足りません" }
        val part = File(target.path + ".part")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            part.outputStream().use { out ->
                val buffer = ByteArray(1 shl 20); var done = 0L
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    out.write(buffer, 0, n); done += n
                    if (total > 0) progress(done.toFloat() / total)
                }
            }
        }
        check(part.renameTo(target)) { "モデルを保存できませんでした" }
        File(dir(spec), ".complete").writeText("")
    }
}
