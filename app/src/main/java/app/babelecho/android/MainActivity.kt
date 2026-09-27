package app.babelecho.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BabelEchoApp(viewModel) }
    }
}

private val Indigo = Color(0xFF5865F2)
private val Ink = Color(0xFF17203A)
private val Glass = Color(0xD9FFFFFF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BabelEchoApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }
    var microphone by remember { mutableStateOf(true) }
    var playback by remember { mutableStateOf(true) }
    var pendingMicrophone by remember { mutableStateOf(true) }
    var pendingPlayback by remember { mutableStateOf(true) }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.startRecording(result.resultCode, result.data, pendingMicrophone, pendingPlayback)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            if (pendingPlayback) {
                val manager = context.getSystemService(MediaProjectionManager::class.java)
                projectionLauncher.launch(manager.createScreenCaptureIntent())
            } else {
                viewModel.startRecording(Activity.RESULT_OK, null, pendingMicrophone, false)
            }
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = Indigo,
            secondary = Color(0xFF00A7A5),
            surface = Glass,
            onSurface = Ink,
            background = Color(0xFFF1F4FF),
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFFE9EDFF), Color(0xFFF8F5FF), Color(0xFFE9FAFA)),
                    ),
                ),
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xBFFFFFFF)),
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Indigo),
                                    contentAlignment = Alignment.Center,
                                ) { Icon(Icons.Rounded.GraphicEq, null, tint = Color.White) }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("巴别回声", fontWeight = FontWeight.Bold)
                                    Text("Babel Echo · Android alpha", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = { showSettings = true }) {
                                Icon(Icons.Rounded.Settings, "设置")
                            }
                        },
                    )
                },
            ) { padding ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item {
                        RecordingCard(
                            isRecording = state.isRecording,
                            microphone = microphone,
                            playback = playback,
                            onMicrophoneChange = { microphone = it },
                            onPlaybackChange = { playback = it },
                            onStart = {
                                pendingMicrophone = microphone
                                pendingPlayback = playback
                                val needed = buildList {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                        add(Manifest.permission.RECORD_AUDIO)
                                    }
                                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                        add(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }
                                if (needed.isNotEmpty()) {
                                    permissionLauncher.launch(needed.toTypedArray())
                                } else if (playback) {
                                    val manager = context.getSystemService(MediaProjectionManager::class.java)
                                    projectionLauncher.launch(manager.createScreenCaptureIntent())
                                } else {
                                    viewModel.startRecording(Activity.RESULT_OK, null, microphone, false)
                                }
                            },
                            onStop = viewModel::stopRecording,
                        )
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("录音与纪要", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text("${state.meetings.size} 条", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (state.meetings.isEmpty()) {
                        item {
                            GlassCard {
                                Text("还没有录音。选择音源后开始录制，停止后会保存在本机。")
                            }
                        }
                    }
                    items(state.meetings, key = { it.id }) { meeting ->
                        MeetingCard(
                            meeting = meeting,
                            working = state.workingMeetingId == meeting.id,
                            onTranscribe = { viewModel.transcribe(meeting) },
                            onSummarize = { viewModel.summarize(meeting) },
                        )
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            initial = state.settings,
            onDismiss = { showSettings = false },
            onSave = {
                viewModel.saveSettings(it)
                showSettings = false
            },
        )
    }
}

@Composable
private fun RecordingCard(
    isRecording: Boolean,
    microphone: Boolean,
    playback: Boolean,
    onMicrophoneChange: (Boolean) -> Unit,
    onPlaybackChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    GlassCard {
        Text(
            if (isRecording) "正在收集声音" else "准备开始",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (isRecording) "录音在前台服务中持续进行，可以暂时切换到会议应用。"
            else "可同时采集你的麦克风和允许录制的系统媒体声音。",
            color = Ink.copy(alpha = 0.72f),
        )
        Spacer(Modifier.height(18.dp))
        SourceSwitch(Icons.Rounded.Mic, "麦克风", "你的发言与周围声音", microphone, !isRecording, onMicrophoneChange)
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Ink.copy(alpha = 0.08f))
        SourceSwitch(Icons.Rounded.DesktopWindows, "系统声音", "媒体和游戏；部分应用会禁止捕获", playback, !isRecording, onPlaybackChange)
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = if (isRecording) onStop else onStart,
            enabled = isRecording || microphone || playback,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (isRecording) Color(0xFFE25162) else Indigo),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(if (isRecording) Icons.Rounded.StopCircle else Icons.Rounded.GraphicEq, null)
            Spacer(Modifier.width(8.dp))
            Text(if (isRecording) "停止并保存" else "开始录音", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SourceSwitch(
    icon: ImageVector,
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(Indigo.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Indigo)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Ink.copy(alpha = 0.62f))
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun MeetingCard(meeting: Meeting, working: Boolean, onTranscribe: () -> Unit, onSummarize: () -> Unit) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(meeting.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sources = listOfNotNull(
                    "麦克风".takeIf { meeting.usedMicrophone },
                    "系统声音".takeIf { meeting.usedPlayback },
                ).joinToString(" + ")
                Text(
                    "${formatDuration(meeting.durationMs)} · $sources",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.copy(alpha = 0.6f),
                )
            }
            if (working) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        if (meeting.transcript.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text("转写", style = MaterialTheme.typography.labelLarge, color = Indigo)
            SelectionContainer {
                Text(meeting.transcript, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
        }
        if (meeting.summary.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text("摘要", style = MaterialTheme.typography.labelLarge, color = Indigo)
            SelectionContainer { Text(meeting.summary) }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onTranscribe, enabled = !working) { Text(if (meeting.transcript.isBlank()) "转写" else "重新转写") }
            TextButton(onClick = onSummarize, enabled = !working && meeting.transcript.isNotBlank()) {
                Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (meeting.summary.isBlank()) "生成摘要" else "重新摘要")
            }
        }
    }
}

@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Glass),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

