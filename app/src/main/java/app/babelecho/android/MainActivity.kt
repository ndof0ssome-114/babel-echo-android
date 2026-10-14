package app.babelecho.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.babelecho.android.data.TextRole
import app.babelecho.android.data.AsrProfile
import app.babelecho.android.data.TextProfile
import app.babelecho.android.data.ExportFormat
import app.babelecho.android.data.MeetingExport
import app.babelecho.android.data.AppSettings
import app.babelecho.android.data.Meeting
import app.babelecho.android.data.TranscriptSegment
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BabelEchoApp(viewModel) }
    }
}

private enum class AppPage { Workspace, History, Settings }
private enum class MeetingPanel { Transcript, Summary, Minutes, Ask, Stats }

private val Indigo = Color(0xFF4C63D9)
private val IndigoDark = Color(0xFF3149BE)
private val Cyan = Color(0xFF39AEB5)
private val Ink: Color @Composable get() = MaterialTheme.colorScheme.onSurface
private val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val Glass: Color @Composable get() = MaterialTheme.colorScheme.surface
private val Soft: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainer
private val Danger = Color(0xFFCF5160)

@Composable
private fun BabelEchoApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lang = state.settings.uiLanguage
    val snackbar = remember { SnackbarHostState() }
    var page by rememberSaveable { mutableStateOf(AppPage.Workspace) }
    var microphone by rememberSaveable { mutableStateOf(true) }
    var playback by rememberSaveable { mutableStateOf(false) }
    var pendingMicrophone by rememberSaveable { mutableStateOf(true) }
    var pendingPlayback by rememberSaveable { mutableStateOf(true) }

    var consent by rememberSaveable { mutableStateOf(false) }
    var importDialog by rememberSaveable { mutableStateOf(false) }
    var importAsr by rememberSaveable { mutableStateOf(false) }
    var importMinutes by rememberSaveable { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            page = AppPage.Workspace
            viewModel.importMedia(it, importAsr, importMinutes)
        }
    }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.startRecording(result.resultCode, result.data, pendingMicrophone, pendingPlayback)
        } else viewModel.showMessage(tr(lang, "系统声音授权已取消", "システム音声の許可がキャンセルされました", "System audio consent was cancelled"))
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val audioAllowed = grants[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (audioAllowed) {
            if (pendingPlayback) projectionLauncher.launch(context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
            else viewModel.startRecording(Activity.RESULT_OK, null, pendingMicrophone, false)
        } else viewModel.showMessage(tr(lang, "录音需要音频权限，请在系统设置中允许。", "録音の音声権限を許可してください。", "Audio permission is required; allow it in system settings."))
    }

    fun startRecording() {
        pendingMicrophone = microphone
        pendingPlayback = playback
        val permissions = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        when {
            permissions.isNotEmpty() -> permissionLauncher.launch(permissions.toTypedArray())
            playback -> projectionLauncher.launch(context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
            else -> viewModel.startRecording(Activity.RESULT_OK, null, microphone, false)
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }

    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(primary = Color(0xFFAFBFFF), secondary = Color(0xFF75D5D7),
        surface = Color(0xFF202B40), onSurface = Color(0xFFE9EEFF), onSurfaceVariant = Color(0xFFBCC8DF),
        background = Color(0xFF101827), surfaceContainer = Color(0xFF29364B))
        else lightColorScheme(primary = Indigo, secondary = Cyan, surface = Color(0xD9FFFFFF), onSurface = Color(0xFF18243D),
            onSurfaceVariant = Color(0xFF67758D), background = Color(0xFFF1F4FD))
    MaterialTheme(colorScheme = colors) {
        Box(Modifier.fillMaxSize().background(if (dark) Brush.linearGradient(listOf(Color(0xFF101827), Color(0xFF182236))) else Brush.linearGradient(listOf(Color(0xFFE7ECFC), Color(0xFFF8F5FF), Color(0xFFE7F6F7))))) {
            Scaffold(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, modifier = Modifier.navigationBarsPadding()) {
                        NavItem(page, AppPage.Workspace, Icons.Rounded.Home, tr(lang, "工作台", "ワークスペース", "Workspace")) { page = it }
                        NavItem(page, AppPage.History, Icons.Rounded.History, tr(lang, "历史", "履歴", "History")) { page = it }
                        NavItem(page, AppPage.Settings, Icons.Rounded.Settings, tr(lang, "设置", "設定", "Settings")) { page = it }
                    }
                },
            ) { padding ->
                val selected = state.meetings.firstOrNull { it.id == (state.activeMeetingId ?: state.selectedMeetingId) }
                when (page) {
                    AppPage.Workspace -> WorkspaceScreen(
                        Modifier.padding(padding), state, selected, microphone, playback,
                        onMicrophone = { microphone = it }, onPlayback = { playback = it },
                        onStart = { consent = true }, onPause = viewModel::togglePause, onStop = viewModel::stopRecording,
                        onOpenHistory = { page = AppPage.History }, onOpenSettings = { page = AppPage.Settings },
                        onRename = { title -> selected?.let { viewModel.renameMeeting(it, title) } },
                        onTranscribe = { selected?.let(viewModel::transcribe) }, onSummarize = { selected?.let(viewModel::summarize) },
                        onMinutes = { selected?.let(viewModel::createMinutes) }, onTranslate = { selected?.let(viewModel::translate) },
                        onAsk = { question -> selected?.let { viewModel.ask(it, question) } }, onShare = { selected?.let { shareMeeting(context, it) } },
                        onImport = { importAsr = false; importMinutes = false; importDialog = true },
                        onRenameSpeaker = { original, name -> selected?.let { viewModel.renameSpeaker(it, original, name) } },
                        onResplitSpeakers = { selected?.let(viewModel::resplitSpeakers) },
                        onExport = { meeting, uri, format -> viewModel.exportMeeting(meeting, uri, format) }, onError = viewModel::showMessage,
                    )
                    AppPage.History -> HistoryScreen(Modifier.padding(padding), state, onOpen = { viewModel.selectMeeting(it.id); page = AppPage.Workspace }, onDelete = viewModel::deleteMeeting)
                    AppPage.Settings -> SettingsScreen(Modifier.padding(padding), state.settings, viewModel::saveSettings, state.isRecording || state.isStarting || state.isStopping || state.workingMeetingId != null)
                }
            }
        }
        if (consent) AlertDialog(onDismissRequest = { consent = false },
            title = { Text(tr(lang, "录音前确认", "録音前の確認", "Before recording")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr(lang, "请确认已取得参与者同意。录音先保存到本机。", "参加者の同意を確認してください。録音は端末に保存されます。", "Confirm participant consent. Audio is saved on this device."))
                Text(if (state.settings.liveTranscription) tr(lang, "已开启实时转写，音频会发送到：", "音声を送信する文字起こし先：", "Live transcription sends audio to: ") + state.settings.asrDestination else tr(lang, "实时转写已关闭，本次录音不上传音频。", "リアルタイム文字起こしは無効です。", "Live transcription is off. This recording does not upload audio."))
                if (state.settings.liveTranscription && !state.settings.hasAsr) Text(tr(lang, "尚未配置识别服务，将只保存本地录音。", "音声認識未設定のため録音のみ保存します。", "ASR is not configured; only local audio will be saved."))
                if (state.settings.autoMinutes && state.settings.hasLlm) Text(tr(lang, "有转写时，结束后会将文本发送给以下服务生成纪要：", "文字起こしがある場合、終了後に議事録を作成：", "If a transcript exists, stopping sends text for minutes to: ") + state.settings.minutesDestination)
                if (state.settings.liveTranscription && (state.settings.autoSummarySeconds > 0 || state.settings.translateTo.isNotBlank())) Text(tr(lang,
                    "启用的摘要和翻译会把转写文字发送至你配置的文本服务：", "有効な要約と翻訳は文字起こしを設定済みのテキストサービスに送信します：", "Enabled summaries and translation send transcript text to your configured text services: ") +
                    (if (state.settings.useTextProfiles) state.settings.textProfiles.filter { it.enabled }.joinToString("\n") { it.baseUrl } else state.settings.llmBaseUrl))
                if (playback) Text(tr(lang, "系统声音仅支持允许捕获的媒体；通话和受保护内容可能无声。", "通話や保護された音声は取得できない場合があります。", "System audio depends on the source app; calls and protected content may be silent."))
            } },
            confirmButton = { TextButton(onClick = { consent = false; startRecording() }) { Text(tr(lang, "已取得同意，开始", "同意を確認して開始", "Consent obtained · Start")) } },
            dismissButton = { TextButton(onClick = { consent = false }) { Text(tr(lang, "取消", "キャンセル", "Cancel")) } })
        if (importDialog) AlertDialog(onDismissRequest = { importDialog = false },
            title = { Text(tr(lang, "导入音频或视频", "音声・動画をインポート", "Import audio or video")) },
            text = { Column {
                Text(tr(lang, "在本机转换并保存音轨，原文件保持完整。开启以下选项才会发送给模型服务。", "音声を端末で変換します。以下を有効にするとモデルに送信します。", "Decode and save audio locally. Enable processing below to send it to your configured services."))
                SettingsSwitch(tr(lang, "导入后转写", "インポート後に文字起こし", "Transcribe after import"), importAsr) { importAsr = it; if (!it) importMinutes = false }
                if (importAsr) {
                    Text(state.settings.asrDestination, style = MaterialTheme.typography.bodySmall)
                    SettingsSwitch(tr(lang, "生成纪要", "議事録を作成", "Create minutes"), importMinutes) { importMinutes = it }
                    if (importMinutes) Text(state.settings.minutesDestination, style = MaterialTheme.typography.bodySmall)
                }
            } },
            confirmButton = { TextButton(onClick = { importDialog = false; importLauncher.launch(arrayOf("audio/*", "video/*")) },
                enabled = (!importAsr || state.settings.hasAsr) && (!importMinutes || state.settings.hasLlm)) { Text(tr(lang, "选择文件", "ファイルを選択", "Choose file")) } },
            dismissButton = { TextButton(onClick = { importDialog = false }) { Text(tr(lang, "取消", "キャンセル", "Cancel")) } })
    }
}

