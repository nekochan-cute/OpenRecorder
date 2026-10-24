@file:OptIn(ExperimentalMaterial3Api::class)
package org.openrecorder.app

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openrecorder.app.audio.RecordingService
import org.openrecorder.app.core.ProcessOptions
import org.openrecorder.app.core.Recording
import org.openrecorder.app.ui.*
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: RecorderViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RecorderTheme { App(vm, ::share, ::open) } }
    }
    private fun share(file: File, mime: String) {
        try {
            val uri = FileProvider.getUriForFile(this,"$packageName.files",file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = mime; putExtra(Intent.EXTRA_STREAM,uri); clipData = ClipData.newRawUri(file.name,uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },null))
        } catch(e: Exception) { vm.message.value = "共有できませんでした" }
    }
    private fun open(url: String) { runCatching { startActivity(Intent(Intent.ACTION_VIEW,url.toUri())) }.onFailure { vm.message.value = "開けませんでした" } }
}

@Composable
private fun App(vm: RecorderViewModel, share: (File, String) -> Unit, openUrl: (String) -> Unit) {
    val context = LocalContext.current
    val all by vm.recordings.collectAsStateWithLifecycle()
    val capture by vm.capture.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val stack = rememberSaveable(saver = listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<String>, String>({ it.toList() }, { it.toMutableStateList() })) { mutableStateListOf("library") }
    val route = stack.last()
    fun go(r: String) { stack.add(r) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun replace(r: String) { stack[stack.lastIndex] = r }
    BackHandler(stack.size > 1 && route != "ai") { vm.stopPlayback(); back() }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); if (vm.message.value == it) vm.message.value = null } }
    LaunchedEffect(capture.error) { capture.error?.let { snackbar.showSnackbar(it) } }

    // Follow the recording: open the recorder when it starts, its result when it stops.
    var recordingId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(capture.id) {
        val id = capture.id
        if (id != null) { if (recordingId != id && route != "record") go("record"); recordingId = id }
        else recordingId?.let { done -> recordingId = null; if (stack.last() == "record") { replace("detail/$done"); vm.load(done) } }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.start()
        else scope(snackbar) { if (snackbar.showSnackbar("マイクの使用が許可されていません", "設定") == SnackbarResult.ActionPerformed)
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) }
    }
    fun record() = permissions.launch(buildList { add(Manifest.permission.RECORD_AUDIO); if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS) }.toTypedArray())
    val importAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri, displayName(context, uri)?.substringBeforeLast('.') ?: "取り込んだ音声")
    }
    var photoTarget by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val t = photoTarget
        if (uri != null && t != null) vm.mark(t.first, t.second, photo = uri)
        photoTarget = null
    }
    fun pickPhoto(id: String, at: Long) { photoTarget = id to at; photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(route, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "route") { current ->
            val id = current.substringAfter('/', "")
            when {
                current == "library" -> LibraryScreen(all, capture, LibraryActions(
                    open = { vm.load(it); go("detail/$it") }, record = ::record, showRecording = { go("record") },
                    import = { importAudio.launch(arrayOf("audio/*", "application/ogg")) }, trash = { go("trash") }, settings = { go("settings") },
                    favorite = vm::favorite, rename = vm::rename, move = vm::moveToFolder, delete = { vm.trash(it); undoTrash(vm, snackbar, it) }))
                current == "record" -> RecordScreen(capture, all.firstOrNull { it.id == capture.id }, RecordActions(
                    pause = { vm.command(RecordingService.PAUSE) }, stop = { vm.command(RecordingService.STOP) },
                    mark = { capture.id?.let { vm.mark(it, capture.durationMs) } }, photo = { capture.id?.let { pickPhoto(it, capture.durationMs) } },
                    back = { back() }))
                current.startsWith("detail/") -> all.firstOrNull { it.id == id }?.let { r -> Detail(vm, r, all, busy != null, share, openUrl, ::pickPhoto,
                    back = { vm.stopPlayback(); back() }, go = ::go, replace = ::replace) } ?: LaunchedEffect(Unit) { back() }
                current.startsWith("editor/") -> all.firstOrNull { it.id == id }?.let { r ->
                    val position by vm.position.collectAsStateWithLifecycle()
                    val playing by vm.playing.collectAsStateWithLifecycle()
                    val levels by vm.levels.collectAsStateWithLifecycle()
                    EditorScreen(r, levels, position, playing, busy != null, EditorActions(close = { vm.stopPlayback(); back() },
                        playRange = vm::playRange, pause = vm::stopPlayback, seek = vm::seek,
                        save = { s, e, remove -> vm.stopPlayback(); vm.edit(r.id, s, e, remove) { newId -> back(); replace("detail/$newId") } }))
                } ?: LaunchedEffect(Unit) { back() }
                current == "trash" -> TrashScreen(all, ::back, vm::restore, vm::delete, vm::emptyTrash)
                current == "settings" -> {
                    val options by vm.options.collectAsStateWithLifecycle()
                    val ai by vm.ai.collectAsStateWithLifecycle()
                    vm.modelsVersion.collectAsStateWithLifecycle().value
                    val modelsLabel = vm.models.active(org.openrecorder.app.ai.ModelKind.Speech)?.title ?: "未ダウンロード"
                    SettingsScreen(options, inputDevices(context), vm.speechReady(), modelsLabel, ai, BuildConfig.VERSION_NAME,
                        SettingsActions(back = ::back, options = vm::setOptions, models = { go("models") }, ai = { go("ai") }, licenses = { go("licenses") },
                            source = { openUrl("https://github.com/nekochan-cute/OpenRecorder") }))
                }
                current == "ai" -> AiScreen(vm.ai.collectAsStateWithLifecycle().value, AiActions(back = { c -> if (c != vm.ai.value) vm.saveAi(c); back() },
                    models = { p, c, speech -> vm.models(p, c, speech) }, login = { vm.loginOpenRouter(openUrl) }, openUrl = openUrl,
                    error = { vm.message.value = it }))
                current == "licenses" -> Licenses(::back)
                current == "models" -> {
                    val downloads by vm.downloads.collectAsStateWithLifecycle()
                    vm.modelsVersion.collectAsStateWithLifecycle().value
                    // Re-read the store when a download finishes.
                    LaunchedEffect(downloads.keys) { vm.modelsVersion.value++ }
                    val rows = org.openrecorder.app.ai.ModelCatalog.all.map { spec ->
                        ModelRow(spec, vm.models.installed(spec), vm.models.active(spec.kind)?.id == spec.id, downloads[spec.id])
                    }
                    ModelsScreen(rows, ModelActions(back = ::back, download = vm::download, cancel = vm::cancelDownload,
                        delete = vm::deleteModel, choose = vm::chooseModel))
                }
            }
        }
        busy?.let { label ->
            Surface(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 72.dp), shape = CircleShape,
                color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 4.dp) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = MaterialTheme.colorScheme.inverseOnSurface, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.inverseOnSurface)
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 120.dp))
    }
}

