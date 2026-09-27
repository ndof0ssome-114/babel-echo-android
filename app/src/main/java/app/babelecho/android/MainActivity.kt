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
private val Ink = Color(0xFF18243D)
private val Muted = Color(0xFF67758D)
private val Glass = Color(0xD9FFFFFF)
private val Soft = Color(0xFFF1F4FD)
private val Danger = Color(0xFFCF5160)

@Composable
private fun BabelEchoApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lang = state.settings.uiLanguage
    val snackbar = remember { SnackbarHostState() }
    var page by remember { mutableStateOf(AppPage.Workspace) }
    var microphone by remember { mutableStateOf(true) }
    var playback by remember { mutableStateOf(true) }
    var pendingMicrophone by remember { mutableStateOf(true) }
    var pendingPlayback by remember { mutableStateOf(true) }

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.startRecording(result.resultCode, result.data, pendingMicrophone, pendingPlayback)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val audioAllowed = grants[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (audioAllowed) {
            if (pendingPlayback) projectionLauncher.launch(context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
            else viewModel.startRecording(Activity.RESULT_OK, null, pendingMicrophone, false)
        }
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

    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = Indigo, secondary = Cyan, surface = Glass, onSurface = Ink, background = Soft, error = Danger)) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFFE7ECFC), Color(0xFFF8F5FF), Color(0xFFE7F6F7))))) {
            Scaffold(
                containerColor = Color.Transparent,
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    NavigationBar(containerColor = Color(0xEAFBFCFF), modifier = Modifier.navigationBarsPadding()) {
                        NavItem(page, AppPage.Workspace, Icons.Rounded.Home, tr(lang, "工作台", "ワークスペース", "Workspace")) { page = it }
                        NavItem(page, AppPage.History, Icons.Rounded.History, tr(lang, "历史", "履歴", "History")) { page = it }
                        NavItem(page, AppPage.Settings, Icons.Rounded.Settings, tr(lang, "设置", "設定", "Settings")) { page = it }
                    }
                },
            ) { padding ->
                val selected = state.meetings.firstOrNull { it.id == state.selectedMeetingId }
                when (page) {
                    AppPage.Workspace -> WorkspaceScreen(
                        Modifier.padding(padding), state, selected, microphone, playback,
                        onMicrophone = { microphone = it }, onPlayback = { playback = it },
                        onStart = ::startRecording, onPause = viewModel::togglePause, onStop = viewModel::stopRecording,
                        onOpenHistory = { page = AppPage.History }, onOpenSettings = { page = AppPage.Settings },
                        onRename = { title -> selected?.let { viewModel.renameMeeting(it, title) } },
                        onTranscribe = { selected?.let(viewModel::transcribe) }, onSummarize = { selected?.let(viewModel::summarize) },
                        onMinutes = { selected?.let(viewModel::createMinutes) }, onTranslate = { selected?.let(viewModel::translate) },
                        onAsk = { question -> selected?.let { viewModel.ask(it, question) } }, onShare = { selected?.let { shareMeeting(context, it) } },
                    )
                    AppPage.History -> HistoryScreen(Modifier.padding(padding), state, onOpen = { viewModel.selectMeeting(it.id); page = AppPage.Workspace }, onDelete = viewModel::deleteMeeting)
                    AppPage.Settings -> SettingsScreen(Modifier.padding(padding), state.settings, viewModel::saveSettings)
                }
            }
        }
    }
}