@Composable
private fun RowScope.NavItem(current: AppPage, target: AppPage, icon: ImageVector, label: String, onChange: (AppPage) -> Unit) {
    NavigationBarItem(selected = current == target, onClick = { onChange(target) }, icon = { Icon(icon, null) }, label = { Text(label) })
}

@Composable
private fun WorkspaceScreen(
    modifier: Modifier,
    state: MainUiState,
    meeting: Meeting?,
    microphone: Boolean,
    playback: Boolean,
    onMicrophone: (Boolean) -> Unit,
    onPlayback: (Boolean) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onRename: (String) -> Unit,
    onTranscribe: () -> Unit,
    onSummarize: () -> Unit,
    onMinutes: () -> Unit,
    onTranslate: () -> Unit,
    onAsk: (String) -> Unit,
    onShare: () -> Unit,
    onImport: () -> Unit,
    onRenameSpeaker: (String, String) -> Unit,
    onResplitSpeakers: () -> Unit,
    onExport: (Meeting, android.net.Uri, ExportFormat) -> Unit,
    onError: (String) -> Unit,
) {
    val lang = state.settings.uiLanguage
    var panel by remember { mutableStateOf(MeetingPanel.Transcript) }
    var rename by remember { mutableStateOf(false) }
    var titleDraft by remember(meeting?.id, meeting?.title) { mutableStateOf(meeting?.title.orEmpty()) }
    var seekMs by remember(meeting?.id) { mutableStateOf<Long?>(null) }
    var seekRevision by remember(meeting?.id) { mutableIntStateOf(0) }
    var speaker by remember(meeting?.id) { mutableStateOf<String?>(null) }
    var speakerDraft by remember { mutableStateOf("") }
    var exporting by remember { mutableStateOf(false) }
    var exportFormat by rememberSaveable { mutableStateOf(ExportFormat.Markdown) }
    var exportPending by rememberSaveable { mutableStateOf(false) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(exportFormat.mime)) { uri ->
        if (uri != null) state.meetings.firstOrNull { it.id == exportId }?.let { onExport(it, uri, exportFormat) }
    }
    val busy = state.workingMeetingId != null || state.isRecording || state.isStarting || state.isStopping
    val elapsed = if (state.isRecording) state.recordedDurationMs else meeting?.durationMs ?: 0

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            BrandHeader(
                lang, meeting?.title ?: tr(lang, "未命名会议", "無題の会議", "Untitled meeting"), elapsed,
                when { state.isStarting -> tr(lang, "启动中", "開始中", "Starting"); state.isStopping -> tr(lang, "保存中", "保存中", "Saving"); state.isPaused -> tr(lang, "已暂停", "一時停止", "Paused"); state.isRecording -> tr(lang, "录音中", "録音中", "Recording"); else -> tr(lang, "待机", "待機", "Ready") },
                state.settings.asrModel, { rename = true }, onOpenHistory, onOpenSettings,
            )
        }
        item {
            CaptureCard(lang, state.isRecording, state.isPaused, microphone, playback, state.audioLevel, state.settings.asrLanguage, state.settings.translateTo, onMicrophone, onPlayback, onStart, onPause, onStop, busy && !state.isRecording || state.isStopping, state.settings.liveTranscription && state.settings.hasAsr)
            if (!state.isRecording) TextButton(onClick = onImport, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.UploadFile, null); Spacer(Modifier.width(6.dp)); Text(tr(lang, "导入音频 / 视频", "音声・動画をインポート", "Import audio / video"))
            }
        }
        if (meeting != null && !state.isRecording && !state.isStarting && !state.isStopping) item { PlaybackCard(meeting, seekMs, seekRevision, onError) }
        if (state.workingMeetingId != null) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(tr(lang, "正在处理，录音已保存在本机", "処理中。音声は端末に保存されています", "Processing · audio is saved locally"), color = Muted) }
        item { MeetingTabs(lang, panel) { panel = it } }
        item {
            GlassCard {
                when (panel) {
                    MeetingPanel.Transcript -> TranscriptPanel(lang, meeting, state.workingMeetingId != null, onTranscribe, onTranslate, { seekMs = it; seekRevision++ }, { speaker = it; speakerDraft = it }, onResplitSpeakers, busy, showSegments = meeting?.segments.isNullOrEmpty())
                    MeetingPanel.Summary -> TextPanel(lang, tr(lang, "实时摘要", "リアルタイム要約", "Live summary"), meeting?.summary.orEmpty(), tr(lang, "录音中按设置的间隔增量更新，也可以手动生成。", "録音中に増分更新できます。", "Updated incrementally while recording, or generated manually."), tr(lang, "重新总结", "再要約", "Summarize"), onSummarize, busy, !meeting?.transcript.isNullOrBlank())
                    MeetingPanel.Minutes -> TextPanel(lang, tr(lang, "智能纪要", "議事録", "Meeting minutes"), meeting?.minutes.orEmpty(), tr(lang, "结构化整理结论、待办、风险和章节。", "決定・タスク・リスクを構造化します。", "Structured decisions, action items, risks, and chapters."), tr(lang, "生成纪要", "議事録を生成", "Create minutes"), onMinutes, busy, !meeting?.transcript.isNullOrBlank())
                    MeetingPanel.Ask -> AskPanel(lang, meeting, busy || meeting?.transcript.isNullOrBlank(), onAsk)
                    MeetingPanel.Stats -> StatsPanel(lang, meeting, onShare, { exporting = true }, busy, state.settings)
                }
            }
        }
        if (panel == MeetingPanel.Transcript && meeting != null) items(meeting.segments, key = { it.id }) { segment ->
            GlassCard { TranscriptRow(segment, { seekMs = it; seekRevision++ }, { speaker = it; speakerDraft = it }, !busy) }
        }
    }

    if (exporting && meeting != null) AlertDialog(onDismissRequest = { exporting = false },
        title = { Text(tr(lang, "导出会议文件", "会議ファイルを保存", "Export meeting file")) },
        text = { Column { ExportFormat.entries.forEach { format ->
            TextButton(onClick = { exportFormat = format; exportId = meeting.id; exportPending = true; exporting = false }, modifier = Modifier.fillMaxWidth()) { Text(format.label) }
        } } }, confirmButton = {}, dismissButton = { TextButton(onClick = { exporting = false }) { Text(tr(lang, "取消", "キャンセル", "Cancel")) } })
    // Wait for the format's document contract to recompose before launching the picker.
    LaunchedEffect(exportPending, exporting, exportFormat) {
        if (exportPending && !exporting && exportId != null && meeting != null && meeting.id == exportId) {
            exportPending = false
            exportLauncher.launch(MeetingExport.filename(meeting, exportFormat))
        }
    }
    if (speaker != null) AlertDialog(onDismissRequest = { speaker = null },
        title = { Text(tr(lang, "修改说话人名称", "話者名を変更", "Rename speaker")) },
        text = { OutlinedTextField(speakerDraft, { speakerDraft = it }, singleLine = true) },
        confirmButton = { TextButton(enabled = speakerDraft.isNotBlank(), onClick = { onRenameSpeaker(speaker!!, speakerDraft); speaker = null }) { Text(tr(lang, "保存", "保存", "Save")) } },
        dismissButton = { TextButton(onClick = { speaker = null }) { Text(tr(lang, "取消", "キャンセル", "Cancel")) } })
    if (rename) AlertDialog(
        onDismissRequest = { rename = false },
        title = { Text(tr(lang, "修改会议标题", "会議名を変更", "Rename meeting")) },
        text = { OutlinedTextField(titleDraft, { titleDraft = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onRename(titleDraft); rename = false }) { Text(tr(lang, "保存", "保存", "Save")) } },
        dismissButton = { TextButton(onClick = { rename = false }) { Text(tr(lang, "取消", "キャンセル", "Cancel")) } },
    )
}

