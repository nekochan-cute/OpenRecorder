package org.openrecorder.app

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openrecorder.app.core.*
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun DetailScreen(vm: RecorderViewModel,r: Recording,busy: Boolean,share:(File,String)->Unit,onBack:()->Unit) {
    val context = LocalContext.current
    val position by vm.position.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val capture by vm.capture.collectAsStateWithLifecycle()
    val locked = busy || capture.id != null || r.unfinished || r.deletedAt != null
    var tab by rememberSaveable(r.id) { mutableIntStateOf(0) }
    var dialog by remember(r.id) { mutableStateOf("") }
    var selectedSegment by remember(r.id) { mutableIntStateOf(-1) }
    var speed by remember { mutableFloatStateOf(vm.player.playbackParameters.speed) }
    var skip by remember { mutableStateOf(vm.player.skipSilenceEnabled) }
    var note by rememberSaveable(r.id) { mutableStateOf("") }
    var range by remember(r.id,r.durationMs) { mutableStateOf(0f..r.durationMs.toFloat().coerceAtLeast(1f)) }
    var selectedMark by remember(r.id) { mutableStateOf<Mark?>(null) }
    var noise by rememberSaveable(r.id) { mutableStateOf(true) }
    var strength by rememberSaveable(r.id) { mutableFloatStateOf(0.6f) }
    var enhance by rememberSaveable(r.id) { mutableStateOf(true) }
    var normalize by rememberSaveable(r.id) { mutableStateOf(true) }
    var name by remember(r.id,r.title) { mutableStateOf(r.title) }
    var folder by remember(r.id,r.folder) { mutableStateOf(r.folder) }
    var template by rememberSaveable { mutableStateOf("会議") }
    var photoTime by remember { mutableLongStateOf(0) }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if(uri != null) vm.mark(r.id,photoTime,note,uri) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        if(uri != null) vm.task("音声を書き出し中") {
            context.contentResolver.openOutputStream(uri,"wt")!!.use { output -> vm.repo.file(r).inputStream().use { it.copyTo(output) } }
            vm.message.value="音声を書き出しました"
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(vertical=16.dp)) {
        item {
            Text(r.title,style=MaterialTheme.typography.headlineMedium)
            Text("${timeLabel(r.durationMs)}  ·  ${r.sampleRate/1000}kHz / ${if(r.channels==1) "モノラル" else "ステレオ"}  ·  WAV",style=MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick={vm.favorite(r.id)},enabled=!locked) {Text(if(r.favorite) "★ お気に入り" else "☆ お気に入り")}
                TextButton(onClick={dialog="name"},enabled=!locked) {Text("名前・分類")}
                TextButton(onClick={dialog="trash"},enabled=!locked) {Text("ごみ箱へ")}
            }
        }
        item { PlaybackWave(r.peaks,position,r.durationMs,r.marks,if(tab == 0) range else null,!locked,vm::seek)
            Slider(value=position.toFloat().coerceIn(0f,r.durationMs.toFloat().coerceAtLeast(1f)),onValueChange={vm.seek(it.toLong())},
                valueRange=0f..r.durationMs.toFloat().coerceAtLeast(1f),enabled=!locked)
            Text("${timeLabel(position)} / ${timeLabel(r.durationMs)}",style=MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                OutlinedButton(onClick={vm.seek((position-10000).coerceAtLeast(0))},enabled=!locked) {Text("−10秒")}
                Button(onClick=vm::togglePlay,enabled=!locked) {Text(if(playing) "一時停止" else "再生")}
                OutlinedButton(onClick={vm.seek((position+10000).coerceAtMost(r.durationMs))},enabled=!locked) {Text("＋10秒")}
            }
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                var menu by remember {mutableStateOf(false)}
                Box {
                    TextButton(onClick={menu=true}) {Text("速度 ${speed}×")}
                    DropdownMenu(menu,{menu=false}) { listOf(0.5f,0.75f,1f,1.25f,1.5f,2f).forEach { value ->
                        DropdownMenuItem(text={Text("${value}×")},onClick={speed=value;vm.player.setPlaybackSpeed(value);menu=false})
                    } }
                }
                FilterChip(skip,{skip=!skip;vm.player.skipSilenceEnabled=skip},label={Text("無音スキップ")})
            }
        }
        item { PrimaryTabRow(selectedTabIndex=tab) { listOf("音声・マーク","文字起こし","要約").forEachIndexed { i,t -> Tab(tab==i,{tab=i},text={Text(t)}) } } }
        when(tab) {
            0 -> {
                item {
                    OutlinedTextField(note,{note=it},label={Text("マークのメモ")},modifier=Modifier.fillMaxWidth())
                    Row {
                        TextButton(onClick={vm.mark(r.id,position.coerceAtMost(r.durationMs),note);note=""},enabled=!locked) {Text("現在位置にマーク")}
                        TextButton(onClick={photoTime=position.coerceAtMost(r.durationMs);photo.launch("image/*")},enabled=!locked) {Text("写真を付ける")}
                    }
                }
                items(r.marks,key={it.id}) { mark ->
                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
                        TextButton(onClick={vm.seek(mark.timeMs)}) {Text("${timeLabel(mark.timeMs)}  ${mark.text}")}
                        Row {
                            if(mark.photo != null) TextButton(onClick={
                                try {
                                    val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",vm.repo.photoFile(mark.photo))
                                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                } catch(e: Exception) {vm.message.value="写真を開けませんでした"}
                            }) {Text("写真を開く")}
                            TextButton(onClick={selectedMark=mark;dialog="mark"},enabled=!locked) {Text("メモを修正")}
                            TextButton(onClick={vm.deleteMark(r.id,mark.id)},enabled=!locked) {Text("マークを削除")}
                        }
                    } }
                }
                item {
                    HorizontalDivider(); Spacer(Modifier.height(16.dp)); Text("音声を編集",style=MaterialTheme.typography.titleLarge)
                    Text("元の録音を残して、編集版を新しく保存します。切断された文章と要約は再生成してください。",style=MaterialTheme.typography.bodySmall)
                    Text("選択区間: ${timeLabel(range.start.toLong())} – ${timeLabel(range.endInclusive.toLong())}（${timeLabel((range.endInclusive-range.start).toLong())}）",
                        style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(top=8.dp))
                    RangeSlider(value=range,onValueChange={range=it},valueRange=0f..r.durationMs.toFloat().coerceAtLeast(1f),enabled=!locked)
                    Row {
                        TextButton(onClick={range=position.toFloat().coerceAtMost(range.endInclusive-1)..range.endInclusive},enabled=!locked) {Text("現在位置を開始に")}
                        TextButton(onClick={range=range.start..position.toFloat().coerceAtLeast(range.start+1)},enabled=!locked) {Text("現在位置を終了に")}
                    }
                    fun applyEdit(remove: Boolean) {
                        val a=range.start.toLong(); val b=range.endInclusive.toLong().coerceAtMost(r.durationMs)
                        if(b <= a) vm.message.value="区間を選択してください"
                        else if(!remove && a == 0L && b == r.durationMs) vm.message.value="全体が選択されています。残す区間を選択してください"
                        else {vm.stopPlayback();vm.edit(r.id,a,b,remove)}
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Button(onClick={applyEdit(false)},enabled=!locked) {Text("選択区間を残す")}
                        OutlinedButton(onClick={applyEdit(true)},enabled=!locked) {Text("選択区間を削除")}
                    }
                }
                item {
                    HorizontalDivider(); Spacer(Modifier.height(16.dp)); Text("音声を整える",style=MaterialTheme.typography.titleLarge)
                    Text("端末内で処理し、元の録音を残して処理版を新しく保存します。マークと文章はそのまま引き継ぎます。",style=MaterialTheme.typography.bodySmall)
                    Toggle("ノイズ低減","エアコン・ファンなどの一定の雑音を抑えます",noise,{noise=it},!locked)
                    if(noise) {
                        Text("強さ ${(strength*100).toInt()}%",style=MaterialTheme.typography.labelMedium)
                        Slider(strength,{strength=it},valueRange=0f..1f,enabled=!locked)
                    }
                    Toggle("音声強調","低音の雑音を除き、声の帯域を聞き取りやすくします",enhance,{enhance=it},!locked)
                    Toggle("音量の調整","小さな録音を聞きやすい音量へそろえます（最大+24dB）",normalize,{normalize=it},!locked)
                    Button(onClick={vm.stopPlayback();vm.process(r.id,ProcessOptions(noise,strength,enhance,normalize))},enabled=!locked && (noise || enhance || normalize)) {Text("処理版を保存")}
                }
                item { Row {
                    TextButton(onClick={share(vm.repo.file(r),"audio/wav")},enabled=!locked) {Text("WAVで共有")}
                    TextButton(onClick={vm.exportAac(r) { share(it,"audio/mp4") }},enabled=!locked) {Text("M4Aで共有")}
                    TextButton(onClick={export.launch("${r.title.replace(Regex("[\\/:*?\"<>|]"),"_")}.wav")},enabled=!locked) {Text("音声を書き出す")}
                }
                    if(r.marks.isNotEmpty()) TextButton(onClick={share(vm.exportText(r,"marks"),"text/plain")}) {Text("マーク一覧を共有")}
                }
            }
            1 -> {
                item {
                    Text("文字起こし",style=MaterialTheme.typography.titleLarge)
                    Text("文章をタップすると、その位置から確認できます。再実行すると文章と要約が置き換わります。",style=MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(onClick={dialog="local"},enabled=!locked && vm.modelAvailable()) {Text("端末内で実行")}
                        OutlinedButton(onClick={dialog="remote"},enabled=!locked) {Text("APIで実行")}
                    }
                    Text(if(vm.speakerModelAvailable()) "端末内の文字起こしでは、話者モデルで話者を自動分離します。APIの結果に話者ラベルがあれば取り込みます。"
                        else "設定からVoskの話者モデルを取り込むと、端末内で話者を自動分離できます。APIの結果に話者ラベルがあれば取り込みます。",style=MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick={share(vm.exportText(r,"transcript"),"text/plain")},enabled=r.segments.isNotEmpty()) {Text("全文を共有")}
                        TextButton(onClick={share(vm.exportText(r,"srt"),"application/x-subrip")},enabled=r.segments.isNotEmpty()) {Text("SRT")}
                        TextButton(onClick={share(vm.exportText(r,"vtt"),"text/vtt")},enabled=r.segments.isNotEmpty()) {Text("WebVTT")}
                        TextButton(onClick={dialog="speaker"},enabled=!locked && r.segments.isNotEmpty()) {Text("話者名")}
                    }
                    if(r.segments.isEmpty()) Text("文字起こしはまだありません。",Modifier.padding(vertical=20.dp))
                }
                itemsIndexed(r.segments) { i,s ->
                    val current = playing && position >= s.startMs && position < s.endMs
                    OutlinedCard(Modifier.fillMaxWidth(),colors=if(current) CardDefaults.outlinedCardColors(containerColor=MaterialTheme.colorScheme.primaryContainer) else CardDefaults.outlinedCardColors()) { Column(Modifier.padding(16.dp)) {
                        Text("${timeLabel(s.startMs)}  ${s.speaker}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                        Text(s.text,Modifier.fillMaxWidth().clickable {vm.seek(s.startMs)}.padding(vertical=12.dp))
                        TextButton(onClick={selectedSegment=i;dialog="segment"},enabled=!locked) {Text("文章・話者を修正")}
                    } }
                }
            }
            else -> {
                item {
                    Text("用途に合わせて整理",style=MaterialTheme.typography.titleLarge)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf("会議","講義","インタビュー").forEach { t -> FilterChip(template==t,{template=t},label={Text(t)}) }
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(onClick={dialog="localSummary"},enabled=!locked && r.segments.isNotEmpty()) {Text("端末内で要約")}
                        OutlinedButton(onClick={dialog="summary"},enabled=!locked && r.segments.isNotEmpty()) {Text("APIで要約")}
                    }
                    Text("端末内の要約は通信せず、原文の文を抜き出して概要・決定事項・作業項目などに整理します。APIの要約は文字起こしの全文を設定したサーバーに送信します。どちらもタイトルを更新します。",style=MaterialTheme.typography.bodySmall)
                }
                item { SelectionContainer { Text(r.summary.ifBlank {"要約はまだありません。先に文字起こしを実行してください。"}) } }
                item { TextButton(onClick={share(vm.exportText(r,"summary"),"text/markdown")},enabled=r.summary.isNotBlank()) {Text("要約を共有")}
                    Text("生成結果は、音声・原文と照合して確認してください。",style=MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    when(dialog) {
        "name" -> AlertDialog(onDismissRequest={dialog=""},title={Text("名前・フォルダー")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name,{name=it},label={Text("録音名")});OutlinedTextField(folder,{folder=it},label={Text("フォルダー名（空欄で未分類）")})
        }},confirmButton={TextButton(onClick={vm.rename(r.id,name,folder);dialog=""}) {Text("保存")}},dismissButton={TextButton(onClick={dialog=""}) {Text("キャンセル")}})
        "trash" -> Confirm("ごみ箱へ移動","音声と文章を、ごみ箱から復元できる状態で保持します。",{dialog=""}) {vm.trash(r.id);dialog="";onBack()}
        "local" -> Confirm("端末内で文字起こし","導入したモデルで処理します。現在の文章と要約を置き換えます。",{dialog=""}) {vm.transcribe(r.id,false);dialog=""}
        "remote" -> Confirm("APIで文字起こし","音声ファイルを設定した文字起こしサーバーへ送信します。現在の文章と要約を置き換えます。",{dialog=""}) {vm.transcribe(r.id,true);dialog=""}
        "summary" -> Confirm("APIで要約","文字起こしを設定した要約サーバーへ送信し、録音名と要約を更新します。",{dialog=""}) {vm.summarize(r.id,template);dialog=""}
        "localSummary" -> Confirm("端末内で要約","文字起こしから要約と自動タイトルを作成し、録音名と現在の要約を置き換えます。",{dialog=""}) {vm.summarizeLocal(r.id,template);dialog=""}
        "mark" -> selectedMark?.let { m ->
            var text by remember(m.id) {mutableStateOf(m.text)}
            AlertDialog(onDismissRequest={dialog=""},title={Text("マークのメモ（${timeLabel(m.timeMs)}）")},text={OutlinedTextField(text,{text=it},label={Text("メモ")},minLines=2,maxLines=6)},
                confirmButton={TextButton(onClick={vm.updateMark(r.id,m.id,text);dialog=""}) {Text("保存")}},dismissButton={TextButton(onClick={dialog=""}) {Text("キャンセル")}})
        }
        "segment" -> r.segments.getOrNull(selectedSegment)?.let { s ->
            var text by remember(selectedSegment) {mutableStateOf(s.text)}
            var speaker by remember(selectedSegment) {mutableStateOf(s.speaker)}
            AlertDialog(onDismissRequest={dialog=""},title={Text("文章・話者を修正")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(speaker,{speaker=it},label={Text("話者名")});OutlinedTextField(text,{text=it},label={Text("文章")},minLines=3,maxLines=8)
            }},confirmButton={TextButton(onClick={vm.updateSegment(r.id,selectedSegment,text,speaker);dialog=""}) {Text("保存")}},dismissButton={TextButton(onClick={dialog=""}) {Text("キャンセル")}})
        }
        "speaker" -> {
            val names = r.segments.map {it.speaker}.distinct()
            var old by remember {mutableStateOf(names.firstOrNull() ?: "")}
            var next by remember {mutableStateOf("")}
            AlertDialog(onDismissRequest={dialog=""},title={Text("話者名をまとめて変更")},text={Column {
                names.forEach { n -> FilterChip(old==n,{old=n},label={Text(n.ifBlank {"未指定"})}) }
                OutlinedTextField(next,{next=it},label={Text("新しい名前")})
            }},confirmButton={TextButton(onClick={vm.renameSpeaker(r.id,old,next);dialog=""}) {Text("変更")}},dismissButton={TextButton(onClick={dialog=""}) {Text("キャンセル")}})
        }
    }
}

