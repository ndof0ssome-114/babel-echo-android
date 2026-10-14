package app.babelecho.android

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.babelecho.android.cloud.CloudClient
import app.babelecho.android.cloud.ModelUsage
import app.babelecho.android.data.*
import app.babelecho.android.recording.MediaImporter
import app.babelecho.android.recording.RecordingService
import app.babelecho.android.recording.WavFileWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

fun Meeting.withUsage(usage: ModelUsage): Meeting {
    val tasks = if (usage.task.isBlank()) stats.textUsageByTask else {
        val old = stats.textUsageByTask[usage.task] ?: TextUsage()
        stats.textUsageByTask + (usage.task to TextUsage(old.calls + usage.llmCalls,
            old.inputTokens + usage.inputTokens, old.outputTokens + usage.outputTokens,
            if (old.reasoningTokens == null && usage.reasoningTokens == null) null else (old.reasoningTokens ?: 0) + (usage.reasoningTokens ?: 0),
            old.unreportedCalls + usage.unreportedCalls))
    }
    return copy(stats = stats.copy(
    asrCalls = stats.asrCalls + usage.asrCalls, llmCalls = stats.llmCalls + usage.llmCalls,
    asrSeconds = stats.asrSeconds + usage.asrSeconds, inputTokens = stats.inputTokens + usage.inputTokens,
    outputTokens = stats.outputTokens + usage.outputTokens,
    textUsageByTask = tasks,
))
}