@Composable
private fun SettingsDialog(initial: AppSettings, onDismiss: () -> Unit, onSave: (AppSettings) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("模型与分段设置") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text("语音识别", fontWeight = FontWeight.Bold, color = Indigo) }
                item { SettingField("ASR 服务地址", value.asrBaseUrl) { value = value.copy(asrBaseUrl = it) } }
                item { SettingField("ASR 模型 ID", value.asrModel) { value = value.copy(asrModel = it) } }
                item { SettingField("语言（auto / ja / zh / en）", value.asrLanguage) { value = value.copy(asrLanguage = it) } }
                item { SecretField("ASR API Key", value.asrApiKey) { value = value.copy(asrApiKey = it) } }
                item { Text("摘要模型", fontWeight = FontWeight.Bold, color = Indigo) }
                item { SettingField("LLM 服务地址", value.llmBaseUrl) { value = value.copy(llmBaseUrl = it) } }
                item { SettingField("LLM 模型 ID", value.llmModel) { value = value.copy(llmModel = it) } }
                item { SecretField("LLM API Key", value.llmApiKey) { value = value.copy(llmApiKey = it) } }
                item { Text("分段与衔接", fontWeight = FontWeight.Bold, color = Indigo) }
                item {
                    SettingField("分段秒数（10–120）", value.chunkSeconds.toString()) {
                        value = value.copy(chunkSeconds = it.toIntOrNull() ?: value.chunkSeconds)
                    }
                }
                item {
                    SettingField("重叠秒数（0–10）", value.overlapSeconds.toString()) {
                        value = value.copy(overlapSeconds = it.toIntOrNull() ?: value.overlapSeconds)
                    }
                }
                item {
                    Text(
                        "密钥由 Android Keystore 加密保存。转写和摘要仅在你点击对应按钮时发送。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink.copy(alpha = 0.65f),
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("允许不安全的 HTTP")
                            Text("仅用于可信局域网中的本地模型", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = value.allowInsecureHttp,
                            onCheckedChange = { value = value.copy(allowInsecureHttp = it) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SettingField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(value, onValueChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true)
}

@Composable
private fun SecretField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value,
        onValueChange,
        Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
    )
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1_000
    return String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
}
