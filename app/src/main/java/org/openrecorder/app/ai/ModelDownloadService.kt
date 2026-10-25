package org.openrecorder.app.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import okhttp3.Request
import org.openrecorder.app.MainActivity
import org.openrecorder.app.R
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

data class DownloadState(val progress: Float = 0f, val error: String? = null)

/**
 * Downloads models in the foreground so multi-gigabyte files survive the app going to the background.
 * Partial files are kept and resumed with HTTP range requests; a model is marked complete only when
 * every file has its expected size.
 */
class ModelDownloadService : Service() {
    companion object {
        private val mutable = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
        val state = mutable.asStateFlow()
        fun start(context: Context, id: String) {
            mutable.update { it + (id to DownloadState()) }
            ContextCompat.startForegroundService(context, Intent(context, ModelDownloadService::class.java).setAction("start").putExtra("id", id))
        }
        fun cancel(context: Context, id: String) {
            context.startService(Intent(context, ModelDownloadService::class.java).setAction("cancel").putExtra("id", id))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = java.util.concurrent.ConcurrentLinkedDeque<String>()
    private var worker: Job? = null
    private var current: Pair<String, Job>? = null
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("id")
        when (intent?.action) {
            "start" -> {
                foreground()
                if (id != null && id !in queue && current?.first != id) queue.addLast(id)
                if (worker?.isActive != true) worker = scope.launch { run() }
            }
            "cancel" -> if (id != null) {
                queue.remove(id); mutable.update { it - id }
                current?.takeIf { it.first == id }?.second?.cancel()
                if (worker?.isActive != true) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun foreground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("models", "モデルのダウンロード", NotificationManager.IMPORTANCE_LOW))
        startForeground(20, notification("ダウンロード中", 0f), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun notification(title: String, progress: Float): Notification =
        NotificationCompat.Builder(this, "models").setSmallIcon(R.drawable.ic_recorder).setContentTitle(title).setOngoing(true)
            .setProgress(1000, (progress * 1000).toInt(), progress <= 0f).setOnlyAlertOnce(true)
            // The launcher's intent brings the existing screen back instead of stacking a new one on top.
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                PendingIntent.FLAG_IMMUTABLE)).build()

    private suspend fun run() {
        val store = ModelStore(this)
        while (true) {
            val id = queue.pollFirst() ?: break
            val spec = ModelCatalog.byId(id) ?: continue
            val job = scope.launch { download(store, spec) }
            current = id to job
            job.join()
            current = null
        }
        withContext(Dispatchers.Main) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }

    private suspend fun download(store: ModelStore, spec: ModelSpec) {
        try {
            require(store.root.usableSpace > spec.size - store.bytesOnDisk(spec) + 256L * 1024 * 1024) { "空き容量が足りません" }
            store.dir(spec).mkdirs()
            var done = spec.files.sumOf { f -> store.file(spec, f.name).takeIf { it.isFile }?.length() ?: File(store.file(spec, f.name).path + ".part").length() }
            for (f in spec.files) {
                val target = store.file(spec, f.name)
                if (target.isFile && target.length() == f.size) continue
                val part = File(target.path + ".part")
                val offset = part.length()
                val request = Request.Builder().url(f.url).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
                client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "ダウンロードできませんでした（HTTP ${response.code}）" }
                    val append = offset > 0 && response.code == 206
                    if (!append) done -= offset
                    RandomAccessFile(part, "rw").use { out ->
                        if (!append) out.setLength(0)
                        out.seek(out.length())
                        response.body!!.byteStream().use { input ->
                            val buffer = ByteArray(1 shl 16); var last = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer); if (n < 0) break
                                out.write(buffer, 0, n); done += n
                                if (done - last > (1 shl 20)) {
                                    last = done
                                    val p = done.toFloat() / spec.size
                                    mutable.update { it + (spec.id to DownloadState(p)) }
                                    getSystemService(NotificationManager::class.java).notify(20, notification(spec.title, p))
                                }
                            }
                        }
                    }
                }
                check(part.length() == f.size) { "ファイルが不完全です。もう一度お試しください" }
                check(part.renameTo(target))
            }
            File(store.dir(spec), ".complete").writeText("")
            mutable.update { it - spec.id }
        } catch (e: CancellationException) {
            mutable.update { it - spec.id }
        } catch (e: Exception) {
            mutable.update { it + (spec.id to DownloadState(error = e.message ?: "ダウンロードできませんでした")) }
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
