package org.openrecorder.app

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openrecorder.app.ai.AiConfig
import org.openrecorder.app.audio.RecordingService
import org.openrecorder.app.core.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: RecorderViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if(dark) darkColorScheme(primary=Color(0xFF7CDBC4)) else lightColorScheme(primary=Color(0xFF176A60),secondary=Color(0xFF586C62))) {
                RecorderScreen(vm,::share)
            }
        }
    }
    private fun share(file: File, mime: String) {
        try {
            val uri = FileProvider.getUriForFile(this,"$packageName.files",file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = mime; putExtra(Intent.EXTRA_STREAM,uri)
                clipData = ClipData.newRawUri("録音",uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },"共有"))
        } catch(e: Exception) { vm.message.value = "共有できませんでした: ${e.message}" }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecorderScreen(vm: RecorderViewModel, share: (File,String) -> Unit) {
    val context = LocalContext.current
    val all by vm.recordings.collectAsStateWithLifecycle()
    val capture by vm.capture.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("list") }
    val snackbar = remember { SnackbarHostState() }
    val r = all.firstOrNull { it.id == selected }
    var priorActive by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(capture.id) {
        if(capture.id != null) { priorActive = capture.id; page = "capture" }
        else if(priorActive != null) { vm.load(priorActive!!); page = "detail"; priorActive = null }
    }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); if(vm.message.value == it) vm.message.value = null } }
    LaunchedEffect(capture.error) { capture.error?.let { snackbar.showSnackbar(it) } }
    BackHandler(page != "list") { vm.stopPlayback(); page = if(capture.id != null && page != "capture") "capture" else "list" }
    Scaffold(
        topBar = { TopAppBar(title={ Text(when(page) { "settings" -> "設定"; "detail" -> "録音の詳細"; "capture" -> "録音"; else -> "Open Recorder" }) },
            navigationIcon={ if(page != "list") TextButton(onClick={ vm.stopPlayback(); page="list" }) { Text("戻る") } },
            actions={ if(page != "settings") TextButton(onClick={ page="settings" }) { Text("設定") } }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { if(capture.id != null && page != "capture") Surface(tonalElevation=4.dp) {
            TextButton(onClick={ page="capture" },modifier=Modifier.fillMaxWidth()) { Text("${if(capture.paused) "一時停止中" else "録音中"}  ${timeLabel(capture.durationMs)} ・ タップして開く") }
        } }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if(busy != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(busy!!,style=MaterialTheme.typography.labelMedium,modifier=Modifier.padding(horizontal=20.dp,vertical=6.dp))
            }
            when(page) {
                "settings" -> SettingsScreen(vm,busy != null || capture.id != null)
                "capture" -> if(capture.id != null) CaptureScreen(vm,all.firstOrNull { it.id == capture.id },busy != null)
                    else RecordSetup(vm,busy != null)
                "detail" -> if(r != null) DetailScreen(vm,r,busy != null,share,onBack={ page="list" })
                    else { Text("録音が見つかりません",Modifier.padding(24.dp)); TextButton(onClick={page="list"}) { Text("一覧へ") } }
                else -> RecordingList(vm,all,busy != null,
                    open={ vm.load(it); page="detail" },record={ page="capture" })
            }
        }
    }
}