@Composable
private fun NavigationBarScope.NavItem(current: AppPage, target: AppPage, icon: ImageVector, label: String, onChange: (AppPage) -> Unit) {
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
) {
    val lang = state.settings.uiLanguage
    var panel by remember { mutableStateOf(MeetingPanel.Transcript) }
    var rename by remember { mutableStateOf(false) }
    var titleDraft by remember(meeting?.id, meeting?.title) { mutableStateOf(meeting?.title.orEmpty()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.isRecording) { while (state.isRecording) { now = System.currentTimeMillis(); delay(1_000) } }
    val elapsed = if (state.isRecording && meeting != null) (now - meeting.createdAt).coerceAtLeast(0) else meeting?.durationMs ?: 0

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            BrandHeader(
                lang, meeting?.title ?: tr(lang, "未命名会议", "無題の会議", "Untitled meeting"), elapsed,
                when { state.isPaused -> tr(lang, "已暂停", "一時停止", "Paused"); state.isRecording -> tr(lang, "录音中", "録音中", "Recording"); else -> tr(lang, "待机", "待機", "Ready") },
                state.settings.asrModel, { rename = true }, onOpenHistory, onOpenSettings,
            )
        }
        item {
            CaptureCard(lang, state.isRecording, state.isPaused, microphone, playback, state.audioLevel, state.settings.asrLanguage, state.settings.translateTo, onMicrophone, onPlayback, onStart, onPause, onStop)
        }
        item { MeetingTabs(lang, panel) { panel = it } }
        item {
            GlassCard {
                when (panel) {
                    MeetingPanel.Transcript -> TranscriptPanel(lang, meeting, state.workingMeetingId == meeting?.id, onTranscribe, onTranslate)
                    MeetingPanel.Summary -> TextPanel(lang, tr(lang, "实时摘要", "リアルタイム要約", "Live summary"), meeting?.summary.orEmpty(), tr(lang, "录音中按设置的间隔增量更新，也可以手动生成。", "録音中に増分更新できます。", "Updated incrementally while recording, or generated manually."), tr(lang, "重新总结", "再要約", "Summarize"), onSummarize, state.workingMeetingId == meeting?.id)
                    MeetingPanel.Minutes -> TextPanel(lang, tr(lang, "智能纪要", "議事録", "Meeting minutes"), meeting?.minutes.orEmpty(), tr(lang, "结构化整理结论、待办、风险和章节。", "決定・タスク・リスクを構造化します。", "Structured decisions, action items, risks, and chapters."), tr(lang, "生成纪要", "議事録を生成", "Create minutes"), onMinutes, state.workingMeetingId == meeting?.id)
                    MeetingPanel.Ask -> AskPanel(lang, meeting, state.workingMeetingId == meeting?.id, onAsk)
                    MeetingPanel.Stats -> StatsPanel(lang, meeting, onShare)
                }
            }
        }
    }

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
            StatusChip(state, if (active) Color(0x1ACF5160) else Color(0x144C63D9), if (active) Danger else Indigo)
        }
        Spacer(Modifier.height(9.dp))
        Row { Text(model, style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)); Text(tr(lang, "点击标题可修改", "タイトルをタップして編集", "Tap title to edit"), style = MaterialTheme.typography.labelSmall, color = Muted) }
    }
}

