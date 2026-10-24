package org.openrecorder.app

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import org.openrecorder.app.ai.AiConfig

@Composable
fun SettingsScreen(vm:RecorderViewModel,locked:Boolean) {
    val context=LocalContext.current
    var loadError by remember {mutableStateOf<String?>(null)}
    var config by remember {mutableStateOf(runCatching {vm.config()}.getOrElse {loadError="保存済みのAPI設定を復号できませんでした。再入力してください。";AiConfig()})}
    var license by remember {mutableStateOf<String?>(null)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri -> if(uri!=null) vm.importModel(uri)}
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(vertical=20.dp)) {
        item {Text("端末内の文字起こし",style=MaterialTheme.typography.headlineSmall)
            Text("文字起こしモデル: ${if(vm.modelAvailable()) "導入済み" else "未導入"}")
            Text("話者モデル: ${if(vm.speakerModelAvailable()) "導入済み（話者を自動分離します）" else "未導入"}")
            Text("モデルのZIPを取り込むと、通信せずに録音中・録音後の文字起こしを実行できます。日本語にはvosk-model-small-ja-0.22が候補です。話者分離にはvosk-model-spk-0.4のZIPを同じボタンから取り込みます。種類はZIPの中身から判別します。",style=MaterialTheme.typography.bodyMedium)
            Row {
                OutlinedButton(onClick={picker.launch(arrayOf("application/zip","application/octet-stream","application/x-zip-compressed"))},enabled=!locked) {Text("モデルZIPを取り込む")}
                TextButton(onClick={runCatching {context.startActivity(Intent(Intent.ACTION_VIEW,"https://alphacephei.com/vosk/models".toUri()))}}) {Text("配布元")}
            }
        }
        item {HorizontalDivider();Text("文字起こしAPI",style=MaterialTheme.typography.headlineSmall)
            Text("保存した音声を、指定したHTTPSエンドポイントへ送信します。タイムスタンプと話者ラベルの対応状況はサーバーによって異なります。",style=MaterialTheme.typography.bodySmall)}
        item {OutlinedTextField(config.transcriptionUrl,{config=config.copy(transcriptionUrl=it)},label={Text("文字起こしの完全なURL")},placeholder={Text("https://server.example/v1/audio/transcriptions")},modifier=Modifier.fillMaxWidth(),enabled=!locked,singleLine=true)}
        item {OutlinedTextField(config.transcriptionModel,{config=config.copy(transcriptionModel=it)},label={Text("文字起こしモデル名")},modifier=Modifier.fillMaxWidth(),enabled=!locked,singleLine=true)}
        item {OutlinedTextField(config.transcriptionKey,{config=config.copy(transcriptionKey=it)},label={Text("文字起こしAPIキー")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth(),enabled=!locked,singleLine=true)}
        item {OutlinedTextField(config.language,{config=config.copy(language=it)},label={Text("音声の言語（ja / en / 空欄で自動）")},modifier=Modifier.fillMaxWidth(),enabled=!locked,singleLine=true)}
        item {HorizontalDivider();Text("要約API",style=MaterialTheme.typography.headlineSmall)
            Text("Chat Completions互換のHTTPSエンドポイントです。文字起こしの全文を送信します。端末内の要約モデルは未搭載です。",style=MaterialTheme.typography.bodySmall)}
        item {OutlinedTextField(config.summaryUrl,{config=config.copy(summaryUrl=it)},label={Text("要約の完全なURL")},placeholder={Text("https://server.example/v1/chat/completions")},modifier=Modifier.fillMaxWidth(),enabled=!locked,singleLine=true)}
        item {OutlinedTextField(config.summaryModel,{config=config.copy(summaryModel=it)},label={Text("要約モデル名")},modifier=Modifier.fillMaxWidth(),enabled=!locked,singleLine=true)}
        item {OutlinedTextField(config.summaryKey,{config=config.copy(summaryKey=it)},label={Text("要約APIキー")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth(),enabled=!locked,singleLine=true)}
        item {loadError?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            Button(onClick={vm.saveConfig(config)},enabled=!locked,modifier=Modifier.fillMaxWidth()) {Text("API設定を保存")}
            Text("APIキーはAndroid Keystoreで暗号化して保存します。通信はAPI機能を実行したときだけ行います。",style=MaterialTheme.typography.bodySmall)}
        item {HorizontalDivider();Text("Open Recorder 0.1.0-dev",style=MaterialTheme.typography.titleMedium)
            Text("Apache License 2.0\n公開資料に基づく独立実装です。OPPO / ColorOSの公式アプリではありません。\n\n本体: Kotlin、AndroidX、Compose、Media3\n音声認識: Vosk / Kaldi\n通信: OkHttp / Okio\nネイティブ連携: JNA（Apache-2.0 / LGPL-2.1のデュアルライセンスからApache-2.0を選択）",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={license=context.assets.open("licenses/Apache-2.0.txt").bufferedReader().use {it.readText()}}) {Text("Apache License 2.0 全文")}
            TextButton(onClick={license=context.assets.open("licenses/THIRD_PARTY_NOTICES.md").bufferedReader().use {it.readText()}}) {Text("第三者ライセンス・出典")}
        }
    }
    license?.let {text -> AlertDialog(onDismissRequest={license=null},title={Text("ライセンス")},
        text={Text(text,Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()),style=MaterialTheme.typography.bodySmall)},
        confirmButton={TextButton(onClick={license=null}) {Text("閉じる")}})}
}