@Composable
private fun RecordingList(vm: RecorderViewModel, all: List<Recording>, busy: Boolean, open: (String)->Unit, record: ()->Unit) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    var folder by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Recording?>(null) }
    val capture by vm.capture.collectAsStateWithLifecycle()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) {
            var name = "取り込んだ録音"
            context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) name=it.getString(0) }
            vm.import(uri,name.substringBeforeLast('.'))
        }
    }
    val filtered = all.filter { r ->
        (if(filter == "trash") r.deletedAt != null else r.deletedAt == null && !r.unfinished) &&
            (filter != "favorite" || r.favorite) && (folder.isBlank() || r.folder == folder) &&
            (query.isBlank() || listOf(r.title,r.folder,r.summary).any { it.contains(query,true) } ||
                r.segments.any { it.text.contains(query,true) || it.speaker.contains(query,true) } || r.marks.any { it.text.contains(query,true) })
    }
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("声と、その先のメモ。",style=MaterialTheme.typography.headlineMedium,modifier=Modifier.padding(top=12.dp))
        Text("${all.count { it.deletedAt == null && !it.unfinished }}件の録音",color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(query,{query=it},label={Text("録音・文章・マークを検索")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("all" to "すべて","favorite" to "お気に入り","trash" to "ごみ箱").forEach { (id,label) ->
                FilterChip(selected=filter==id,onClick={filter=id},label={Text(label)})
            }
        }
        var expanded by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick={expanded=true}) { Text("フォルダー: ${folder.ifBlank { "すべて" }}") }
            DropdownMenu(expanded,{expanded=false}) {
                (listOf("") + all.map { it.folder }.filter(String::isNotBlank).distinct().sorted()).forEach { name ->
                    DropdownMenuItem(text={Text(name.ifBlank { "すべて" })},onClick={ folder=name; expanded=false })
                }
            }
        }
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=12.dp)) {
            if(filtered.isEmpty()) item {
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(if(query.isBlank()) "録音はまだありません" else "一致する録音がありません",style=MaterialTheme.typography.titleMedium)
                    Text(if(filter=="trash") "削除した録音は、完全削除するまで保持されます。" else "録音を始めるか、音声ファイル（WAV・M4A・MP3など）を取り込んでください。")
                } }
            }
            items(filtered,key={it.id}) { r ->
                Card(onClick={if(filter != "trash") open(r.id)},modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                        Text((if(r.favorite) "★ " else "")+r.title,style=MaterialTheme.typography.titleMedium)
                        Text("${SimpleDateFormat("yyyy/MM/dd HH:mm",Locale.JAPAN).format(Date(r.createdAt))}  ·  ${timeLabel(r.durationMs)}",style=MaterialTheme.typography.bodySmall)
                        if(r.folder.isNotBlank()) Text(r.folder,style=MaterialTheme.typography.labelMedium)
                        Waveform(r.peaks,0f,Modifier.fillMaxWidth().height(30.dp))
                        if(r.segments.isNotEmpty()) Text("文字起こし ${r.segments.size}区間",style=MaterialTheme.typography.labelSmall)
                        if(filter == "trash") Row {
                            TextButton(onClick={vm.restore(r.id)},enabled=!busy) { Text("復元") }
                            TextButton(onClick={deleting=r},enabled=!busy) { Text("完全に削除") }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick={pick.launch(arrayOf("audio/*","application/ogg"))},enabled=!busy && capture.id == null) { Text("音声を取込") }
            Button(onClick=record,enabled=!busy,modifier=Modifier.weight(1f)) { Text(if(capture.id != null) "録音に戻る" else "録音を始める") }
        }
    }
    deleting?.let { r -> AlertDialog(onDismissRequest={deleting=null},title={Text("完全に削除しますか")},text={Text("「${r.title}」の音声と文章を削除します。元に戻せません。")},
        confirmButton={TextButton(onClick={vm.delete(r.id);deleting=null}) {Text("完全に削除")}},dismissButton={TextButton(onClick={deleting=null}) {Text("キャンセル")}}) }
}

@Composable
private fun RecordSetup(vm: RecorderViewModel,busy: Boolean) {
    val context = LocalContext.current
    var rate by rememberSaveable { mutableIntStateOf(48000) }
    var channels by rememberSaveable { mutableIntStateOf(1) }
    var voice by rememberSaveable { mutableStateOf(false) }
    var noise by rememberSaveable { mutableStateOf(false) }
    var live by rememberSaveable { mutableStateOf(false) }
    var device by remember { mutableIntStateOf(0) }
    val devices = remember { context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS).toList() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if(result[Manifest.permission.RECORD_AUDIO] == true || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            vm.start(rate,channels,voice,noise,device,live)
        else vm.message.value = "録音にはマイクへのアクセスが必要です。設定から許可してください。"
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(vertical=20.dp)) {
        item { Text("新しい録音",style=MaterialTheme.typography.headlineLarge) }
        item { Text("音声は端末内へ保存されます。録音中に画面を閉じても、通知から操作できます。") }
        item { Text("録音品質",style=MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                FilterChip(rate==16000,{rate=16000},label={Text("音声 16kHz")})
                FilterChip(rate==48000,{rate=48000},label={Text("高音質 48kHz")})
            }
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                FilterChip(channels==1,{channels=1},label={Text("モノラル")})
                FilterChip(channels==2,{channels=2},label={Text("ステレオ")})
            }
            Text("16bit PCM WAV・約${rate * channels * 2 * 60 / 1000000}MB/分",style=MaterialTheme.typography.bodySmall)
        }
        item { ToggleRow("音声認識向け入力", "端末のVOICE_RECOGNITION入力を使います",voice,{voice=it}) }
        item { ToggleRow("端末のノイズ抑制", "対応端末で録音時に適用します。実際の状態は録音画面に表示されます",noise,{noise=it}) }
        item { ToggleRow("録音中の端末内文字起こし", if(vm.modelAvailable()) "導入したVoskモデルで処理します" else "設定からVoskモデルZIPを取り込んでください",live,{live=it},vm.modelAvailable()) }
        item {
            var menu by remember { mutableStateOf(false) }
            Text("マイク",style=MaterialTheme.typography.titleMedium)
            Box { OutlinedButton(onClick={menu=true}) { Text(devices.firstOrNull {it.id==device}?.productName?.toString() ?: "システムの既定") }
                DropdownMenu(menu,{menu=false}) {
                    DropdownMenuItem(text={Text("システムの既定")},onClick={device=0;menu=false})
                    devices.forEach { d -> DropdownMenuItem(text={Text("${d.productName}・${inputType(d.type)}")},onClick={device=d.id;menu=false}) }
                }
            }
            Text("選択は端末への要求です。実際に使われた入力は録音画面で確認できます。Bluetoothマイクは通話用の接続になるため、音質は16kHz程度に制限されます。",style=MaterialTheme.typography.bodySmall)
        }
        item { Button(onClick={
            val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if(Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permission.launch(permissions.toTypedArray())
        },enabled=!busy,modifier=Modifier.fillMaxWidth().height(56.dp)) { Text("録音開始") } }
    }
}

