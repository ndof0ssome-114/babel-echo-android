package app.babelecho.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.babelecho.android.cloud.CloudClient
import app.babelecho.android.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

private enum class SettingsSection { Common, Services, Advanced }
private data class ServiceEditor(val asr: AsrProfile? = null, val text: TextProfile? = null)
private data class KeyEditor(val category: String, val id: String)

@Composable
internal fun SettingsScreen(modifier: Modifier, draft: AppSettings, onChange: (AppSettings) -> Unit,
    onSave: (AppSettings) -> Unit, locked: Boolean, unsaved: Boolean) {
    val value = draft
    val lang = value.uiLanguage
    val context = LocalContext.current
    val catalog = remember { ModelCatalog(context) }
    val scope = rememberCoroutineScope()
    var section by rememberSaveable { mutableStateOf(SettingsSection.Common) }
    var editor by remember { mutableStateOf<ServiceEditor?>(null) }
    var keyEditor by remember { mutableStateOf<KeyEditor?>(null) }
    var delete by remember { mutableStateOf<KeyEditor?>(null) }
    DisposableEffect(catalog) { onDispose { catalog.close() } }
    fun update(profile: AsrProfile) = onChange(value.withAsrProfile(profile))
    fun update(profile: TextProfile) = onChange(value.withTextProfile(profile))
    val keyAction: (String, String) -> Unit = { category, id -> keyEditor = KeyEditor(category, id) }

    Column(modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t(lang, "设置", "設定", "Settings"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(if (unsaved) t(lang, "有未保存的修改", "未保存の変更", "Unsaved changes") else t(lang, "密钥和会议保存在本机", "キーと会議は端末に保存", "Keys and meetings stay on this device"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = { onSave(value) }, enabled = !locked && unsaved) { Text(t(lang, "保存", "保存", "Save")) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsSection.entries.forEach { item ->
                FilterChip(section == item, { section = item }, label = { Text(when (item) {
                    SettingsSection.Common -> t(lang, "常用", "基本", "Common")
                    SettingsSection.Services -> t(lang, "模型服务", "サービス", "Services")
                    SettingsSection.Advanced -> t(lang, "高级", "詳細", "Advanced")
                }) })
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (section == SettingsSection.Common) {
                SettingsCard(t(lang, "语言", "言語", "Languages")) {
                    Fold(t(lang, "界面与识别语言", "表示と認識言語", "Interface and speech languages"), language(value.uiLanguage) + " · " + language(value.asrLanguage, lang)) {
                    Selection(t(lang, "界面语言", "表示言語", "Interface language"), value.uiLanguage,
                        listOf("zh", "ja", "en").map { Option(it, language(it)) }) { onChange(value.copy(uiLanguage = it)) }
                    Selection(t(lang, "识别语言", "認識言語", "Speech language"), value.asrLanguage,
                        ProviderDefaults.languages.map { Option(it, language(it, lang)) }) { onChange(value.copy(asrLanguage = it)) }
                    Selection(t(lang, "翻译为", "翻訳先", "Translate to"), value.translateTo,
                        listOf(Option("", t(lang, "关闭", "オフ", "Off"))) + ProviderDefaults.languages.filter { it != "auto" }.map { Option(it, language(it)) }) {
                        onChange(value.copy(translateTo = it))
                    }
                    }
                }
                SettingsCard(t(lang, "语音识别", "音声認識", "Speech recognition")) {
                    val profile = value.asrProfiles.firstOrNull { it.id == value.asrRoutes[value.asrLanguage] }
                    Selection(t(lang, "识别服务", "認識サービス", "ASR service"), profile?.id.orEmpty(), value.asrProfiles.map {
                        Option(it.id, asrName(it, lang), it.supports(value.asrLanguage))
                    }) { id ->
                        val selected = value.asrProfiles.first { it.id == id }
                        onChange(value.copy(asrRoutes = value.asrRoutes + (value.asrLanguage to selected.id)))
                    }
                    if (profile != null) {
                        ServiceStatus(profile.enabled, profile.apiKey, profile.noAuth, lang)
                        ProviderAddress(profile.baseUrl)
                        KeyActions("asr", profile.id, profile.apiKey, profile.noAuth, lang, keyAction)
                        if (!profile.enabled && (profile.noAuth || profile.apiKey.isNotBlank())) SwitchRow(t(lang, "启用此服务", "このサービスを有効化", "Enable this service"), false) { update(profile.copy(enabled = it)) }
                        Text(t(lang, "模型", "モデル", "Model") + ": " + profile.model, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { editor = ServiceEditor(asr = profile) }) { Text(t(lang, "服务选项", "サービスの詳細", "Service options")) }
                    }
                    Fold(t(lang, "按语言选择服务", "言語別サービス", "Services by language"), t(lang, "为不同语言指定识别引擎", "言語別に認識エンジンを選択", "Choose an engine for each language")) {
                        ProviderDefaults.languages.forEach { code ->
                            Selection(language(code, lang), value.asrRoutes[code].orEmpty(), value.asrProfiles.map { Option(it.id, asrName(it, lang), it.supports(code)) }) {
                                onChange(value.copy(asrRoutes = value.asrRoutes + (code to it)))
                            }
                        }
                    }
                }
                SettingsCard(t(lang, "文本模型", "テキストモデル", "Text models")) {
                    val role = value.textRoles.getValue("summary")
                    val profile = value.textProfiles.firstOrNull { it.id == role.provider }
                    Selection(t(lang, "常用服务", "基本サービス", "Default service"), role.provider,
                        value.textProfiles.map { Option(it.id, textName(it, lang)) }) { id ->
                        val selected = value.textProfiles.first { it.id == id }
                        onChange(value.copy(textRoles = ProviderDefaults.tasks.associateWith { TextModelPolicy.initial(selected, it) }))
                    }
                    if (profile != null) {
                        ServiceStatus(profile.enabled, profile.apiKey, profile.noAuth, lang)
                        ProviderAddress(profile.baseUrl)
                        KeyActions("text", profile.id, profile.apiKey, profile.noAuth, lang, keyAction)
                        if (!profile.enabled && (profile.noAuth || profile.apiKey.isNotBlank())) SwitchRow(t(lang, "启用此服务", "このサービスを有効化", "Enable this service"), false) { update(profile.copy(enabled = it)) }
                        val mimo = value.asrProfiles.firstOrNull { it.id == "mimo" && it.baseUrl.trimEnd('/') == profile.baseUrl.trimEnd('/') && it.apiKey.isNotBlank() }
                        if (profile.id == "mimo" && profile.apiKey.isBlank() && mimo != null) TextButton(onClick = { update(profile.copy(apiKey = mimo.apiKey, enabled = true)) }) {
                            Text(t(lang, "使用 MiMo 语音服务的密钥", "MiMo 音声サービスのキーを使う", "Use the MiMo ASR key"))
                        }
                        ModelSelector(t(lang, "通用模型", "共通モデル", "Shared model"), role.model, ProviderDefaults.textModels(profile.baseUrl),
                            catalog.entry("text", profile.id, profile.baseUrl, profile.apiKey, profile.noAuth, value.allowInsecureHttp), scope, lang) { model ->
                            onChange(value.copy(textRoles = value.textRoles.mapValues { (_, old) -> old.copy(provider = profile.id, model = model) }))
                        }
                        Text(t(lang, "选择通用模型会应用到所有任务；也可分别设置。", "共通モデルは全タスクに適用。個別にも設定できます。", "Choosing a shared model applies it to every task. You can also configure tasks individually."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Fold(t(lang, "分别设置任务模型", "タスク別モデル", "Models by task"), t(lang, "摘要、纪要、翻译、问答与说话人", "要約・議事録・翻訳・質問・話者", "Summary, minutes, translation, Q&A and speakers")) {
                        ProviderDefaults.tasks.forEach { task ->
                            val selected = value.textRoles.getValue(task)
                            Fold(taskName(task, lang), textName(value.textProfiles.firstOrNull { it.id == selected.provider }, lang) + " · " + selected.model.ifBlank { t(lang, "未选择", "未選択", "Not selected") }) {
                                Selection(t(lang, "服务", "サービス", "Provider"), selected.provider, value.textProfiles.map { Option(it.id, textName(it, lang)) }) { id ->
                                    onChange(value.copy(textRoles = value.textRoles + (task to TextModelPolicy.initial(value.textProfiles.first { it.id == id }, task))))
                                }
                                val provider = value.textProfiles.firstOrNull { it.id == selected.provider }
                                if (provider != null) {
                                    ModelSelector(t(lang, "模型", "モデル", "Model"), selected.model, ProviderDefaults.textModels(provider.baseUrl),
                                        catalog.entry("text", provider.id, provider.baseUrl, provider.apiKey, provider.noAuth, value.allowInsecureHttp), scope, lang) {
                                        onChange(value.copy(textRoles = value.textRoles + (task to selected.copy(model = it))))
                                    }
                                    if (TextModelPolicy.isDeepSeek(provider.baseUrl)) Selection(t(lang, "思考模式", "思考モード", "Thinking mode"), selected.thinking ?: "default",
                                        listOf(Option("default", t(lang, "服务默认", "サービス既定", "Provider default")), Option("disabled", t(lang, "关闭思考", "思考を無効", "Disabled")), Option("enabled", t(lang, "开启思考", "思考を有効", "Enabled")))) {
                                        onChange(value.copy(textRoles = value.textRoles + (task to selected.copy(thinking = it))))
                                    }
                                    if (!provider.enabled) Note(t(lang, "此服务尚未启用，请先配置密钥或在模型服务页启用。", "キーを設定するかサービスを有効化してください。", "Configure the key or enable this provider in Services."))
                                }
                            }
                        }
                    }
                }
                SettingsCard(t(lang, "录音后处理", "録音と処理", "Recording and processing")) {
                    SwitchRow(t(lang, "实时转写", "リアルタイム文字起こし", "Live transcription"), value.liveTranscription) { onChange(value.copy(liveTranscription = it)) }
                    Note(t(lang, "开启后，音频片段会发送到选定的识别服务。", "有効にすると音声を選択した認識サービスに送信します。", "When enabled, audio chunks go to the selected ASR service."))
                    SwitchRow(t(lang, "结束后生成纪要", "終了後に議事録を作成", "Minutes after stopping"), value.autoMinutes) { onChange(value.copy(autoMinutes = it)) }
                    Note(t(lang, "有转写和可用模型时发送文字生成纪要。", "文字起こしとモデルがある場合にテキストを送信。", "Sends existing transcript text when the minutes model is configured."))
                }
            }
            if (section == SettingsSection.Services) {
                Note(t(lang, "云服务已提供地址、协议与预设。配置密钥即可启用；本地或自定义服务可在选项中修改。", "クラウドの URL・方式・プリセットを用意。キーを設定すると有効化できます。", "Cloud endpoints, protocols and presets are provided. Configure a key to enable a service; edit options for custom or local services."))
                SettingsCard(t(lang, "语音识别服务", "音声認識サービス", "ASR services")) {
                    value.asrProfiles.forEach { profile ->
                        ProviderRow(asrName(profile, lang), profile.model, profile.enabled, profile.apiKey, profile.noAuth, lang,
                            { update(profile.copy(enabled = it)) }, { keyEditor = KeyEditor("asr", profile.id) }, { editor = ServiceEditor(asr = profile) })
                        HorizontalDivider()
                    }
                    TextButton(onClick = { editor = ServiceEditor(asr = AsrProfile("custom-${UUID.randomUUID().toString().take(8)}", "openai", "", "")) }) { Text(t(lang, "添加自定义 ASR", "カスタム ASR を追加", "Add custom ASR")) }
                }
                SettingsCard(t(lang, "文本服务", "テキストサービス", "Text services")) {
                    value.textProfiles.forEach { profile ->
                        ProviderRow(textName(profile, lang), profile.baseUrl, profile.enabled, profile.apiKey, profile.noAuth, lang,
                            { update(profile.copy(enabled = it)) }, { keyEditor = KeyEditor("text", profile.id) }, { editor = ServiceEditor(text = profile) })
                        HorizontalDivider()
                    }
                    TextButton(onClick = { editor = ServiceEditor(text = TextProfile("custom-${UUID.randomUUID().toString().take(8)}", "")) }) { Text(t(lang, "添加自定义文本服务", "カスタムテキストサービスを追加", "Add custom text service")) }
                }
            }
            if (section == SettingsSection.Advanced) {
                SettingsCard(t(lang, "实时识别节奏", "リアルタイム認識", "Live recognition")) {
                    SwitchRow(t(lang, "静音断句（停顿 1.2 秒）", "無音で区切る（1.2 秒）", "Split at silence (1.2 seconds)"), value.liveUtterances) { onChange(value.copy(liveUtterances = it)) }
                    Stepper(t(lang, "最长分段 / 秒", "最長チャンク / 秒", "Maximum chunk / seconds"), value.chunkSeconds, 10..120, 5) { onChange(value.copy(chunkSeconds = it, overlapSeconds = minOf(value.overlapSeconds, it - 1))) }
                    Stepper(t(lang, "重叠 / 秒", "重複 / 秒", "Overlap / seconds"), value.overlapSeconds, 0..minOf(10, value.chunkSeconds - 1), 1) { onChange(value.copy(overlapSeconds = it)) }
                    Stepper(t(lang, "自动摘要 / 秒（0 关闭）", "自動要約 / 秒（0 オフ）", "Auto summary / seconds (0 off)"), value.autoSummarySeconds, 0..1800, 60) { onChange(value.copy(autoSummarySeconds = if (it in 1..59) 0 else it)) }
                    Text(t(lang, "声音阈值", "音声閾値", "Voice threshold") + ": %.4f".format(value.vadThreshold))
                    Slider(value.vadThreshold.toFloat().coerceIn(.0001f, .02f), { onChange(value.copy(vadThreshold = it.toDouble())) }, valueRange = .0001f.. .02f)
                }
                SettingsCard(t(lang, "连接与隐私", "接続とプライバシー", "Connections and privacy")) {
                    SwitchRow(t(lang, "允许 HTTP", "HTTP を許可", "Allow HTTP"), value.allowInsecureHttp) { onChange(value.copy(allowInsecureHttp = it)) }
                    Note(t(lang, "HTTP 不加密音频、文字与密钥，仅用于可信局域网。", "HTTP は音声・テキスト・キーを暗号化しません。", "HTTP does not encrypt audio, text or keys; trusted LAN only."))
                }
                Fold(t(lang, "费用估算", "費用の概算", "Cost estimates"), t(lang, "可选，填写后用于统计估算", "任意。統計の概算に使用", "Optional rates for local estimates")) {
                    Selection(t(lang, "币种", "通貨", "Currency"), value.priceCurrency, listOf("USD", "CNY", "JPY").map { Option(it, it) }) { onChange(value.copy(priceCurrency = it)) }
                    DecimalField(t(lang, "ASR / 音频小时", "ASR / 音声時間", "ASR / audio hour"), value.asrPricePerHour) { onChange(value.copy(asrPricePerHour = it)) }
                    DecimalField(t(lang, "输入 / 百万 token", "入力 / 100万 token", "Input / million tokens"), value.inputPricePerMillion) { onChange(value.copy(inputPricePerMillion = it)) }
                    DecimalField(t(lang, "输出 / 百万 token", "出力 / 100万 token", "Output / million tokens"), value.outputPricePerMillion) { onChange(value.copy(outputPricePerMillion = it)) }
                }
            }
            if (locked) Note(t(lang, "录音或处理结束后可以保存设置。", "録音・処理が終了すると保存できます。", "Save settings after recording or processing finishes."))
        }
    }

    keyEditor?.let { item ->
        val asr = value.asrProfiles.firstOrNull { item.category == "asr" && it.id == item.id }
        val text = value.textProfiles.firstOrNull { item.category == "text" && it.id == item.id }
        if (asr != null || text != null) KeyDialog(asr?.let { asrName(it, lang) } ?: textName(text, lang), asr?.apiKey ?: text!!.apiKey, lang,
            { keyEditor = null }) { key ->
            if (asr != null) update(asr.copy(apiKey = key, enabled = key.isNotBlank() || asr.noAuth))
            else update(text!!.copy(apiKey = key, enabled = key.isNotBlank() || text.noAuth))
            keyEditor = null
        }
    }
    editor?.let { item -> ServiceDialog(item, value, catalog, scope, lang, { editor = null }, { category, id -> delete = KeyEditor(category, id) }) { next ->
        when {
            next.asr != null -> onChange(value.withAsrProfile(next.asr))
            next.text != null -> onChange(value.withTextProfile(next.text))
        }
        editor = null
    } }
    delete?.let { item -> AlertDialog(onDismissRequest = { delete = null }, title = { Text(t(lang, "删除此服务？", "サービスを削除？", "Remove this service?")) },
        text = { Text(t(lang, "保存后清除本机此服务的密钥，使用它的语言或任务需要重新选择服务。", "保存後にキーを削除。割り当てた言語やタスクを再設定してください。", "Saving clears this provider's local key. Reassign languages and tasks that use it.")) },
        confirmButton = { TextButton(onClick = {
            onChange(if (item.category == "asr") value.removeAsrProfile(item.id) else value.removeTextProfile(item.id))
            delete = null; editor = null
        }) { Text(t(lang, "删除", "削除", "Remove")) } }, dismissButton = { TextButton(onClick = { delete = null }) { Text(t(lang, "取消", "キャンセル", "Cancel")) } }) }
}

private data class ModelEndpoint(val category: String, val id: String, val url: String, val key: String, val noAuth: Boolean, val http: Boolean)
private class ModelEntry(val endpoint: ModelEndpoint, val cloud: CloudClient) {
    var models by mutableStateOf(emptyList<String>())
    var loading by mutableStateOf(false)
    var status by mutableStateOf("")
    var active = true
    fun load(scope: CoroutineScope, lang: String) {
        if (loading) return
        loading = true; status = ""
        scope.launch {
            val result = runCatching { cloud.discoverModels(endpoint.url, endpoint.key, endpoint.noAuth, endpoint.http) }
            if (active) {
                result.onSuccess { models = it; status = t(lang, "已读取模型列表", "モデル一覧を取得", "Model list loaded") }
                    .onFailure { status = t(lang, "列表不可用，可手动输入模型。", "一覧が利用不可。手動入力できます。", "List unavailable; enter a model manually.") + " " + it.message.orEmpty() }
                loading = false
            }
        }
    }
    fun close() { active = false; cloud.cancel() }
}
private class ModelCatalog(val context: android.content.Context) {
    private val entries = mutableMapOf<Pair<String, String>, ModelEntry>()
    fun entry(category: String, id: String, url: String, key: String, noAuth: Boolean, http: Boolean): ModelEntry {
        val endpoint = ModelEndpoint(category, id, url, key, noAuth, http)
        val cacheKey = category to id
        val existing = entries[cacheKey]
        if (existing?.endpoint == endpoint) return existing
        existing?.close()
        return ModelEntry(endpoint, CloudClient(context)).also { entries[cacheKey] = it }
    }
    fun close() = entries.values.forEach { it.close() }
}

@Composable
private fun ModelSelector(label: String, model: String, seeds: List<String>, entry: ModelEntry, scope: CoroutineScope, lang: String, onChange: (String) -> Unit) {
    var manual by remember(entry.endpoint) { mutableStateOf(false) }
    // Retain custom IDs, but reveal the keyboard only when the user requests manual input.
    val ids = (listOf(model) + seeds + entry.models).filter(String::isNotBlank).distinct()
    Selection(label, if (manual) "__manual__" else model,
        ids.map { Option(it, modelName(it)) } + Option("__manual__", t(lang, "手动输入模型 ID", "モデル ID を手動入力", "Enter model ID manually"))) { id ->
        manual = id == "__manual__"
        if (!manual) onChange(id)
    }
    if (manual) OutlinedTextField(model, onChange, Modifier.fillMaxWidth(), label = { Text(t(lang, "模型 ID", "モデル ID", "Model ID")) }, singleLine = true)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { entry.load(scope, lang) }, enabled = !entry.loading) { Text(t(lang, "读取模型列表", "モデル一覧を取得", "Fetch models")) }
        if (entry.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    }
    if (entry.status.isNotBlank()) SelectionContainer { Note(entry.status) }
}

@Composable
private fun ServiceDialog(initial: ServiceEditor, settings: AppSettings, catalog: ModelCatalog, scope: CoroutineScope, lang: String,
    dismiss: () -> Unit, onDelete: (String, String) -> Unit, onApply: (ServiceEditor) -> Unit) {
    var asr by remember(initial) { mutableStateOf(initial.asr) }
    var text by remember(initial) { mutableStateOf(initial.text) }
    val id = asr?.id ?: text!!.id
    val existing = if (asr != null) settings.asrProfiles.any { it.id == id } else settings.textProfiles.any { it.id == id }
    AlertDialog(onDismissRequest = dismiss, title = { Text(t(lang, "服务选项", "サービスの詳細", "Service options")) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(asr?.label ?: text!!.label, { label -> if (asr != null) asr = asr!!.copy(label = label) else text = text!!.copy(label = label) }, Modifier.fillMaxWidth(), label = { Text(t(lang, "显示名称（可选）", "表示名（任意）", "Display name (optional)")) }, singleLine = true)
            if (asr != null) Selection(t(lang, "接口协议", "方式", "Protocol"), asr!!.kind,
                listOf(Option("openai", "OpenAI Audio"), Option("mimo", "MiMo ASR"), Option("deepgram", "Deepgram"))) { kind ->
                asr = asr!!.copy(kind = kind, languages = if (kind == "mimo") setOf("zh", "en") else ProviderDefaults.languages.toSet())
            }
            val url = asr?.baseUrl ?: text!!.baseUrl
            OutlinedTextField(url, { next -> if (asr != null) asr = asr!!.copy(baseUrl = next) else text = text!!.copy(baseUrl = next) }, Modifier.fillMaxWidth(), label = { Text("Base URL") }, singleLine = true)
            Note(t(lang, "云服务通常保留预设地址；本地服务填写手机能访问的地址。", "クラウドはプリセット URL を使用。ローカルは端末から届く URL を入力。", "Keep cloud presets; local services need an address reachable from this phone."))
            val noAuth = asr?.noAuth ?: text!!.noAuth
            SwitchRow(t(lang, "无需 API Key", "API Key 不要", "No API key"), noAuth) { next -> if (asr != null) asr = asr!!.copy(noAuth = next) else text = text!!.copy(noAuth = next) }
            if (asr != null) {
                val profile = asr!!
                ModelSelector(t(lang, "识别模型", "認識モデル", "ASR model"), profile.model, ProviderDefaults.asrModels(profile),
                    catalog.entry("asr", id, profile.baseUrl, profile.apiKey, profile.noAuth, settings.allowInsecureHttp), scope, lang) { asr = asr!!.copy(model = it) }
                Fold(t(lang, "支持语言", "対応言語", "Supported languages"), profile.languages.joinToString { language(it, lang) }) {
                    ProviderDefaults.languages.forEach { code ->
                        if (profile.kind != "mimo" || code in setOf("zh", "en")) SwitchRow(language(code, lang), code in profile.languages) {
                            asr = asr!!.copy(languages = if (it) asr!!.languages + code else asr!!.languages - code)
                        }
                    }
                }
            }
            if (existing) TextButton(onClick = { onDelete(if (asr != null) "asr" else "text", id) }) { Text(t(lang, "删除此服务", "サービスを削除", "Remove this service"), color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { onApply(ServiceEditor(asr, text)) }, enabled = (asr?.baseUrl ?: text!!.baseUrl).isNotBlank() && (asr == null || asr!!.model.isNotBlank() && asr!!.languages.isNotEmpty())) { Text(t(lang, "应用", "適用", "Apply")) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(t(lang, "取消", "キャンセル", "Cancel")) } })
}

@Composable private fun KeyDialog(name: String, original: String, lang: String, dismiss: () -> Unit, onApply: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text(name + " API Key") }, text = {
        Column {
            Note(if (original.isBlank()) t(lang, "应用密钥会修改草稿并启用服务；点击读取模型列表才会连接服务。", "キーは下書きに適用。モデル一覧の取得でサービスに接続します。", "Applying enables this provider in the draft. Fetch models contacts the service.")
                else t(lang, "已有密钥。输入新密钥可替换，或清除本机密钥。", "キーを設定済み。置換または削除できます。", "A key is saved. Enter a replacement or clear the local key."))
            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("API Key") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            if (original.isNotBlank()) TextButton(onClick = { onApply("") }) { Text(t(lang, "清除密钥", "キーを削除", "Clear key")) }
        }
    }, confirmButton = { TextButton(onClick = { onApply(key.trim()) }, enabled = key.isNotBlank()) { Text(t(lang, "应用并启用", "適用して有効化", "Apply and enable")) } }, dismissButton = { TextButton(onClick = dismiss) { Text(t(lang, "取消", "キャンセル", "Cancel")) } })
}

private data class Option(val id: String, val label: String, val enabled: Boolean = true)
@Composable private fun Selection(label: String, selected: String, options: List<Option>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(options.firstOrNull { it.id == selected }?.label ?: selected.ifBlank { "—" }, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Rounded.ArrowDropDown, null)
            }
            DropdownMenu(expanded, { expanded = false }) { options.forEach { option ->
                DropdownMenuItem(text = { Text(option.label) }, enabled = option.enabled, onClick = { onSelect(option.id); expanded = false })
            } }
        }
    }
}
@Composable private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary); content()
        }
    }
}
@Composable private fun Fold(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
        }
        if (expanded) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
    }
}
@Composable private fun ServiceStatus(enabled: Boolean, key: String, noAuth: Boolean, lang: String) {
    val status = when {
        !enabled -> t(lang, "未启用", "無効", "Disabled")
        noAuth -> t(lang, "无需密钥", "キー不要", "No key required")
        key.isBlank() -> t(lang, "需要配置密钥", "キーが必要", "Key required")
        else -> t(lang, "已启用 · 密钥已配置", "有効・キー設定済み", "Enabled · key configured")
    }
    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable private fun ProviderAddress(url: String) { SelectionContainer { Note(url) } }
@Composable private fun KeyActions(category: String, id: String, key: String, noAuth: Boolean, lang: String, onKey: (String, String) -> Unit) {
    if (!noAuth) OutlinedButton(onClick = { onKey(category, id) }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Rounded.Key, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (key.isBlank()) t(lang, "配置密钥", "キーを設定", "Configure key") else t(lang, "管理密钥", "キーを管理", "Manage key"))
    }
}
@Composable private fun ProviderRow(name: String, subtitle: String, enabled: Boolean, key: String, noAuth: Boolean, lang: String, onEnabled: (Boolean) -> Unit, onKey: () -> Unit, onOptions: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(name, fontWeight = FontWeight.Bold); Text(subtitle, maxLines = 2, style = MaterialTheme.typography.bodySmall); ServiceStatus(enabled, key, noAuth, lang) }
            Switch(enabled, onEnabled)
        }
        Row {
            if (!noAuth) TextButton(onClick = onKey) { Text(if (key.isBlank()) t(lang, "配置密钥", "キーを設定", "Configure key") else t(lang, "管理密钥", "キーを管理", "Manage key")) }
            TextButton(onClick = onOptions) { Text(t(lang, "服务选项", "サービスの詳細", "Service options")) }
        }
    }
}
@Composable private fun SwitchRow(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(value, change) }
}
@Composable private fun Stepper(label: String, value: Int, range: IntRange, step: Int, onChange: (Int) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange((value - step).coerceIn(range)) }, enabled = value > range.first) { Icon(Icons.Rounded.Remove, t("", "减少", "減らす", "Decrease")) }
            Text(value.toString(), Modifier.weight(1f), fontWeight = FontWeight.Bold)
            IconButton(onClick = { onChange((value + step).coerceIn(range)) }, enabled = value < range.last) { Icon(Icons.Rounded.Add, t("", "增加", "増やす", "Increase")) }
        }
    }
}
@Composable private fun DecimalField(label: String, value: Double, onChange: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(text, { text = it; it.toDoubleOrNull()?.let(onChange) }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true)
}
@Composable private fun Note(text: String) { Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
private fun asrName(profile: AsrProfile, lang: String) = profile.label.ifBlank { when (profile.id) {
    "groq" -> "Groq · Whisper"; "mimo" -> "Xiaomi MiMo · ASR"; "deepgram" -> "Deepgram · Nova"; "local" -> t(lang, "本地语音服务", "ローカル音声サービス", "Local ASR")
    else -> t(lang, "自定义语音服务", "カスタム音声サービス", "Custom ASR")
} }
private fun textName(profile: TextProfile?, lang: String): String = profile?.label?.ifBlank { when (profile.id) {
    "deepseek" -> "DeepSeek"; "mimo" -> "Xiaomi MiMo"; "local" -> t(lang, "本地文本服务", "ローカルテキスト", "Local text service")
    else -> t(lang, "自定义文本服务", "カスタムテキスト", "Custom text service")
} } ?: t(lang, "未选择服务", "サービス未選択", "No provider selected")
private fun taskName(task: String, lang: String) = when (task) {
    "summary" -> t(lang, "摘要", "要約", "Summary"); "minutes" -> t(lang, "纪要", "議事録", "Minutes")
    "translation" -> t(lang, "翻译", "翻訳", "Translation"); "ask" -> t(lang, "问答", "質問", "Q&A")
    else -> t(lang, "说话人区分", "話者区分", "Speaker labels")
}
private fun modelName(id: String) = when (id) { "deepseek-flash" -> "DeepSeek Flash · $id"; "deepseek-v4-pro" -> "DeepSeek V4 Pro · $id"; else -> id }
private fun language(code: String, lang: String = "zh") = when (code) {
    "zh" -> "中文"; "ja" -> "日本語"; "en" -> "English"; "ko" -> "한국어"; "fr" -> "Français"; "de" -> "Deutsch"; "es" -> "Español"
    "auto" -> t(lang, "自动检测", "自動検出", "Auto detect")
    else -> code
}
private fun t(lang: String, zh: String, ja: String, en: String) = when (lang) { "ja" -> ja; "en" -> en; else -> zh }