@Composable
private fun CaptureCard(lang: String, recording: Boolean, paused: Boolean, microphone: Boolean, playback: Boolean, level: Float, speechLanguage: String, translateTo: String, onMicrophone: (Boolean) -> Unit, onPlayback: (Boolean) -> Unit, onStart: () -> Unit, onPause: () -> Unit, onStop: () -> Unit) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr(lang, "录音控制", "録音コントロール", "Capture controls"), fontWeight = FontWeight.Bold)
                Text(if (recording) tr(lang, "正在实时转写并保存本地音频", "リアルタイム文字起こし中", "Live transcription and local recording") else tr(lang, "选择声音来源后开始会议", "音声ソースを選択してください", "Choose audio sources and start"), style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            StatusChip(speechLanguage.uppercase(), Color(0x114C63D9), Indigo)
            if (translateTo.isNotBlank()) { Spacer(Modifier.width(5.dp)); StatusChip("→ ${translateTo.uppercase()}", Color(0x1139AEB5), Cyan) }
        }
        Spacer(Modifier.height(13.dp)); AudioMeter(level, recording && !paused); Spacer(Modifier.height(13.dp))
        SourceRow(Icons.Rounded.Mic, tr(lang, "麦克风", "マイク", "Microphone"), tr(lang, "你的发言与环境声音", "自分と周囲の音声", "You and nearby voices"), microphone, !recording, onMicrophone)
        HorizontalDivider(Modifier.padding(vertical = 7.dp), color = Ink.copy(alpha = .07f))
        SourceRow(Icons.Rounded.DesktopWindows, tr(lang, "系统声音", "システム音声", "System audio"), tr(lang, "允许捕获的媒体和游戏声音", "許可されたメディア音声", "Capturable media and games"), playback, !recording, onPlayback)
        Spacer(Modifier.height(14.dp))
        if (!recording) Button(onClick = onStart, enabled = microphone || playback, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Indigo)) {
            Icon(Icons.Rounded.Mic, null); Spacer(Modifier.width(7.dp)); Text(tr(lang, "开始录音", "録音を開始", "Start recording"), fontWeight = FontWeight.Bold)
        } else Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Button(onClick = onPause, modifier = Modifier.weight(.42f).height(50.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF0F2FA), contentColor = Ink)) {
                Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null); Spacer(Modifier.width(5.dp)); Text(if (paused) tr(lang, "继续", "再開", "Resume") else tr(lang, "暂停", "一時停止", "Pause"))
            }
            Button(onClick = onStop, modifier = Modifier.weight(.58f).height(50.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Danger)) {
                Icon(Icons.Rounded.StopCircle, null); Spacer(Modifier.width(5.dp)); Text(tr(lang, "结束并生成纪要", "終了して議事録を作成", "Stop & create minutes"))
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
        Box(Modifier.size(39.dp).clip(CircleShape).background(Indigo.copy(alpha = .09f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Indigo, modifier = Modifier.size(21.dp)) }
        Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(detail, style = MaterialTheme.typography.bodySmall, color = Muted) }; Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
private fun MeetingTabs(lang: String, current: MeetingPanel, onSelect: (MeetingPanel) -> Unit) {
    val tabs = listOf(MeetingPanel.Transcript to tr(lang, "转写", "文字起こし", "Transcript"), MeetingPanel.Summary to tr(lang, "摘要", "要約", "Summary"), MeetingPanel.Minutes to tr(lang, "纪要", "議事録", "Minutes"), MeetingPanel.Ask to tr(lang, "问答", "質問", "Ask"), MeetingPanel.Stats to tr(lang, "统计", "統計", "Stats"))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) { tabs.forEach { (tab, text) -> FilterChip(selected = current == tab, onClick = { onSelect(tab) }, label = { Text(text, maxLines = 1) }, modifier = Modifier.weight(1f)) } }
}