data class MainUiState(
    val meetings: List<Meeting> = emptyList(), val settings: AppSettings = AppSettings(),
    val isRecording: Boolean = false, val isPaused: Boolean = false, val isStarting: Boolean = false,
    val isStopping: Boolean = false, val audioLevel: Float = 0f, val recordedDurationMs: Long = 0,
    val activeMeetingId: String? = null, val selectedMeetingId: String? = null,
    val workingMeetingId: String? = null, val message: String? = null,
    val isSavingSettings: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = MeetingStore(application)
    private val secureSettings = SecureSettings(application)
    private val _state = MutableStateFlow(MainUiState(settings = secureSettings.load()))
    val state = _state.asStateFlow()
    private val _settingsDraft = MutableStateFlow(_state.value.settings)
    val settingsDraft = _settingsDraft.asStateFlow()
    fun editSettings(settings: AppSettings) { _settingsDraft.value = settings }
    private var serviceWorking = false
    private var startReserved = false
    private val recordingReceiver = RecordingStateReceiver(::receiveRecordingState)
    private fun receiveRecordingState(intent: Intent?) {
            if (intent?.action == RecordingService.ACTION_SEGMENT) { refresh(); return }
            if (intent?.action != RecordingService.ACTION_STATE) return
            val recording = intent.getBooleanExtra(RecordingService.EXTRA_IS_RECORDING, false)
            val id = intent.getStringExtra(RecordingService.EXTRA_MEETING_ID)?.takeIf { it.isNotBlank() }
            val processing = intent.getBooleanExtra(RecordingService.EXTRA_PROCESSING, false)
            val wasRecording = _state.value.isRecording
            val working = if (processing) id else if (serviceWorking) null else _state.value.workingMeetingId
            serviceWorking = processing
            _state.value = _state.value.copy(
                isRecording = recording, isStarting = false,
                isStopping = recording && _state.value.isStopping,
                isPaused = intent.getBooleanExtra(RecordingService.EXTRA_IS_PAUSED, false),
                audioLevel = if (recording) intent.getFloatExtra(RecordingService.EXTRA_LEVEL, _state.value.audioLevel) else 0f,
                recordedDurationMs = intent.getLongExtra(RecordingService.EXTRA_DURATION, _state.value.recordedDurationMs),
                activeMeetingId = if (recording) id else null,
                selectedMeetingId = if (recording && !wasRecording) id else _state.value.selectedMeetingId,
                workingMeetingId = working,
                message = intent.getStringExtra(RecordingService.EXTRA_ERROR) ?: _state.value.message,
            )
            if (!recording || !wasRecording) refresh()
    }

    init {
        ContextCompat.registerReceiver(application, recordingReceiver, IntentFilter().apply {
            addAction(RecordingService.ACTION_STATE); addAction(RecordingService.ACTION_SEGMENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        RecordingService.snapshot()?.let { recordingReceiver.onReceive(application, it) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                store.list().filter { it.state == "recording" && it.id != _state.value.activeMeetingId }.forEach { meeting ->
                    runCatching {
                        val duration = WavFileWriter.recover(File(meeting.audioPath), 48_000)
                        store.update(meeting.id) { it.copy(state = "interrupted", durationMs = duration, stats = it.stats.copy(audioSeconds = duration / 1_000)) }
                    }
                }
            }
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val meetings = withContext(Dispatchers.IO) { store.list() }
            val selected = _state.value.selectedMeetingId?.takeIf { id -> meetings.any { it.id == id } } ?: meetings.firstOrNull()?.id
            _state.value = _state.value.copy(meetings = meetings, selectedMeetingId = selected)
        }
    }

    fun reserveRecordingStart(): Boolean {
        if (_state.value.isRecording || _state.value.isStarting || _state.value.isStopping || _state.value.isSavingSettings || _state.value.workingMeetingId != null) return false
        startReserved = true
        _state.value = _state.value.copy(isStarting = true, message = null)
        return true
    }

    fun cancelRecordingStart(message: String) {
        if (startReserved) {
            startReserved = false
            _state.value = _state.value.copy(isStarting = false, message = message)
        }
    }

    fun startRecording(resultCode: Int, resultData: Intent?, microphone: Boolean, playback: Boolean) {
        if (!startReserved && !reserveRecordingStart()) return
        startReserved = false
        val context = getApplication<Application>()
        val intent = Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_START)
            .putExtra(RecordingService.EXTRA_MICROPHONE, microphone).putExtra(RecordingService.EXTRA_PLAYBACK, playback)
            .putExtra(RecordingService.EXTRA_RESULT_CODE, resultCode)
        if (resultCode == Activity.RESULT_OK && resultData != null) intent.putExtra(RecordingService.EXTRA_RESULT_DATA, resultData)
        _state.value = _state.value.copy(isStarting = true, message = null, recordedDurationMs = 0)
        runCatching { ContextCompat.startForegroundService(context, intent) }.onFailure {
            _state.value = _state.value.copy(isStarting = false, message = it.message)
        }
    }

    fun stopRecording() {
        if (!_state.value.isRecording || _state.value.isStopping) return
        _state.value = _state.value.copy(isStopping = true)
        getApplication<Application>().startService(Intent(getApplication(), RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
    }

    fun togglePause() {
        if (!_state.value.isRecording || _state.value.isStopping) return
        getApplication<Application>().startService(Intent(getApplication(), RecordingService::class.java)
            .setAction(if (_state.value.isPaused) RecordingService.ACTION_RESUME else RecordingService.ACTION_PAUSE))
    }

    fun selectMeeting(id: String) { _state.value = _state.value.copy(selectedMeetingId = id) }
    fun showMessage(text: String) { _state.value = _state.value.copy(message = text) }
    fun clearMessage() { _state.value = _state.value.copy(message = null) }

    fun saveSettings(settings: AppSettings) {
        if (_state.value.isSavingSettings || _state.value.isRecording || _state.value.isStarting || _state.value.isStopping || _state.value.workingMeetingId != null) return
        _state.value = _state.value.copy(isSavingSettings = true)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { secureSettings.save(settings); secureSettings.load() } }
                .onSuccess { if (_settingsDraft.value == settings) _settingsDraft.value = it
                    _state.value = _state.value.copy(settings = it, message = when (it.uiLanguage) { "ja" -> "設定を保存しました"; "en" -> "Settings saved"; else -> "设置已保存" }) }
                .onFailure { showMessage(it.message ?: "Cannot save settings") }
            _state.value = _state.value.copy(isSavingSettings = false)
        }
    }

    private fun cloud(id: String) = CloudClient(getApplication()) { usage -> store.update(id) { it.withUsage(usage) } }

    fun transcribe(meeting: Meeting) = launchMeetingWork(meeting.id) {
        val segments = cloud(meeting.id).transcribeSegments(File(meeting.audioPath), _state.value.settings)
        store.update(meeting.id) { it.copy(transcript = segments.joinToString("\n") { segment -> segment.text },
            summary = "", summaryUpTo = 0, minutes = "", qa = emptyList(), segments = segments,
            stats = it.stats.copy(transcriptChars = segments.sumOf { segment -> segment.text.length })) }
    }

    fun summarize(meeting: Meeting) = launchMeetingWork(meeting.id) {
        val summary = cloud(meeting.id).summarize(meeting.transcript, _state.value.settings)
        store.update(meeting.id) { it.copy(summary = summary, summaryUpTo = meeting.segments.size) }
    }

    fun createMinutes(meeting: Meeting) = launchMeetingWork(meeting.id) {
        val minutes = cloud(meeting.id).createMinutes(meeting, _state.value.settings)
        store.update(meeting.id) { it.copy(minutes = minutes) }
    }

    fun ask(meeting: Meeting, question: String) = launchMeetingWork(meeting.id) {
        val answer = cloud(meeting.id).ask(meeting, question, _state.value.settings)
        store.update(meeting.id) { it.copy(qa = it.qa + QaEntry(question, answer, System.currentTimeMillis())) }
    }

    fun translate(meeting: Meeting) = launchMeetingWork(meeting.id) {
        val settings = _state.value.settings
        require(settings.translateTo.isNotBlank()) { "请先选择翻译语言 / Choose a translation language in Settings" }
        val segments = meeting.segments.ifEmpty { listOf(TranscriptSegment(0, 0, meeting.durationMs, text = meeting.transcript)) }
        require(meeting.transcript.isNotBlank()) { "Transcribe this recording first" }
        val translated = cloud(meeting.id).translateSegments(segments, settings.translateTo, settings)
        store.update(meeting.id) { it.copy(translateTo = settings.translateTo, segments = segments.mapIndexed { i, segment -> segment.copy(translated = translated[i]) }) }
    }

    fun resplitSpeakers(meeting: Meeting) = launchMeetingWork(meeting.id) {
        val labels = cloud(meeting.id).resplitSpeakers(meeting.segments, _state.value.settings)
        store.update(meeting.id) { it.copy(segments = it.segments.map { segment -> segment.copy(speaker = labels.getValue(segment.id)) }) }
    }

    fun renameSpeaker(meeting: Meeting, original: String, name: String) = launchMeetingWork(meeting.id) {
        require(name.isNotBlank()) { "Speaker name cannot be blank" }
        store.update(meeting.id) { it.copy(segments = it.segments.map { segment -> if (segment.speaker == original) segment.copy(speaker = name.trim()) else segment }) }
    }

    fun renameMeeting(meeting: Meeting, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { store.update(meeting.id) { it.copy(title = title.trim()) } } }
                .onFailure { showMessage(it.message.orEmpty()) }
            refresh()
        }
    }

    fun deleteMeeting(meeting: Meeting) = launchMeetingWork(meeting.id) {
        check(store.delete(meeting.id)) { "Cannot delete meeting" }
    }

    fun importMedia(uri: Uri, transcribe: Boolean, minutes: Boolean) = launchMeetingWork("import") {
        val context = getApplication<Application>()
        val title = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()?.substringBeforeLast('.')?.take(120) ?: "Imported recording"
        val (id, directory) = store.createDirectory()
        val audio = File(directory, "recording.wav")
        val duration = try { MediaImporter.decode(context, uri, audio) } catch (error: Exception) {
            directory.deleteRecursively(); throw error
        } finally {
            runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        var meeting = Meeting(id, title, System.currentTimeMillis(), duration, audio.absolutePath, false, false,
            language = _state.value.settings.asrLanguage, stats = MeetingStats(audioSeconds = duration / 1_000))
        store.save(meeting)
        // Selection is updated on the main dispatcher; all file/model work remains off the UI thread.
        withContext(Dispatchers.Main) { _state.value = _state.value.copy(selectedMeetingId = id, workingMeetingId = id) }
        if (transcribe) {
            val segments = cloud(id).transcribeSegments(audio, _state.value.settings)
            meeting = store.update(id) { it.copy(segments = segments, transcript = segments.joinToString("\n") { s -> s.text }, stats = it.stats.copy(transcriptChars = segments.sumOf { s -> s.text.length })) }
        }
        if (minutes && meeting.transcript.isNotBlank()) {
            val result = cloud(id).createMinutes(meeting, _state.value.settings)
            store.update(id) { it.copy(minutes = result) }
        }
    }

    fun exportMeeting(meeting: Meeting, uri: Uri, format: ExportFormat) = launchMeetingWork(meeting.id) {
        getApplication<Application>().contentResolver.openOutputStream(uri, "wt").use { output ->
            requireNotNull(output) { "Cannot open export destination" }
            if (format == ExportFormat.Audio) File(meeting.audioPath).inputStream().use { it.copyTo(output) }
            else output.write(MeetingExport.text(meeting, format).toByteArray(Charsets.UTF_8))
        }
    }

    private fun launchMeetingWork(id: String, block: suspend () -> Unit) {
        if (_state.value.workingMeetingId != null || _state.value.isRecording || _state.value.isStarting || _state.value.isStopping || _state.value.isSavingSettings) return
        _state.value = _state.value.copy(workingMeetingId = id, message = null)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onFailure { showMessage(it.message ?: it.javaClass.simpleName) }
            val meetings = withContext(Dispatchers.IO) { store.list() }
            _state.value = _state.value.copy(meetings = meetings, workingMeetingId = null)
        }
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().unregisterReceiver(recordingReceiver) }
        super.onCleared()
    }
}

private class RecordingStateReceiver(private val receive: (Intent?) -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) { receive(intent) }
}
