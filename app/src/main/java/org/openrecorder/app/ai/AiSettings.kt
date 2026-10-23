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

data class AiConfig(val transcriptionUrl: String = "", val transcriptionKey: String = "", val transcriptionModel: String = "",
    val summaryUrl: String = "", val summaryKey: String = "", val summaryModel: String = "", val language: String = "ja")

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
        val j = JSONObject(String(plain,Charsets.UTF_8))
        return AiConfig(j.optString("tu"),j.optString("tk"),j.optString("tm"),j.optString("su"),j.optString("sk"),j.optString("sm"),j.optString("language","ja"))
    }
    @Synchronized fun save(c: AiConfig) {
        listOf(c.transcriptionUrl,c.summaryUrl).filter { it.isNotBlank() }.forEach {
            val uri = java.net.URI(it)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "APIには認証情報を含まないHTTPS URLを指定してください" }
        }
        val j = JSONObject().apply {
            put("tu",c.transcriptionUrl.trim());put("tk",c.transcriptionKey);put("tm",c.transcriptionModel.trim())
            put("su",c.summaryUrl.trim());put("sk",c.summaryKey);put("sm",c.summaryModel.trim());put("language",c.language.trim())
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val bytes = cipher.iv + cipher.doFinal(j.toString().toByteArray())
        check(prefs.edit().putString("sealed",Base64.encodeToString(bytes,Base64.NO_WRAP)).commit()) { "設定を保存できません" }
    }
}
