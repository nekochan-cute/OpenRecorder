package org.openrecorder.app.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** OpenAI-compatible services. Summaries use `/chat/completions`, transcription `/audio/transcriptions`. */
enum class Provider(val label: String, val baseUrl: String, val keyPage: String?, val transcription: Boolean, val speechModel: String = "") {
    OpenRouter("OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai/settings/keys", false),
    Google("Google AI Studio", "https://generativelanguage.googleapis.com/v1beta/openai", "https://aistudio.google.com/apikey", false),
    OpenAI("OpenAI", "https://api.openai.com/v1", "https://platform.openai.com/api-keys", true, "whisper-1"),
    Groq("Groq", "https://api.groq.com/openai/v1", "https://console.groq.com/keys", true, "whisper-large-v3-turbo"),
    Custom("その他（OpenAI互換）", "", null, true);

    val oauth get() = this == OpenRouter
}

data class AiConfig(
    val summaryProvider: Provider? = null,
    val summaryModel: String = "",
    val summaryBase: String = "",
    val transcriptionProvider: Provider? = null,
    val transcriptionModel: String = "",
    val transcriptionBase: String = "",
    val keys: Map<Provider, String> = emptyMap(),
    val language: String = "ja",
) {
    private fun base(p: Provider?, custom: String) = (if (p == Provider.Custom) custom else p?.baseUrl.orEmpty()).trim().trimEnd('/')
    val summaryUrl get() = summaryProvider?.let { base(it, summaryBase).takeIf(String::isNotBlank)?.plus("/chat/completions") }.orEmpty()
    val summaryKey get() = summaryProvider?.let { keys[it] }.orEmpty()
    val transcriptionUrl get() = transcriptionProvider?.let { base(it, transcriptionBase).takeIf(String::isNotBlank)?.plus("/audio/transcriptions") }.orEmpty()
    val transcriptionKey get() = transcriptionProvider?.let { keys[it] }.orEmpty()
    fun baseUrl(p: Provider, forTranscription: Boolean) = base(p, if (forTranscription) transcriptionBase else summaryBase)
    val summaryReady get() = summaryUrl.isNotBlank() && summaryModel.isNotBlank()
    val transcriptionReady get() = transcriptionUrl.isNotBlank()
}

class AiSettings(context: Context) {
    private val prefs = context.getSharedPreferences("ai-sealed",Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (ks.getKey("open-recorder-ai",null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("open-recorder-ai", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun load(): AiConfig {
        val value = prefs.getString("sealed",null) ?: return AiConfig()
        val bytes = Base64.decode(value,Base64.NO_WRAP)
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12))); doFinal(bytes.copyOfRange(12,bytes.size))
        }
        return decode(JSONObject(String(plain,Charsets.UTF_8)))
    }
    @Synchronized fun save(c: AiConfig) {
        listOf(c.summaryUrl,c.transcriptionUrl).filter { it.isNotBlank() }.forEach {
            val uri = java.net.URI(it)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "URLはhttps://で始まる形式で入力してください" }
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val bytes = cipher.iv + cipher.doFinal(encode(c).toString().toByteArray())
        check(prefs.edit().putString("sealed",Base64.encodeToString(bytes,Base64.NO_WRAP)).commit()) { "設定を保存できません" }
    }

    private fun encode(c: AiConfig) = JSONObject().apply {
        put("sp", c.summaryProvider?.name ?: ""); put("sm", c.summaryModel.trim()); put("sb", c.summaryBase.trim())
        put("tp", c.transcriptionProvider?.name ?: ""); put("tm", c.transcriptionModel.trim()); put("tb", c.transcriptionBase.trim())
        put("language", c.language.trim())
        put("keys", JSONObject().apply { c.keys.filterValues { it.isNotBlank() }.forEach { (p, k) -> put(p.name, k.trim()) } })
    }

    private fun decode(j: JSONObject): AiConfig {
        fun provider(name: String) = Provider.entries.firstOrNull { it.name == name }
        if (j.has("sp") || j.has("tp")) {
            val keys = j.optJSONObject("keys")
            return AiConfig(provider(j.optString("sp")), j.optString("sm"), j.optString("sb"),
                provider(j.optString("tp")), j.optString("tm"), j.optString("tb"),
                Provider.entries.mapNotNull { p -> keys?.optString(p.name)?.takeIf(String::isNotBlank)?.let { p to it } }.toMap(),
                j.optString("language", "ja"))
        }
        // Settings saved before providers existed held full endpoint URLs.
        val tu = j.optString("tu"); val su = j.optString("su")
        val keys = buildMap { (j.optString("sk").ifBlank { j.optString("tk") }).takeIf(String::isNotBlank)?.let { put(Provider.Custom, it) } }
        return AiConfig(if (su.isBlank()) null else Provider.Custom, j.optString("sm"), su.removeSuffix("/chat/completions"),
            if (tu.isBlank()) null else Provider.Custom, j.optString("tm"), tu.removeSuffix("/audio/transcriptions"), keys, j.optString("language", "ja"))
    }
}