@Composable
private fun Detail(vm: RecorderViewModel, r: Recording, all: List<Recording>, busy: Boolean, share: (File, String) -> Unit, openUrl: (String) -> Unit,
                   pickPhoto: (String, Long) -> Unit, back: () -> Unit, go: (String) -> Unit, replace: (String) -> Unit) {
    val context = LocalContext.current
    val position by vm.position.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val levels by vm.levels.collectAsStateWithLifecycle()
    val options by vm.options.collectAsStateWithLifecycle()
    val ai by vm.ai.collectAsStateWithLifecycle()
    LaunchedEffect(r.id) { vm.load(r.id) }
    DetailScreen(DetailState(r, levels, position, playing, options.playbackSpeed, options.skipSilence, busy,
        vm.speechReady(), ai.transcriptionReady, ai.summaryReady, all.map { it.folder }.filter(String::isNotBlank).distinct().sorted()),
        DetailActions(back = back, playPause = vm::togglePlay, seek = vm::seek,
            speed = { vm.setOptions(options.copy(playbackSpeed = it)) }, skipSilence = { vm.setOptions(options.copy(skipSilence = it)) },
            favorite = { vm.favorite(r.id) }, rename = { vm.rename(r.id, it) }, move = { vm.moveToFolder(r.id, it) },
            trash = { vm.trash(r.id); back() }, edit = { vm.stopPlayback(); go("editor/${r.id}") },
            enhance = { o: ProcessOptions -> vm.stopPlayback(); vm.process(r.id, o) { replace("detail/$it") } },
            share = { kind -> when (kind) {
                "m4a" -> vm.exportAac(r) { share(it, "audio/mp4") }
                "wav" -> share(vm.repo.file(r), "audio/wav")
                else -> share(vm.exportText(r, kind), when (kind) { "srt" -> "application/x-subrip"; "vtt" -> "text/vtt"; "summary" -> "text/markdown"; else -> "text/plain" })
            } },
            transcribe = { remote -> vm.transcribe(r.id, remote) }, summarize = { t, remote -> if (remote) vm.summarize(r.id, t) else vm.summarizeLocal(r.id, t) },
            addMark = { vm.mark(r.id, it) }, addPhoto = { pickPhoto(r.id, it) }, editMark = { m, t -> vm.updateMark(r.id, m, t) },
            deleteMark = { vm.deleteMark(r.id, it) }, photoFile = { runCatching { vm.repo.photoFile(it) }.getOrNull() },
            openPhoto = { name -> runCatching {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", vm.repo.photoFile(name))
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } },
            updateSegment = { i, t, s -> vm.updateSegment(r.id, i, t, s) }, renameSpeaker = { old, new -> vm.renameSpeaker(r.id, old, new) },
            settings = { go("settings") }))
}

@Composable
private fun Licenses(back: () -> Unit) {
    val context = LocalContext.current
    val text = remember { listOf("licenses/THIRD_PARTY_NOTICES.md", "licenses/Apache-2.0.txt").joinToString("\n\n") { f -> context.assets.open(f).bufferedReader().use { it.readText() } } }
    Scaffold(topBar = { TopAppBar(title = { Text("ライセンス") }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "戻る") } }) }) { padding ->
        Text(text, Modifier.padding(padding).readable().verticalScroll(rememberScrollState()).padding(24.dp), style = MaterialTheme.typography.bodySmall)
    }
}

private fun undoTrash(vm: RecorderViewModel, snackbar: SnackbarHostState, id: String) = scope(snackbar) {
    if (snackbar.showSnackbar("ごみ箱に移動しました", "元に戻す", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) vm.restore(id)
}

private val uiScope = kotlinx.coroutines.MainScope()
private fun scope(@Suppress("UNUSED_PARAMETER") host: SnackbarHostState, block: suspend () -> Unit) { uiScope.launch { block() } }

private fun displayName(context: android.content.Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }

private fun inputDevices(context: android.content.Context): List<Pair<Int, String>> =
    context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type != AudioDeviceInfo.TYPE_TELEPHONY }.map { d ->
        val kind = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "内蔵マイク"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有線"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            else -> "外部"
        }
        d.id to if (d.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) kind else "${d.productName}（$kind）"
    }.distinctBy { it.second }