@Composable
private fun CaptureScreen(vm: RecorderViewModel,r: Recording?,busy: Boolean) {
    val capture by vm.capture.collectAsStateWithLifecycle()
    var note by rememberSaveable { mutableStateOf("") }
    val pending = remember { mutableStateOf<Pair<String,Long>?>(null) }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = pending.value
        if(uri != null && target != null) vm.mark(target.first,target.second,note,uri)
        pending.value = null; note=""
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(20.dp),contentPadding=PaddingValues(vertical=20.dp)) {
        item { Text(r?.title ?: "録音中",style=MaterialTheme.typography.titleLarge) }
        item { Text(timeLabel(capture.durationMs),style=MaterialTheme.typography.displayLarge)
            Text(if(capture.saving) "保存中" else if(capture.paused) "一時停止中" else "録音中",color=MaterialTheme.colorScheme.primary) }
        item { Waveform(capture.peaks,0f,Modifier.fillMaxWidth().height(100.dp)) }
        item { Text("入力: ${capture.input.ifBlank { "確認中" }}\nノイズ抑制: ${if(capture.noiseReduction) "適用中" else "未適用"}",style=MaterialTheme.typography.bodySmall) }
        if(capture.silenced) item { Text("システムがマイク入力を無音化しています。他のアプリの録音やマイク設定を確認してください。",color=MaterialTheme.colorScheme.error) }
        item { Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick={vm.command(RecordingService.PAUSE)},enabled=!capture.saving,modifier=Modifier.weight(1f)) {Text(if(capture.paused) "再開" else "一時停止")}
            Button(onClick={vm.command(RecordingService.STOP)},enabled=!capture.saving,modifier=Modifier.weight(1f)) {Text("保存して停止")}
        } }
        item { OutlinedTextField(note,{note=it},label={Text("マークのメモ")},modifier=Modifier.fillMaxWidth())
            Row {
                TextButton(onClick={capture.id?.let {vm.mark(it,capture.durationMs,note);note=""}},enabled=!busy) {Text("マークを追加")}
                TextButton(onClick={capture.id?.let {pending.value=it to capture.durationMs;photo.launch("image/*")}},enabled=!busy) {Text("写真マーク")}
            }
        }
        if(capture.liveText.isNotBlank()) item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp)) {
            Text("端末内文字起こし",style=MaterialTheme.typography.labelLarge)
            Text(capture.liveText,modifier=Modifier.padding(top=12.dp))
        } } }
        items(r?.marks?.reversed() ?: emptyList(),key={it.id}) { m -> Text("${timeLabel(m.timeMs)}  ${m.text}${if(m.photo != null) " ・写真" else ""}") }
    }
}

private fun inputType(type: Int) = when(type) {
    android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC -> "内蔵マイク"
    android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有線"
    android.media.AudioDeviceInfo.TYPE_USB_DEVICE, android.media.AudioDeviceInfo.TYPE_USB_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
    android.media.AudioDeviceInfo.TYPE_TELEPHONY -> "通話"
    else -> "外部入力"
}

@Composable
private fun ToggleRow(title: String,description: String,value: Boolean,onChange:(Boolean)->Unit,enabled:Boolean=true) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) { Text(title,style=MaterialTheme.typography.titleSmall); Text(description,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(value,onChange,enabled=enabled)
    }
}

@Composable
private fun Waveform(peaks: List<Float>,progress: Float,modifier: Modifier=Modifier) {
    val main = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.semantics { contentDescription="音声の波形" }) {
        if(peaks.isEmpty()) drawLine(muted,Offset(0f,size.height/2),Offset(size.width,size.height/2),2f)
        else {
            val n = minOf(peaks.size,240); val step = size.width/n
            for(i in 0 until n) {
                val amp = peaks[i*peaks.size/n].coerceIn(0.015f,1f)*size.height/2
                val x = (i+0.5f)*step
                drawLine(if(i.toFloat()/n <= progress) main else muted,Offset(x,size.height/2-amp),Offset(x,size.height/2+amp),maxOf(1f,step*0.55f))
            }
        }
    }
}