@Composable
private fun TranscriptPanel(lang: String, meeting: Meeting?, working: Boolean, onTranscribe: () -> Unit, onTranslate: () -> Unit) {
    PanelHeader(tr(lang, "实时转写", "リアルタイム文字起こし", "Live transcript"), "${meeting?.segments?.size ?: 0} ${tr(lang, "条", "件", "segments")}")
    if (meeting == null || (meeting.segments.isEmpty() && meeting.transcript.isBlank())) EmptyState(Icons.Rounded.GraphicEq, tr(lang, "还没有转写内容", "文字起こしはまだありません", "No transcript yet"), tr(lang, "开始录音后会按分段间隔实时出现。", "録音開始後にリアルタイムで表示されます。", "Segments appear while recording."))
    else meeting.segments.ifEmpty { listOf(TranscriptSegment(0, 0, meeting.durationMs, text = meeting.transcript)) }.forEach { TranscriptRow(it) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onTranslate, enabled = !working && meeting != null) { Icon(Icons.Rounded.Language, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text(tr(lang, "翻译", "翻訳", "Translate")) }
        TextButton(onClick = onTranscribe, enabled = !working && meeting != null) { Icon(Icons.Rounded.Refresh, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text(tr(lang, "重新转写", "再文字起こし", "Retranscribe")) }
        if (working) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun TranscriptRow(segment: TranscriptSegment) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(formatDuration(segment.startMs), style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.width(48.dp))
        Column(Modifier.weight(1f)) {
            Text(segment.speaker, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Indigo)
            SelectionContainer { Text(segment.text, lineHeight = MaterialTheme.typography.bodyLarge.lineHeight) }
            if (segment.translated.isNotBlank()) { Spacer(Modifier.height(5.dp)); Text(segment.translated, color = Muted, style = MaterialTheme.typography.bodyMedium) }
        }
    }; HorizontalDivider(color = Ink.copy(alpha = .055f))
}

@Composable
private fun TextPanel(lang: String, title: String, content: String, empty: String, action: String, onAction: () -> Unit, working: Boolean) {
    PanelHeader(title, if (content.isBlank()) tr(lang, "尚未生成", "未生成", "Not generated") else tr(lang, "已更新", "更新済み", "Updated"))
    if (content.isBlank()) EmptyState(Icons.Rounded.AutoAwesome, title, empty) else SelectionContainer { Text(content, lineHeight = MaterialTheme.typography.bodyLarge.lineHeight) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onAction, enabled = !working) { if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text(action) } }
}

@Composable
private fun AskPanel(lang: String, meeting: Meeting?, working: Boolean, onAsk: (String) -> Unit) {
    var question by remember(meeting?.id) { mutableStateOf("") }
    PanelHeader(tr(lang, "会议问答", "会議への質問", "Ask this meeting"), "${meeting?.qa?.size ?: 0}")
    meeting?.qa?.forEach { entry -> Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Text("Q  ${entry.question}", fontWeight = FontWeight.SemiBold, color = Indigo); Spacer(Modifier.height(5.dp)); SelectionContainer { Text(entry.answer) } }; HorizontalDivider(color = Ink.copy(alpha = .06f)) }
    if (meeting?.qa.isNullOrEmpty()) Text(tr(lang, "基于全部转写提问，回答会尽量标注时间。", "文字起こしに基づいて回答します。", "Answers use the transcript and cite time when possible."), color = Muted)
    Spacer(Modifier.height(12.dp)); Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(question, { question = it }, Modifier.weight(1f), placeholder = { Text(tr(lang, "例如：谁负责下一步？", "例：次の担当者は？", "Who owns the next step?")) }, maxLines = 3)
        Spacer(Modifier.width(7.dp)); IconButton(onClick = { if (question.isNotBlank()) { onAsk(question); question = "" } }, enabled = !working && meeting != null) { Icon(Icons.Rounded.Send, null, tint = Indigo) }
    }
}

@Composable
private fun StatsPanel(lang: String, meeting: Meeting?, onShare: () -> Unit) {
    PanelHeader(tr(lang, "会议统计", "会議統計", "Meeting statistics"), meeting?.let { formatDuration(it.durationMs) }.orEmpty())
    val stats = meeting?.stats
    StatRow(tr(lang, "录音时长", "録音時間", "Duration"), formatDuration(meeting?.durationMs ?: 0)); StatRow(tr(lang, "语音请求", "音声リクエスト", "ASR requests"), (stats?.asrCalls ?: 0).toString()); StatRow(tr(lang, "文本模型请求", "LLM リクエスト", "LLM requests"), (stats?.llmCalls ?: 0).toString()); StatRow(tr(lang, "转写字符", "文字数", "Transcript characters"), (stats?.transcriptChars ?: meeting?.transcript?.length ?: 0).toString()); StatRow(tr(lang, "声音来源", "音声ソース", "Sources"), listOfNotNull("MIC".takeIf { meeting?.usedMicrophone == true }, "SYSTEM".takeIf { meeting?.usedPlayback == true }).joinToString(" + "))
    Spacer(Modifier.height(10.dp)); Button(onClick = onShare, enabled = meeting != null, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Indigo)) { Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(6.dp)); Text(tr(lang, "分享 Markdown", "Markdown を共有", "Share Markdown")) }
}