@Composable
private fun BrandHeader(lang: String, title: String, elapsed: Long, state: String, model: String, onTitle: () -> Unit, onHistory: () -> Unit, onSettings: () -> Unit) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(IndigoDark, Indigo))), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.GraphicEq, null, tint = Color.White) }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("巴别回声", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(tr(lang, "AI 会议记录工作台", "AI 会議ワークスペース", "AI meeting workspace"), style = MaterialTheme.typography.labelSmall, color = Muted)
            }
            IconButton(onClick = onHistory) { Icon(Icons.Rounded.History, null) }
            IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, null) }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Ink.copy(alpha = .08f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).clickable(onClick = onTitle), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            StatusChip(formatDuration(elapsed), Indigo.copy(alpha = .09f), Ink); Spacer(Modifier.width(6.dp))
            val active = state == "Recording" || state.contains("录") || state.contains("録")
            StatusChip(state, if (active) Color(0x1ACF5160) else Color(0x144C63D9), if (active) Danger else MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(9.dp))
        Row { Text(model, style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)); Text(tr(lang, "点击标题可修改", "タイトルをタップして編集", "Tap title to edit"), style = MaterialTheme.typography.labelSmall, color = Muted) }
    }
}

@Composable
private fun CaptureCard(lang: String, recording: Boolean, paused: Boolean, microphone: Boolean, playback: Boolean, level: Float, speechLanguage: String, translateTo: String, onMicrophone: (Boolean) -> Unit, onPlayback: (Boolean) -> Unit, onStart: () -> Unit, onPause: () -> Unit, onStop: () -> Unit, busy: Boolean, live: Boolean) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr(lang, "录音控制", "録音コントロール", "Capture controls"), fontWeight = FontWeight.Bold)
                Text(if (recording) (if (live) tr(lang, "实时转写 · 本地录音", "文字起こし・端末録音", "Live transcription · local recording") else tr(lang, "仅保存本地录音", "端末録音のみ", "Local recording only")) else tr(lang, "选择声音来源后开始会议", "音声ソースを選択してください", "Choose audio sources and start"), style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            StatusChip(speechLanguage.uppercase(), Color(0x114C63D9), MaterialTheme.colorScheme.primary)
            if (translateTo.isNotBlank()) { Spacer(Modifier.width(5.dp)); StatusChip("→ ${translateTo.uppercase()}", Color(0x1139AEB5), Cyan) }
        }
        Spacer(Modifier.height(13.dp)); AudioMeter(level, recording && !paused); Spacer(Modifier.height(13.dp))
        SourceRow(Icons.Rounded.Mic, tr(lang, "麦克风", "マイク", "Microphone"), tr(lang, "你的发言与环境声音", "自分と周囲の音声", "You and nearby voices"), microphone, !recording && !busy, onMicrophone)
        HorizontalDivider(Modifier.padding(vertical = 7.dp), color = Ink.copy(alpha = .07f))
        SourceRow(Icons.Rounded.DesktopWindows, tr(lang, "系统声音", "システム音声", "System audio"), tr(lang, "允许捕获的媒体和游戏声音", "許可されたメディア音声", "Capturable media and games"), playback, !recording && !busy, onPlayback)
        Spacer(Modifier.height(14.dp))
        if (!recording) Button(onClick = onStart, enabled = !busy && (microphone || playback), modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Indigo, contentColor = Color.White)) {
            Icon(Icons.Rounded.Mic, null); Spacer(Modifier.width(7.dp)); Text(tr(lang, "开始录音", "録音を開始", "Start recording"), fontWeight = FontWeight.Bold)
        } else Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Button(onClick = onPause, enabled = !busy, modifier = Modifier.weight(.42f).height(50.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Soft, contentColor = Ink)) {
                Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null); Spacer(Modifier.width(5.dp)); Text(if (paused) tr(lang, "继续", "再開", "Resume") else tr(lang, "暂停", "一時停止", "Pause"))
            }
            Button(onClick = onStop, enabled = !busy, modifier = Modifier.weight(.58f).height(50.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Color.White)) {
                Icon(Icons.Rounded.StopCircle, null); Spacer(Modifier.width(5.dp)); Text(tr(lang, "结束录音", "録音を終了", "Stop recording"))
            }
        }
    }
}

