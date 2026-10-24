package org.openrecorder.app.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.openrecorder.app.core.TextGenerator

/** On-device LLM through LiteRT-LM. Only one engine is loaded at a time; each request gets a fresh conversation. */
object LocalLlm {
    private val lock = Mutex()

    fun ready(context: Context) = ModelStore(context).active(ModelKind.Llm) != null
    /** LiteRT-LM ships native code for arm64-v8a and x86_64 only. */
    val supported get() = android.os.Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }

    suspend fun <T> use(context: Context, block: suspend (TextGenerator) -> T): T = lock.withLock {
        check(supported) { "この端末では端末内AIを使用できません" }
        val store = ModelStore(context)
        val spec = store.active(ModelKind.Llm) ?: error("設定のモデルから要約・翻訳用のモデルをダウンロードしてください")
        withContext(Dispatchers.Default) {
            // The context length comes from the model file; transcripts are sent in parts sized for small windows.
            Engine(EngineConfig(store.file(spec, "model.litertlm").path, Backend.CPU(), cacheDir = context.cacheDir.path)).use { engine ->
                engine.initialize()
                block(TextGenerator { system, user ->
                    withContext(Dispatchers.Default) {
                        engine.createConversation(ConversationConfig(Contents.of(system))).use { conversation ->
                            conversation.sendMessage(user).contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                        }
                    }
                })
            }
        }
    }
}