@Composable
private fun HistoryScreen(modifier: Modifier, state: MainUiState, onOpen: (Meeting) -> Unit, onDelete: (Meeting) -> Unit) {
    val lang = state.settings.uiLanguage; var deleteTarget by remember { mutableStateOf<Meeting?>(null) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        item { SectionTitle("巴别回声", tr(lang, "会议历史", "会議履歴", "Meeting history"), tr(lang, "录音、转写与纪要都保存在本机", "データは端末内に保存されます", "Recordings and notes stay on this device")) }
        if (state.meetings.isEmpty()) item { GlassCard { EmptyState(Icons.Rounded.History, tr(lang, "还没有会议", "会議はまだありません", "No meetings yet"), tr(lang, "从工作台开始一次录音。", "ワークスペースから録音を開始。", "Start a recording from Workspace.")) } }
        items(state.meetings, key = { it.id }) { meeting ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(meeting) }, colors = CardDefaults.cardColors(containerColor = Glass), shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(1.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(45.dp).clip(RoundedCornerShape(14.dp)).background(Indigo.copy(alpha = .10f)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.GraphicEq, null, tint = Indigo) }
                    Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(meeting.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(meeting.createdAt))} · ${formatDuration(meeting.durationMs)}", style = MaterialTheme.typography.bodySmall, color = Muted); Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { if (meeting.transcript.isNotBlank()) MiniTag(tr(lang, "有转写", "文字あり", "Transcript")); if (meeting.minutes.isNotBlank()) MiniTag(tr(lang, "有纪要", "議事録あり", "Minutes")) } }
                    IconButton(onClick = { deleteTarget = meeting }) { Icon(Icons.Rounded.DeleteOutline, null, tint = Danger) }
                }
            }
        }
    }
    deleteTarget?.let { meeting -> AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text(tr(lang, "删除会议？", "会議を削除しますか？", "Delete meeting?")) }, text = { Text(tr(lang, "录音、转写与纪要将从本机删除。", "録音と議事録が端末から削除されます。", "Audio, transcript, and notes will be removed from this device.")) }, confirmButton = { TextButton(onClick = { onDelete(meeting); deleteTarget = null }) { Text(tr(lang, "删除", "削除", "Delete"), color = Danger) } }, dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(tr(lang, "取消", "キャンセル", "Cancel")) } }) }
}