@Composable
private fun AudioMeter(level: Float, active: Boolean) {
    Canvas(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x0C4C63D9))) {
        val count = 46; val gap = size.width / count
        repeat(count) { index ->
            val phase = index.toFloat() / count * 6.28f
            val motion = if (active) .25f + level * 6f else .08f
            val bar = size.height * (.12f + kotlin.math.abs(sin(phase * 2.4f)) * motion).coerceAtMost(.86f)
            drawLine(if (active) Indigo.copy(alpha = .72f) else Indigo.copy(alpha = .18f), Offset(index * gap + gap / 2, size.height / 2 - bar / 2), Offset(index * gap + gap / 2, size.height / 2 + bar / 2), 2.5.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
private fun SourceRow(icon: ImageVector, title: String, detail: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(39.dp).clip(CircleShape).background(Indigo.copy(alpha = .09f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp)) }
        Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(detail, style = MaterialTheme.typography.bodySmall, color = Muted) }; Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
private fun MeetingTabs(lang: String, current: MeetingPanel, onSelect: (MeetingPanel) -> Unit) {
    val tabs = listOf(MeetingPanel.Transcript to tr(lang, "转写", "文字起こし", "Transcript"), MeetingPanel.Summary to tr(lang, "摘要", "要約", "Summary"), MeetingPanel.Minutes to tr(lang, "纪要", "議事録", "Minutes"), MeetingPanel.Ask to tr(lang, "问答", "質問", "Ask"), MeetingPanel.Stats to tr(lang, "统计", "統計", "Stats"))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tabs.forEach { (tab, text) -> FilterChip(selected = current == tab, onClick = { onSelect(tab) }, label = { Text(text, maxLines = 1) }) }
    }
}

@Composable
private fun TranscriptPanel(lang: String, meeting: Meeting?, working: Boolean, onTranscribe: () -> Unit, onTranslate: () -> Unit, onSeek: (Long) -> Unit, onSpeaker: (String) -> Unit, onResplit: () -> Unit, blocked: Boolean, showSegments: Boolean) {
    PanelHeader(tr(lang, "实时转写", "リアルタイム文字起こし", "Live transcript"), "${meeting?.segments?.size ?: 0} ${tr(lang, "条", "件", "segments")}")
    if (meeting == null || (meeting.segments.isEmpty() && meeting.transcript.isBlank())) EmptyState(Icons.Rounded.GraphicEq, tr(lang, "还没有转写内容", "文字起こしはまだありません", "No transcript yet"), tr(lang, "可在设置中开启实时转写，或录完后手动转写。", "設定で文字起こしを有効にするか、録音後に実行してください。", "Enable live transcription in Settings, or transcribe after recording."))
    else if (showSegments) meeting.segments.ifEmpty { listOf(TranscriptSegment(0, 0, meeting.durationMs, text = meeting.transcript)) }.forEach { TranscriptRow(it, onSeek, onSpeaker, !blocked) }
    if (!meeting?.segments.isNullOrEmpty()) TextButton(onClick = onResplit, enabled = !blocked) { Text(tr(lang, "AI 调整说话人（根据文本推测）", "AI 話者ラベル（テキストから推定）", "AI speaker labels (inferred from text)")) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onTranslate, enabled = !blocked && meeting != null) { Icon(Icons.Rounded.Language, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text(tr(lang, "翻译", "翻訳", "Translate")) }
        TextButton(onClick = onTranscribe, enabled = !blocked && meeting != null) { Icon(Icons.Rounded.Refresh, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text(tr(lang, "重新转写", "再文字起こし", "Retranscribe")) }
        if (working) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun TranscriptRow(segment: TranscriptSegment, onSeek: (Long) -> Unit, onSpeaker: (String) -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(formatDuration(segment.startMs), style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.width(65.dp).clickable(enabled = enabled) { onSeek(segment.startMs) })
        Column(Modifier.weight(1f)) {
            Text(segment.speaker, modifier = Modifier.clickable(enabled = enabled) { onSpeaker(segment.speaker) }, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            SelectionContainer { Text(segment.text, lineHeight = MaterialTheme.typography.bodyLarge.lineHeight) }
            if (segment.translated.isNotBlank()) { Spacer(Modifier.height(5.dp)); Text(segment.translated, color = Muted, style = MaterialTheme.typography.bodyMedium) }
        }
    }; HorizontalDivider(color = Ink.copy(alpha = .055f))
}

@Composable
private fun TextPanel(lang: String, title: String, content: String, empty: String, action: String, onAction: () -> Unit, working: Boolean, available: Boolean) {
    PanelHeader(title, if (content.isBlank()) tr(lang, "尚未生成", "未生成", "Not generated") else tr(lang, "已更新", "更新済み", "Updated"))
    if (content.isBlank()) EmptyState(Icons.Rounded.AutoAwesome, title, empty) else SelectionContainer { Text(content, lineHeight = MaterialTheme.typography.bodyLarge.lineHeight) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onAction, enabled = !working && available) { if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text(action) } }
}

@Composable
private fun AskPanel(lang: String, meeting: Meeting?, working: Boolean, onAsk: (String) -> Unit) {
    var question by remember(meeting?.id) { mutableStateOf("") }
    PanelHeader(tr(lang, "会议问答", "会議への質問", "Ask this meeting"), "${meeting?.qa?.size ?: 0}")
    meeting?.qa?.forEach { entry -> Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Text("Q  ${entry.question}", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(5.dp)); SelectionContainer { Text(entry.answer) } }; HorizontalDivider(color = Ink.copy(alpha = .06f)) }
    if (meeting?.qa.isNullOrEmpty()) Text(tr(lang, "基于全部转写提问，回答会尽量标注时间。", "文字起こしに基づいて回答します。", "Answers use the transcript and cite time when possible."), color = Muted)
    Spacer(Modifier.height(12.dp)); Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(question, { question = it }, Modifier.weight(1f), placeholder = { Text(tr(lang, "例如：谁负责下一步？", "例：次の担当者は？", "Who owns the next step?")) }, maxLines = 3)
        Spacer(Modifier.width(7.dp)); IconButton(onClick = { if (question.isNotBlank()) { onAsk(question); question = "" } }, enabled = !working && meeting != null) { Icon(Icons.AutoMirrored.Rounded.Send, null, tint = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun StatsPanel(lang: String, meeting: Meeting?, onShare: () -> Unit, onExport: () -> Unit, busy: Boolean, settings: AppSettings) {
    PanelHeader(tr(lang, "会议统计", "会議統計", "Meeting statistics"), meeting?.let { formatDuration(it.durationMs) }.orEmpty())
    val stats = meeting?.stats
    StatRow(tr(lang, "录音时长", "録音時間", "Duration"), formatDuration(meeting?.durationMs ?: 0)); StatRow(tr(lang, "语音请求", "音声リクエスト", "ASR requests"), (stats?.asrCalls ?: 0).toString()); StatRow(tr(lang, "文本模型请求", "LLM リクエスト", "LLM requests"), (stats?.llmCalls ?: 0).toString()); StatRow(tr(lang, "转写字符", "文字数", "Transcript characters"), (stats?.transcriptChars ?: meeting?.transcript?.length ?: 0).toString()); StatRow(tr(lang, "声音来源", "音声ソース", "Sources"), listOfNotNull("MIC".takeIf { meeting?.usedMicrophone == true }, "SYSTEM".takeIf { meeting?.usedPlayback == true }).joinToString(" + "))
    StatRow(tr(lang, "已处理音频秒数", "処理した音声秒数", "Processed audio seconds"), String.format(Locale.US, "%.1f", stats?.asrSeconds ?: 0.0))
    StatRow(tr(lang, "输入 / 输出 token", "入力 / 出力 token", "Input / output tokens"), "${stats?.inputTokens ?: 0} / ${stats?.outputTokens ?: 0}")
    val estimate = (stats?.asrSeconds ?: 0.0) / 3_600 * settings.asrPricePerHour +
        (stats?.inputTokens ?: 0) / 1_000_000.0 * settings.inputPricePerMillion +
        (stats?.outputTokens ?: 0) / 1_000_000.0 * settings.outputPricePerMillion
    StatRow(tr(lang, "按当前费率估算（非账单）", "現在の単価による概算（請求額ではありません）", "Estimate at current rates (not a bill)"), if (settings.asrPricePerHour + settings.inputPricePerMillion + settings.outputPricePerMillion > 0) String.format(Locale.US, "%.4f %s", estimate, settings.priceCurrency) else tr(lang, "请在设置中填写费率", "設定で単価を入力", "Set rates in Settings"))
    Text(tr(lang, "统计仅包含成功请求；旧会议和未返回用量的服务可能低估。", "成功リクエストのみ。古い会議や使用量未対応のサービスは過小計上の場合があります。", "Successful requests only. Older meetings and providers without usage data may be undercounted."), style = MaterialTheme.typography.bodySmall, color = Muted)
    Spacer(Modifier.height(10.dp)); OutlinedButton(onClick = onExport, enabled = meeting != null && !busy, modifier = Modifier.fillMaxWidth()) { Text(tr(lang, "导出文件（MD / TXT / VTT / JSON / WAV）", "ファイル保存（MD / TXT / VTT / JSON / WAV）", "Export file (MD / TXT / VTT / JSON / WAV)")) }
    Spacer(Modifier.height(10.dp)); Button(onClick = onShare, enabled = meeting != null, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Indigo, contentColor = Color.White)) { Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(6.dp)); Text(tr(lang, "分享 Markdown", "Markdown を共有", "Share Markdown")) }
}

@Composable
private fun HistoryScreen(modifier: Modifier, state: MainUiState, onOpen: (Meeting) -> Unit, onDelete: (Meeting) -> Unit) {
    val lang = state.settings.uiLanguage; var deleteTarget by remember { mutableStateOf<Meeting?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    val filtered = remember(state.meetings, search) { state.meetings.filter { search.isBlank() || it.title.contains(search, true) || it.transcript.contains(search, true) || it.summary.contains(search, true) } }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        item { SectionTitle("巴别回声", tr(lang, "会议历史", "会議履歴", "Meeting history"), tr(lang, "录音、转写与纪要都保存在本机", "データは端末内に保存されます", "Recordings and notes stay on this device")) }
        if (state.meetings.isEmpty()) item { GlassCard { EmptyState(Icons.Rounded.History, tr(lang, "还没有会议", "会議はまだありません", "No meetings yet"), tr(lang, "从工作台开始一次录音。", "ワークスペースから録音を開始。", "Start a recording from Workspace.")) } }
        item { OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(tr(lang, "搜索标题、转写或摘要", "タイトル・文字起こし・要約を検索", "Search titles, transcripts or summaries")) }) }
        items(filtered, key = { it.id }) { meeting ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(meeting) }, colors = CardDefaults.cardColors(containerColor = Glass), shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(1.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(45.dp).clip(RoundedCornerShape(14.dp)).background(Indigo.copy(alpha = .10f)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.primary) }
                    Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(meeting.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(meeting.createdAt))} · ${formatDuration(meeting.durationMs)}", style = MaterialTheme.typography.bodySmall, color = Muted); Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { if (meeting.transcript.isNotBlank()) MiniTag(tr(lang, "有转写", "文字あり", "Transcript")); if (meeting.state == "interrupted") MiniTag(tr(lang, "中断录音已恢复", "中断した録音を復元", "Interrupted audio recovered")); if (meeting.minutes.isNotBlank()) MiniTag(tr(lang, "有纪要", "議事録あり", "Minutes")) } }
                    IconButton(enabled = !state.isRecording && state.workingMeetingId == null && !state.isStarting, onClick = { deleteTarget = meeting }) { Icon(Icons.Rounded.DeleteOutline, null, tint = Danger) }
                }
            }
        }
    }
    deleteTarget?.let { meeting -> AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text(tr(lang, "删除会议？", "会議を削除しますか？", "Delete meeting?")) }, text = { Text(tr(lang, "录音、转写与纪要将从本机删除。", "録音と議事録が端末から削除されます。", "Audio, transcript, and notes will be removed from this device.")) }, confirmButton = { TextButton(onClick = { onDelete(meeting); deleteTarget = null }) { Text(tr(lang, "删除", "削除", "Delete"), color = Danger) } }, dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(tr(lang, "取消", "キャンセル", "Cancel")) } }) }
}

