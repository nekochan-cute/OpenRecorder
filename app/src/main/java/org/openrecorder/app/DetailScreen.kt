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
import androidx.compose.ui.Modifier
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
    var start by rememberSaveable(r.id) { mutableStateOf("0") }
    var end by rememberSaveable(r.id) { mutableStateOf(java.lang.String.format(java.util.Locale.ROOT,"%.3f",r.durationMs/1000.0)) }
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
        item { PlaybackWave(r.peaks,position.toFloat()/r.durationMs.coerceAtLeast(1))
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
                            TextButton(onClick={vm.deleteMark(r.id,mark.id)},enabled=!locked) {Text("マークを削除")}
                        }
                    } }
                }
                item {
                    HorizontalDivider(); Spacer(Modifier.height(16.dp)); Text("音声を編集",style=MaterialTheme.typography.titleLarge)
                    Text("元の録音を残して、編集版を新しく保存します。切断された文章と要約は再生成してください。",style=MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(start,{start=it},label={Text("開始（秒）")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.weight(1f))
                        OutlinedTextField(end,{end=it},label={Text("終了（秒）")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.weight(1f))
                    }
                    Row {
                        TextButton(onClick={start=java.lang.String.format(java.util.Locale.ROOT,"%.3f",position/1000.0)}) {Text("現在位置を開始に")}
                        TextButton(onClick={end=java.lang.String.format(java.util.Locale.ROOT,"%.3f",position/1000.0)}) {Text("現在位置を終了に")}
                    }
                    fun applyEdit(remove: Boolean) {
                        val a=start.toDoubleOrNull(); val b=end.toDoubleOrNull()
                        if(a==null || b==null || !a.isFinite() || !b.isFinite() || a < 0 || b <= a || b > r.durationMs/1000.0 + 0.0001)
                            vm.message.value="開始・終了の秒数を確認してください"
                        else {vm.stopPlayback();vm.edit(r.id,(a*1000).toLong(),(b*1000).toLong().coerceAtMost(r.durationMs),remove)}
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Button(onClick={applyEdit(false)},enabled=!locked) {Text("選択区間を残す")}
                        OutlinedButton(onClick={applyEdit(true)},enabled=!locked) {Text("選択区間を削除")}
                    }
                }
                item { Row {
                    TextButton(onClick={share(vm.repo.file(r),"audio/wav")},enabled=!locked) {Text("音声を共有")}
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
                    Text("話者ラベルは、対応する文字起こしAPIの結果から取り込みます。端末内の自動話者分離は未搭載です。",style=MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick={share(vm.exportText(r,"transcript"),"text/plain")},enabled=r.segments.isNotEmpty()) {Text("全文を共有")}
                        TextButton(onClick={share(vm.exportText(r,"srt"),"application/x-subrip")},enabled=r.segments.isNotEmpty()) {Text("字幕を共有")}
                        TextButton(onClick={dialog="speaker"},enabled=!locked && r.segments.isNotEmpty()) {Text("話者名")}
                    }
                    if(r.segments.isEmpty()) Text("文字起こしはまだありません。",Modifier.padding(vertical=20.dp))
                }
                itemsIndexed(r.segments) { i,s ->
                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
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
                    Button(onClick={dialog="summary"},enabled=!locked && r.segments.isNotEmpty()) {Text("APIでタイトル・要約を生成")}
                    Text("文字起こしの全文を設定したサーバーに送信します。",style=MaterialTheme.typography.bodySmall)
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
private fun PlaybackWave(peaks:List<Float>,progress:Float) {
    val color=MaterialTheme.colorScheme.primary; val muted=MaterialTheme.colorScheme.outlineVariant
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(76.dp)) {
        if(peaks.isEmpty()) drawLine(muted,androidx.compose.ui.geometry.Offset(0f,size.height/2),androidx.compose.ui.geometry.Offset(size.width,size.height/2),2f)
        peaks.forEachIndexed {i,p ->
            val x=(i+0.5f)*size.width/peaks.size;val h=p.coerceIn(0.015f,1f)*size.height/2
            drawLine(if(i.toFloat()/peaks.size<progress) color else muted,androidx.compose.ui.geometry.Offset(x,size.height/2-h),androidx.compose.ui.geometry.Offset(x,size.height/2+h),maxOf(1f,size.width/peaks.size*0.6f))
        }
    }
}