@Composable
private fun SettingsScreen(modifier: Modifier, initial: AppSettings, onSave: (AppSettings) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }; val lang = value.uiLanguage
    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            SectionTitle("巴别回声", tr(lang, "设置", "設定", "Settings"), tr(lang, "模型、语言、分段与隐私", "モデル・言語・プライバシー", "Models, languages, chunking, and privacy"))
            SettingsCard(tr(lang, "界面与语言", "表示と言語", "Interface & languages")) { ChoiceRow(tr(lang, "界面语言", "表示言語", "Interface"), listOf("zh", "ja", "en"), value.uiLanguage) { value = value.copy(uiLanguage = it) }; ChoiceRow(tr(lang, "识别语言", "認識言語", "Speech language"), listOf("auto", "zh", "ja", "en"), value.asrLanguage) { value = value.copy(asrLanguage = it) }; ChoiceRow(tr(lang, "翻译为", "翻訳先", "Translate to"), listOf("", "zh", "ja", "en"), value.translateTo) { value = value.copy(translateTo = it) } }
            SettingsCard(tr(lang, "语音识别", "音声認識", "Speech recognition")) {
                SettingField(tr(lang, "服务地址", "サービス URL", "Base URL"), value.asrBaseUrl) { value = value.copy(asrBaseUrl = it) }
                SettingField(tr(lang, "模型 ID", "モデル ID", "Model ID"), value.asrModel) { value = value.copy(asrModel = it) }
                SecretField("ASR API Key", value.asrApiKey) { value = value.copy(asrApiKey = it) }
                SettingsSwitch(tr(lang, "本地服务无需密钥", "ローカルサービスはキー不要", "Local service needs no key"), value.asrNoAuth) { value = value.copy(asrNoAuth = it) }
            }
            SettingsCard(tr(lang, "文本模型", "テキストモデル", "Text models")) {
                SettingField(tr(lang, "服务地址", "サービス URL", "Base URL"), value.llmBaseUrl) { value = value.copy(llmBaseUrl = it) }
                SecretField("LLM API Key", value.llmApiKey) { value = value.copy(llmApiKey = it) }
                SettingsSwitch(tr(lang, "本地服务无需密钥", "ローカルサービスはキー不要", "Local service needs no key"), value.llmNoAuth) { value = value.copy(llmNoAuth = it) }
                SettingField(tr(lang, "摘要模型", "要約モデル", "Summary model"), value.summaryModel) { value = value.copy(summaryModel = it) }
                SettingField(tr(lang, "纪要模型", "議事録モデル", "Minutes model"), value.minutesModel) { value = value.copy(minutesModel = it) }
                SettingField(tr(lang, "翻译模型", "翻訳モデル", "Translation model"), value.translateModel) { value = value.copy(translateModel = it) }
                SettingField(tr(lang, "问答模型", "質問モデル", "Q&A model"), value.askModel) { value = value.copy(askModel = it) }
            }
            SettingsCard(tr(lang, "实时处理", "リアルタイム処理", "Realtime processing")) { NumberField(tr(lang, "分段秒数（10–120）", "チャンク秒（10–120）", "Chunk seconds (10–120)"), value.chunkSeconds) { value = value.copy(chunkSeconds = it) }; NumberField(tr(lang, "重叠秒数（0–10）", "重複秒（0–10）", "Overlap seconds (0–10)"), value.overlapSeconds) { value = value.copy(overlapSeconds = it) }; NumberField(tr(lang, "自动摘要秒数（0 关闭）", "自動要約秒（0 で無効）", "Auto summary seconds (0 off)"), value.autoSummarySeconds) { value = value.copy(autoSummarySeconds = it) } }
            SettingsCard(tr(lang, "本地模型与隐私", "ローカルモデルとプライバシー", "Local models & privacy")) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(tr(lang, "允许 HTTP", "HTTP を許可", "Allow HTTP"), fontWeight = FontWeight.SemiBold); Text(tr(lang, "仅连接可信局域网中的本地模型", "信頼できる LAN のみ", "Trusted LAN models only"), style = MaterialTheme.typography.bodySmall, color = Muted) }; Switch(value.allowInsecureHttp, { value = value.copy(allowInsecureHttp = it) }) }; Text(tr(lang, "密钥使用 Android Keystore 加密。音频和文本仅在操作需要时发送到你配置的地址。", "キーは Android Keystore で暗号化されます。", "Keys are encrypted with Android Keystore. Data is sent only for requested processing."), style = MaterialTheme.typography.bodySmall, color = Muted) }
        }
        Button(onClick = { onSave(value) }, Modifier.fillMaxWidth().padding(14.dp).height(50.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Indigo)) { Text(tr(lang, "保存设置", "設定を保存", "Save settings"), fontWeight = FontWeight.Bold) }
    }
}