@Composable
private fun SettingsScreen(modifier: Modifier, initial: AppSettings, onSave: (AppSettings) -> Unit, locked: Boolean) {
    var value by remember(initial) { mutableStateOf(initial) }; val lang = value.uiLanguage
    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            SectionTitle("巴别回声", tr(lang, "设置", "設定", "Settings"), tr(lang, "模型、语言、分段与隐私", "モデル・言語・プライバシー", "Models, languages, chunking, and privacy"))
            SettingsCard(tr(lang, "界面与语言", "表示と言語", "Interface & languages")) { ChoiceRow(tr(lang, "界面语言", "表示言語", "Interface"), listOf("zh", "ja", "en"), value.uiLanguage) { value = value.copy(uiLanguage = it) }; ChoiceRow(tr(lang, "识别语言", "認識言語", "Speech language"), listOf("auto", "zh", "ja", "en"), value.asrLanguage) { value = value.copy(asrLanguage = it) }; ChoiceRow(tr(lang, "翻译为", "翻訳先", "Translate to"), listOf("", "zh", "ja", "en"), value.translateTo) { value = value.copy(translateTo = it) } }
            SettingsCard(tr(lang, "语音识别", "音声認識", "Speech recognition")) {
                ChoiceRow(tr(lang, "接口协议", "プロトコル", "Protocol"), listOf("openai", "mimo", "deepgram"), value.asrKind) { value = value.copy(asrKind = it) }
                Text(tr(lang, "Groq 使用 OpenAI 协议；MiMo 仅支持明确选择中文或英文。端点与模型 ID 可自由输入。", "Groq は OpenAI。MiMo は中国語・英語のみ。URL とモデルは編集できます。", "Groq uses OpenAI. MiMo requires Chinese or English. URLs and model IDs remain editable."), style = MaterialTheme.typography.bodySmall, color = Muted)
                SettingField(tr(lang, "服务地址", "サービス URL", "Base URL"), value.asrBaseUrl) { value = value.copy(asrBaseUrl = it) }
                SettingField(tr(lang, "模型 ID", "モデル ID", "Model ID"), value.asrModel) { value = value.copy(asrModel = it) }
                SecretField("ASR API Key", value.asrApiKey) { value = value.copy(asrApiKey = it) }
                SettingsSwitch(tr(lang, "本地服务无需密钥", "ローカルサービスはキー不要", "Local service needs no key"), value.asrNoAuth) { value = value.copy(asrNoAuth = it) }
            }
            SettingsCard(tr(lang, "文本模型", "テキストモデル", "Text models")) {
                SettingField(tr(lang, "服务地址", "サービス URL", "Base URL"), value.llmBaseUrl) { value = value.copy(llmBaseUrl = it) }
                SettingField(tr(lang, "默认模型（任务留空时使用）", "既定モデル", "Default model (fallback)"), value.llmModel) { value = value.copy(llmModel = it) }
                SecretField("LLM API Key", value.llmApiKey) { value = value.copy(llmApiKey = it) }
                SettingsSwitch(tr(lang, "本地服务无需密钥", "ローカルサービスはキー不要", "Local service needs no key"), value.llmNoAuth) { value = value.copy(llmNoAuth = it) }
                SettingField(tr(lang, "摘要模型", "要約モデル", "Summary model"), value.summaryModel) { value = value.copy(summaryModel = it) }
                SettingField(tr(lang, "纪要模型", "議事録モデル", "Minutes model"), value.minutesModel) { value = value.copy(minutesModel = it) }
                SettingField(tr(lang, "翻译模型", "翻訳モデル", "Translation model"), value.translateModel) { value = value.copy(translateModel = it) }
                SettingField(tr(lang, "问答模型", "質問モデル", "Q&A model"), value.askModel) { value = value.copy(askModel = it) }
            }
            SettingsCard(tr(lang, "实时处理", "リアルタイム処理", "Realtime processing")) {
                SettingsSwitch(tr(lang, "实时转写（向识别服务发送音频）", "リアルタイム文字起こし（音声送信）", "Live transcription (sends audio to ASR)"), value.liveTranscription) { value = value.copy(liveTranscription = it) }
                SettingsSwitch(tr(lang, "结束后自动生成纪要（有转写时发送文本）", "終了後に議事録を作成（文字起こし送信）", "Auto minutes on stop (sends existing transcript)"), value.autoMinutes) { value = value.copy(autoMinutes = it) }
                 NumberField(tr(lang, "分段秒数（10–120）", "チャンク秒（10–120）", "Chunk seconds (10–120)"), value.chunkSeconds) { value = value.copy(chunkSeconds = it) }; NumberField(tr(lang, "重叠秒数（0–10）", "重複秒（0–10）", "Overlap seconds (0–10)"), value.overlapSeconds) { value = value.copy(overlapSeconds = it) }; NumberField(tr(lang, "自动摘要秒数（0 关闭）", "自動要約秒（0 で無効）", "Auto summary seconds (0 off)"), value.autoSummarySeconds) { value = value.copy(autoSummarySeconds = it) } }
            ProviderProfiles(value) { value = it }
            SettingsCard(tr(lang, "费用估算", "費用の概算", "Cost estimates")) {
                Text(tr(lang, "填写同一币种的服务费率。混用不同模型时此估算不等于账单。", "同じ通貨の単価を入力。異なるモデルを使うと請求額と異なります。", "Enter rates in one currency. Mixed models and missing usage may differ from your bill."), style = MaterialTheme.typography.bodySmall, color = Muted)
                ChoiceRow(tr(lang, "币种", "通貨", "Currency"), listOf("USD", "CNY", "JPY"), value.priceCurrency) { value = value.copy(priceCurrency = it) }
                PriceField(tr(lang, "ASR / 音频小时", "ASR / 音声時間", "ASR / audio hour"), value.asrPricePerHour) { value = value.copy(asrPricePerHour = it) }
                PriceField(tr(lang, "文本输入 / 百万 token", "入力 / 100万 token", "Input / million tokens"), value.inputPricePerMillion) { value = value.copy(inputPricePerMillion = it) }
                PriceField(tr(lang, "文本输出 / 百万 token", "出力 / 100万 token", "Output / million tokens"), value.outputPricePerMillion) { value = value.copy(outputPricePerMillion = it) }
            }
            SettingsCard(tr(lang, "本地模型与隐私", "ローカルモデルとプライバシー", "Local models & privacy")) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(tr(lang, "允许 HTTP", "HTTP を許可", "Allow HTTP"), fontWeight = FontWeight.SemiBold); Text(tr(lang, "HTTP 不加密音频、文本与密钥，仅用于可信局域网", "HTTP は音声・テキスト・キーを暗号化しません", "HTTP does not encrypt audio, text or keys; trusted LAN only"), style = MaterialTheme.typography.bodySmall, color = Muted) }; Switch(value.allowInsecureHttp, { value = value.copy(allowInsecureHttp = it) }) }; Text(tr(lang, "密钥使用 Android Keystore 加密。音频和文本仅在操作需要时发送到你配置的地址。", "キーは Android Keystore で暗号化されます。", "Keys are encrypted with Android Keystore. Data is sent only for requested processing."), style = MaterialTheme.typography.bodySmall, color = Muted) }
        }
        Button(onClick = { onSave(value) }, enabled = !locked, modifier = Modifier.fillMaxWidth().padding(14.dp).height(50.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Indigo, contentColor = Color.White)) { Text(tr(lang, "保存设置", "設定を保存", "Save settings"), fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun ProviderProfiles(value: AppSettings, onChange: (AppSettings) -> Unit) {
    val lang = value.uiLanguage
    SettingsCard(tr(lang, "多个语音服务与语言路由", "複数の音声認識サービス", "ASR providers & language routes")) {
        SettingsSwitch(tr(lang, "使用多服务配置（替代上面的单服务）", "複数サービス設定を使う", "Use provider profiles (replaces single service above)"), value.useAsrProfiles) { onChange(value.copy(useAsrProfiles = it)) }
        if (value.useAsrProfiles) {
            value.asrProfiles.forEach { profile ->
                var expanded by remember(profile.id) { mutableStateOf(false) }
                fun update(next: AsrProfile) = onChange(value.copy(asrProfiles = value.asrProfiles.map { if (it.id == next.id) next else it }))
                SettingsSwitch(profile.id.uppercase(), profile.enabled) { update(profile.copy(enabled = it)); expanded = it }
                TextButton(onClick = { expanded = !expanded }) { Text(tr(lang, "编辑服务", "サービスを編集", "Edit provider")) }
                if (expanded) {
                    SettingField("URL", profile.baseUrl) { update(profile.copy(baseUrl = it)) }
                    SettingField("Model ID", profile.model) { update(profile.copy(model = it)) }
                    SecretField("${profile.id} API Key", profile.apiKey) { update(profile.copy(apiKey = it)) }
                    SettingsSwitch(tr(lang, "无需密钥", "キー不要", "No API key"), profile.noAuth) { update(profile.copy(noAuth = it)) }
                }
            }
            listOf("zh", "ja", "en", "auto").forEach { language ->
                ChoiceRow(language.uppercase(), value.asrProfiles.filter { it.supports(language) }.map { it.id }, value.asrRoutes[language].orEmpty()) {
                    onChange(value.copy(asrRoutes = value.asrRoutes + (language to it)))
                }
            }
            Text(tr(lang, "只有选中且启用、配置完整、支持该语言的服务会接收音频。", "選択した有効な対応サービスだけに音声を送信します。", "Only the selected, enabled and configured provider supporting this language receives audio."), style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
    SettingsCard(tr(lang, "多个文本服务与任务模型", "複数のテキストサービス", "Text providers & task models")) {
        SettingsSwitch(tr(lang, "使用多文本服务配置", "複数テキストサービスを使う", "Use text provider profiles"), value.useTextProfiles) { onChange(value.copy(useTextProfiles = it)) }
        if (value.useTextProfiles) {
            value.textProfiles.forEach { profile ->
                var expanded by remember(profile.id) { mutableStateOf(false) }
                fun update(next: TextProfile) = onChange(value.copy(textProfiles = value.textProfiles.map { if (it.id == next.id) next else it }))
                SettingsSwitch(profile.id.uppercase(), profile.enabled) { update(profile.copy(enabled = it)); expanded = it }
                TextButton(onClick = { expanded = !expanded }) { Text(tr(lang, "编辑服务", "サービスを編集", "Edit provider")) }
                if (expanded) {
                    SettingField("URL", profile.baseUrl) { update(profile.copy(baseUrl = it)) }
                    SecretField("${profile.id} API Key", profile.apiKey) { update(profile.copy(apiKey = it)) }
                    SettingsSwitch(tr(lang, "无需密钥", "キー不要", "No API key"), profile.noAuth) { update(profile.copy(noAuth = it)) }
                }
            }
            listOf("summary", "minutes", "translation", "ask", "speaker").forEach { task ->
                val role = value.textRoles.getValue(task)
                ChoiceRow(task.uppercase(), value.textProfiles.map { it.id }, role.provider) {
                    onChange(value.copy(textRoles = value.textRoles + (task to role.copy(provider = it))))
                }
                SettingField("$task Model ID", role.model) { onChange(value.copy(textRoles = value.textRoles + (task to role.copy(model = it)))) }
            }
        }
    }
}

@Composable private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) = GlassCard { Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(10.dp)); Column(verticalArrangement = Arrangement.spacedBy(9.dp), content = content) }
@Composable private fun ChoiceRow(label: String, choices: List<String>, selected: String, onSelect: (String) -> Unit) { Column { Text(label, style = MaterialTheme.typography.labelMedium, color = Muted); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { choices.forEach { choice -> FilterChip(selected == choice, { onSelect(choice) }, { Text(choice.ifBlank { "OFF" }.uppercase()) }) } } } }
@Composable private fun SettingField(label: String, value: String, onChange: (String) -> Unit) = OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true)
@Composable private fun SecretField(label: String, value: String, onChange: (String) -> Unit) = OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
@Composable private fun SettingsSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, modifier = Modifier.weight(1f)); Switch(checked, onChange) } }
@Composable private fun PriceField(label: String, value: Double, onChange: (Double) -> Unit) {
    var draft by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(draft, { draft = it; it.toDoubleOrNull()?.let(onChange) }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true)
}
@Composable private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) = OutlinedTextField(value.toString(), { onChange(it.toIntOrNull() ?: value) }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true)
@Composable private fun GlassCard(content: @Composable ColumnScope.() -> Unit) { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Glass), elevation = CardDefaults.cardElevation(1.dp)) { Column(Modifier.padding(17.dp), content = content) } }
@Composable private fun PanelHeader(title: String, meta: String) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f)); Text(meta, style = MaterialTheme.typography.labelSmall, color = Muted) }; HorizontalDivider(Modifier.padding(top = 10.dp, bottom = 8.dp), color = Ink.copy(alpha = .07f)) }
@Composable private fun EmptyState(icon: ImageVector, title: String, body: String) { Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) { Box(Modifier.size(54.dp).clip(RoundedCornerShape(17.dp)).background(Brush.linearGradient(listOf(Indigo.copy(alpha = .12f), Cyan.copy(alpha = .13f)))), contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }; Spacer(Modifier.height(10.dp)); Text(title, fontWeight = FontWeight.Bold); Text(body, color = Muted, style = MaterialTheme.typography.bodySmall) } }
@Composable private fun StatusChip(text: String, background: Color, foreground: Color) { Box(Modifier.clip(RoundedCornerShape(9.dp)).background(background).padding(horizontal = 9.dp, vertical = 5.dp)) { Text(text, style = MaterialTheme.typography.labelMedium, color = foreground, maxLines = 1) } }
@Composable private fun MiniTag(text: String) { Box(Modifier.padding(top = 5.dp).clip(RoundedCornerShape(6.dp)).background(Indigo.copy(alpha = .08f)).padding(horizontal = 6.dp, vertical = 2.dp)) { Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) } }
@Composable private fun StatRow(label: String, value: String) { Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) { Text(label, color = Muted, modifier = Modifier.weight(1f)); Text(value.ifBlank { "—" }, fontWeight = FontWeight.SemiBold) }; HorizontalDivider(color = Ink.copy(alpha = .05f)) }
@Composable private fun SectionTitle(brand: String, title: String, subtitle: String) { Column(Modifier.statusBarsPadding().padding(top = 6.dp, bottom = 3.dp)) { Text(brand, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold); Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall) } }

private fun tr(lang: String, zh: String, ja: String, en: String): String = when (lang) { "ja" -> ja; "en" -> en; else -> zh }
private fun formatDuration(ms: Long): String { val total = (ms / 1_000).coerceAtLeast(0); return if (total >= 3_600) String.format(Locale.US, "%d:%02d:%02d", total / 3_600, total / 60 % 60, total % 60) else String.format(Locale.US, "%02d:%02d", total / 60, total % 60) }
private fun shareMeeting(context: android.content.Context, meeting: Meeting) {
    val body = buildString { append("# ${meeting.title}\n\n"); if (meeting.summary.isNotBlank()) append("## 摘要\n\n${meeting.summary}\n\n"); if (meeting.minutes.isNotBlank()) append("## 纪要\n\n${meeting.minutes}\n\n"); append("## 转写\n\n"); if (meeting.segments.isNotEmpty()) meeting.segments.forEach { append("[${formatDuration(it.startMs)}] ${it.speaker}: ${it.text}\n") } else append(meeting.transcript) }
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_SUBJECT, meeting.title).putExtra(Intent.EXTRA_TEXT, body), "Babel Echo"))
}
