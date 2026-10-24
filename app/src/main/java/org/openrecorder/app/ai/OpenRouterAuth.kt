package org.openrecorder.app.ai

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * OpenRouter OAuth PKCE (https://openrouter.ai/docs/use-cases/oauth-pkce). The browser is sent back to a
 * one-shot listener on the loopback interface, which OpenRouter allows on any port; the code is then
 * exchanged for a user API key. Nothing listens on the network.
 */
object OpenRouterAuth {
    private const val TIMEOUT_MS = 5 * 60 * 1000

    suspend fun login(packageName: String, openBrowser: suspend (String) -> Unit): String {
        val verifier = random(48)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = random(16)
        val code = withContext(Dispatchers.IO) {
            ServerSocket(0, 4, InetAddress.getLoopbackAddress()).use { server ->
                server.soTimeout = TIMEOUT_MS
                val job = currentCoroutineContext().job
                val closer = job.invokeOnCompletion { runCatching { server.close() } }
                try {
                    val callback = "http://localhost:${server.localPort}/callback"
                    openBrowser("https://openrouter.ai/auth?callback_url=${enc(callback)}&code_challenge=$challenge" +
                        "&code_challenge_method=S256&state=$state&key_label=${enc("Open Recorder")}")
                    awaitCode(server, state, packageName)
                } finally { closer.dispose() }
            }
        }
        return exchange(code, verifier)
    }

    private fun awaitCode(server: ServerSocket, state: String, packageName: String): String {
        while (true) {
            server.accept().use { socket ->
                socket.soTimeout = 10_000
                val line = socket.getInputStream().bufferedReader().readLine().orEmpty()
                val target = line.split(' ').getOrNull(1).orEmpty()
                if (!target.startsWith("/callback")) {
                    socket.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return@use
                }
                val params = target.substringAfter('?', "").split('&').mapNotNull {
                    val (k, v) = it.split('=', limit = 2).takeIf { p -> p.size == 2 } ?: return@mapNotNull null
                    URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
                }.toMap()
                val ok = params["state"] == state && !params["code"].isNullOrBlank()
                val back = "intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;package=$packageName;end"
                val html = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>" +
                    "<body style='font-family:sans-serif;display:grid;place-items:center;height:90vh;margin:0'>" +
                    "<a href='$back' style='font-size:20px;padding:16px 28px;border-radius:28px;background:#e5484d;color:#fff;text-decoration:none'>" +
                    (if (ok) "Open Recorderに戻る" else "ログインできませんでした") + "</a></body>"
                val body = html.toByteArray()
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray() + body)
                if (ok) return params.getValue("code")
                error("ログインがキャンセルされました")
            }
        }
    }

    private suspend fun exchange(code: String, verifier: String): String = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).followRedirects(false).build()
        val body = JSONObject().put("code", code).put("code_verifier", verifier).put("code_challenge_method", "S256")
            .toString().toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url("https://openrouter.ai/api/v1/auth/keys").post(body).build()).execute().use { response ->
            check(response.isSuccessful) { "OpenRouterのキーを取得できませんでした（HTTP ${response.code}）" }
            JSONObject(response.body!!.string()).optString("key").takeIf(String::isNotBlank) ?: error("OpenRouterのキーを取得できませんでした")
        }
    }

    private fun random(bytes: Int) = base64Url(ByteArray(bytes).also(SecureRandom()::nextBytes))
    private fun base64Url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