@Composable private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) = GlassCard { Text(title, fontWeight = FontWeight.Bold, color = Indigo); Spacer(Modifier.height(10.dp)); Column(verticalArrangement = Arrangement.spacedBy(9.dp), content = content) }
@Composable private fun ChoiceRow(label: String, choices: List<String>, selected: String, onSelect: (String) -> Unit) { Column { Text(label, style = MaterialTheme.typography.labelMedium, color = Muted); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { choices.forEach { choice -> FilterChip(selected == choice, { onSelect(choice) }, { Text(choice.ifBlank { "OFF" }.uppercase()) }) } } } }
@Composable private fun SettingField(label: String, value: String, onChange: (String) -> Unit) = OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true)
@Composable private fun SecretField(label: String, value: String, onChange: (String) -> Unit) = OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
@Composable private fun SettingsSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, modifier = Modifier.weight(1f)); Switch(checked, onChange) } }
@Composable private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) = OutlinedTextField(value.toString(), { onChange(it.toIntOrNull() ?: value) }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true)
@Composable private fun GlassCard(content: @Composable ColumnScope.() -> Unit) { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Glass), elevation = CardDefaults.cardElevation(1.dp)) { Column(Modifier.padding(17.dp), content = content) } }
@Composable private fun PanelHeader(title: String, meta: String) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f)); Text(meta, style = MaterialTheme.typography.labelSmall, color = Muted) }; HorizontalDivider(Modifier.padding(top = 10.dp, bottom = 8.dp), color = Ink.copy(alpha = .07f)) }
@Composable private fun EmptyState(icon: ImageVector, title: String, body: String) { Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) { Box(Modifier.size(54.dp).clip(RoundedCornerShape(17.dp)).background(Brush.linearGradient(listOf(Indigo.copy(alpha = .12f), Cyan.copy(alpha = .13f)))), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Indigo) }; Spacer(Modifier.height(10.dp)); Text(title, fontWeight = FontWeight.Bold); Text(body, color = Muted, style = MaterialTheme.typography.bodySmall) } }
@Composable private fun StatusChip(text: String, background: Color, foreground: Color) { Box(Modifier.clip(RoundedCornerShape(9.dp)).background(background).padding(horizontal = 9.dp, vertical = 5.dp)) { Text(text, style = MaterialTheme.typography.labelMedium, color = foreground, maxLines = 1) } }
@Composable private fun MiniTag(text: String) { Box(Modifier.padding(top = 5.dp).clip(RoundedCornerShape(6.dp)).background(Indigo.copy(alpha = .08f)).padding(horizontal = 6.dp, vertical = 2.dp)) { Text(text, style = MaterialTheme.typography.labelSmall, color = Indigo) } }
@Composable private fun StatRow(label: String, value: String) { Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) { Text(label, color = Muted, modifier = Modifier.weight(1f)); Text(value.ifBlank { "—" }, fontWeight = FontWeight.SemiBold) }; HorizontalDivider(color = Ink.copy(alpha = .05f)) }
@Composable private fun SectionTitle(brand: String, title: String, subtitle: String) { Column(Modifier.statusBarsPadding().padding(top = 6.dp, bottom = 3.dp)) { Text(brand, color = Indigo, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold); Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall) } }

private fun tr(lang: String, zh: String, ja: String, en: String): String = when (lang) { "ja" -> ja; "en" -> en; else -> zh }
private fun formatDuration(ms: Long): String { val total = (ms / 1_000).coerceAtLeast(0); return if (total >= 3_600) String.format(Locale.US, "%d:%02d:%02d", total / 3_600, total / 60 % 60, total % 60) else String.format(Locale.US, "%02d:%02d", total / 60, total % 60) }
private fun shareMeeting(context: android.content.Context, meeting: Meeting) {
    val body = buildString { append("# ${meeting.title}\n\n"); if (meeting.summary.isNotBlank()) append("## 摘要\n\n${meeting.summary}\n\n"); if (meeting.minutes.isNotBlank()) append("## 纪要\n\n${meeting.minutes}\n\n"); append("## 转写\n\n"); if (meeting.segments.isNotEmpty()) meeting.segments.forEach { append("[${formatDuration(it.startMs)}] ${it.speaker}: ${it.text}\n") } else append(meeting.transcript) }
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_SUBJECT, meeting.title).putExtra(Intent.EXTRA_TEXT, body), "Babel Echo"))
}