@Composable
private fun Confirm(title:String,text:String,dismiss:()->Unit,confirm:()->Unit) {
    AlertDialog(onDismissRequest=dismiss,title={Text(title)},text={Text(text)},confirmButton={TextButton(onClick=confirm) {Text("実行")}},dismissButton={TextButton(onClick=dismiss) {Text("キャンセル")}})
}

@Composable
private fun Toggle(title: String, description: String, value: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title,style=MaterialTheme.typography.titleSmall); Text(description,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(value,onChange,enabled=enabled)
    }
}

/** Waveform with playhead, marks and the edit selection. Tap or drag to seek. */
@Composable
private fun PlaybackWave(peaks: List<Float>, position: Long, durationMs: Long, marks: List<Mark>, selection: ClosedFloatingPointRange<Float>?, enabled: Boolean, seek: (Long) -> Unit) {
    val color=MaterialTheme.colorScheme.primary; val muted=MaterialTheme.colorScheme.outlineVariant
    val markColor=MaterialTheme.colorScheme.tertiary; val selected=MaterialTheme.colorScheme.primary.copy(alpha=0.12f)
    val duration=durationMs.coerceAtLeast(1)
    val progress=position.toFloat()/duration
    fun at(x: Float, width: Int) = (x/width.coerceAtLeast(1)*duration).toLong().coerceIn(0,durationMs)
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(88.dp)
        .semantics { contentDescription="音声の波形。タップまたはドラッグで再生位置を移動" }
        .pointerInput(enabled,durationMs) { if(enabled) detectTapGestures { seek(at(it.x,size.width)) } }
        .pointerInput(enabled,durationMs) { if(enabled) detectHorizontalDragGestures { change,_ -> seek(at(change.position.x,size.width)) } }) {
        selection?.let { drawRect(selected,Offset(it.start/duration*size.width,0f),androidx.compose.ui.geometry.Size((it.endInclusive-it.start)/duration*size.width,size.height)) }
        if(peaks.isEmpty()) drawLine(muted,Offset(0f,size.height/2),Offset(size.width,size.height/2),2f)
        peaks.forEachIndexed {i,p ->
            val x=(i+0.5f)*size.width/peaks.size;val h=p.coerceIn(0.015f,1f)*size.height*0.42f
            drawLine(if(i.toFloat()/peaks.size<progress) color else muted,Offset(x,size.height/2-h),Offset(x,size.height/2+h),maxOf(1f,size.width/peaks.size*0.6f))
        }
        marks.forEach { m -> val x=m.timeMs.toFloat()/duration*size.width; drawLine(markColor,Offset(x,0f),Offset(x,size.height*0.18f),3f); drawCircle(markColor,4f,Offset(x,3f)) }
        val head=progress.coerceIn(0f,1f)*size.width
        drawLine(color,Offset(head,0f),Offset(head,size.height),2.5f)
    }
}
