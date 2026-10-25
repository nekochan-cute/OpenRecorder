@file:OptIn(ExperimentalMaterial3Api::class)
package org.openrecorder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import org.openrecorder.app.ui.icons.automirrored.OpenInNew
import androidx.compose.material.icons.rounded.*
import org.openrecorder.app.ui.icons.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.openrecorder.app.ai.AiConfig
import org.openrecorder.app.ai.Provider

/** [update] changes the draft held by the caller; [back] saves it and leaves. */
class AiActions(
    val update: ((AiConfig) -> AiConfig) -> Unit = {},
    val back: () -> Unit = {},
    val models: suspend (Provider, AiConfig, forTranscription: Boolean) -> List<String> = { _, _, _ -> emptyList() },
    val login: suspend () -> String = { "" },
    val openUrl: (String) -> Unit = {},
    val error: (String) -> Unit = {},
)

@Composable
fun AiScreen(c: AiConfig, actions: AiActions) {
    val set = actions.update
    androidx.activity.compose.BackHandler { actions.back() }
    Scaffold(topBar = {
        TopAppBar(title = { Text("AIサービス") }, navigationIcon = { IconButton(actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "戻る") } })
    }) { padding ->
        Column(Modifier.padding(padding).readable().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            SectionHeader("要約")
            Service(c, false, Provider.entries, c.summaryProvider, { p -> set { it.copy(summaryProvider = p, summaryModel = "") } }, c.summaryBase, { b -> set { it.copy(summaryBase = b) } },
                c.summaryModel, { m -> set { it.copy(summaryModel = m) } }, { p, k -> set { it.copy(keys = it.keys + (p to k)) } }, actions)
            SectionHeader("文字起こし")
            val speech = Provider.entries.filter { it.transcription }
            Service(c, true, speech, c.transcriptionProvider, { p -> set { it.copy(transcriptionProvider = p, transcriptionModel = p?.speechModel.orEmpty()) } },
                c.transcriptionBase, { b -> set { it.copy(transcriptionBase = b) } }, c.transcriptionModel, { m -> set { it.copy(transcriptionModel = m) } },
                { p, k -> set { it.copy(keys = it.keys + (p to k)) } }, actions)
            if (c.transcriptionProvider != null) Field {
                Dropdown("言語", listOf("" to "自動", "ja" to "日本語", "en" to "English"), c.language) { l -> set { it.copy(language = l) } }
            }
        }
    }
}

@Composable
private fun Field(content: @Composable () -> Unit) = Box(Modifier.padding(horizontal = 24.dp, vertical = 6.dp)) { content() }

@Composable
private fun Service(c: AiConfig, speech: Boolean, providers: List<Provider>, provider: Provider?, onProvider: (Provider?) -> Unit,
                    base: String, onBase: (String) -> Unit, model: String, onModel: (String) -> Unit, onKey: (Provider, String) -> Unit, actions: AiActions) {
    Field { Dropdown("サービス", listOf<Pair<Provider?, String>>(null to "使わない") + providers.map { it to it.label }, provider, onProvider) }
    if (provider == null) return
    if (provider == Provider.Custom) Field {
        OutlinedTextField(base, onBase, Modifier.fillMaxWidth(), label = { Text("URL") }, placeholder = { Text("https://example.com/v1") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
    }
    val key = c.keys[provider].orEmpty()
    if (provider.oauth) OAuthKey(key, { onKey(provider, it) }, actions) else Field {
        OutlinedTextField(key, { onKey(provider, it.trim()) }, Modifier.fillMaxWidth(), label = { Text("APIキー") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = provider.keyPage?.let { page -> { IconButton({ actions.openUrl(page) }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "キーを発行") } } })
    }
    ModelField(c, provider, speech, base, key, model, onModel, actions)
}

@Composable
private fun OAuthKey(key: String, onKey: (String) -> Unit, actions: AiActions) {
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var manual by remember { mutableStateOf(false) }
    Field {
        if (key.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text("接続済み", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            TextButton({ onKey("") }) { Text("接続を解除") }
        } else if (manual) {
            // Typed locally: committing each keystroke would replace the field with "接続済み" after the first character.
            var text by remember { mutableStateOf("") }
            val save = { if (text.isNotBlank()) onKey(text.trim()) }
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("APIキー") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { save() }),
                trailingIcon = { TextButton(save, enabled = text.isNotBlank()) { Text("保存") } })
        } else Column {
            Button({
                job = scope.launch {
                    try { onKey(actions.login()) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { actions.error(e.message ?: "ログインできませんでした") }
                    finally { job = null }
                }
            }, Modifier.fillMaxWidth().height(48.dp), enabled = job == null) {
                if (job != null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("OpenRouterでログイン")
            }
            TextButton({ job?.cancel(); manual = true }) { Text("APIキーを入力") }
        }
    }
}

@Composable
private fun ModelField(c: AiConfig, provider: Provider, speech: Boolean, base: String, key: String, model: String, onModel: (String) -> Unit, actions: AiActions) {
    var models by remember(provider) { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(provider, key, base) {
        if (key.isBlank() && provider != Provider.OpenRouter) { models = emptyList(); return@LaunchedEffect }
        delay(500)
        loading = true; failed = false
        models = try { actions.models(provider, c, speech) } catch (e: CancellationException) { throw e } catch (e: Exception) { failed = true; emptyList() }
        loading = false
    }
    val shown = models.filter { model.isBlank() || it.contains(model, true) || model in models }.take(80)
    Field {
        ExposedDropdownMenuBox(open && shown.isNotEmpty(), { open = it }) {
            OutlinedTextField(model, { onModel(it); open = true }, Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable),
                label = { Text("モデル") }, singleLine = true,
                supportingText = if (failed) { { Text("モデル一覧を取得できませんでした") } } else null,
                trailingIcon = { if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else ExposedDropdownMenuDefaults.TrailingIcon(open) })
            ExposedDropdownMenu(open && shown.isNotEmpty(), { open = false }) {
                shown.forEach { m -> DropdownMenuItem({ Text(m) }, { onModel(m); open = false }) }
            }
        }
    }
}

@Composable
fun <T> Dropdown(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = it }) {
        OutlinedTextField(options.firstOrNull { it.first == selected }?.second.orEmpty(), {}, Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            readOnly = true, label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) })
        ExposedDropdownMenu(open, { open = false }) {
            options.forEach { (value, text) -> DropdownMenuItem({ Text(text) }, { onSelect(value); open = false }) }
        }
    }
}
