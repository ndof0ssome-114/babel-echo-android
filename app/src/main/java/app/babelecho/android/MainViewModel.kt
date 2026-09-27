package app.babelecho.android

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.babelecho.android.cloud.CloudClient
import app.babelecho.android.data.AppSettings
import app.babelecho.android.data.Meeting
import app.babelecho.android.data.MeetingStore
import app.babelecho.android.data.QaEntry
import app.babelecho.android.data.TranscriptSegment
import app.babelecho.android.data.SecureSettings
import app.babelecho.android.recording.RecordingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class MainUiState(
    val meetings: List<Meeting> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val audioLevel: Float = 0f,
    val selectedMeetingId: String? = null,
    val workingMeetingId: String? = null,
    val message: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = MeetingStore(application)
    private val secureSettings = SecureSettings(application)
    private val cloud = CloudClient(application)
    private val _state = MutableStateFlow(MainUiState(settings = secureSettings.load()))
    val state = _state.asStateFlow()

    private val recordingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == RecordingService.ACTION_SEGMENT) {
                refresh()
                return
            }
            if (intent?.action != RecordingService.ACTION_STATE) return
            val recording = intent.getBooleanExtra(RecordingService.EXTRA_IS_RECORDING, false)
            val wasRecording = _state.value.isRecording
            val paused = intent.getBooleanExtra(RecordingService.EXTRA_IS_PAUSED, false)
            val level = if (intent.hasExtra(RecordingService.EXTRA_LEVEL)) intent.getFloatExtra(RecordingService.EXTRA_LEVEL, 0f) else _state.value.audioLevel
            val error = intent.getStringExtra(RecordingService.EXTRA_ERROR)
            _state.value = _state.value.copy(isRecording = recording, isPaused = paused, audioLevel = if (recording) level else 0f, message = error)
            if (!recording || (!wasRecording && recording)) refresh()
            if (wasRecording && !recording && finalizeAfterStop) {
                finalizeAfterStop = false
                finalizeLatestMeeting()
            }
        }
    }
    private var finalizeAfterStop = false

    init {
        ContextCompat.registerReceiver(
            application,
            recordingReceiver,
            IntentFilter().apply {
                addAction(RecordingService.ACTION_STATE)
                addAction(RecordingService.ACTION_SEGMENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val meetings = withContext(Dispatchers.IO) { store.list() }
            val selected = _state.value.selectedMeetingId?.takeIf { id -> meetings.any { it.id == id } } ?: meetings.firstOrNull()?.id
            _state.value = _state.value.copy(meetings = meetings, selectedMeetingId = selected)
        }
    }

    fun startRecording(resultCode: Int, resultData: Intent?, microphone: Boolean, playback: Boolean) {
        val context = getApplication<Application>()
        val intent = Intent(context, RecordingService::class.java)
            .setAction(RecordingService.ACTION_START)
            .putExtra(RecordingService.EXTRA_MICROPHONE, microphone)
            .putExtra(RecordingService.EXTRA_PLAYBACK, playback)
            .putExtra(RecordingService.EXTRA_RESULT_CODE, resultCode)
        if (resultCode == Activity.RESULT_OK && resultData != null) {
            intent.putExtra(RecordingService.EXTRA_RESULT_DATA, resultData)
        }
        _state.value = _state.value.copy(message = null)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopRecording() {
        finalizeAfterStop = true
        val context = getApplication<Application>()
        context.startService(
            Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP),
        )
    }

    fun togglePause() {
        val context = getApplication<Application>()
        val action = if (_state.value.isPaused) RecordingService.ACTION_RESUME else RecordingService.ACTION_PAUSE
        context.startService(Intent(context, RecordingService::class.java).setAction(action))
    }

    fun selectMeeting(id: String) {
        _state.value = _state.value.copy(selectedMeetingId = id)
    }

    fun saveSettings(settings: AppSettings) {
        runCatching { secureSettings.save(settings) }
            .onSuccess { _state.value = _state.value.copy(settings = secureSettings.load(), message = "设置已保存") }
            .onFailure { _state.value = _state.value.copy(message = it.message) }
    }

    fun transcribe(meeting: Meeting) {
        launchMeetingWork(meeting.id) {
            val transcript = cloud.transcribe(File(meeting.audioPath), _state.value.settings)
            store.update(meeting.id) {
                it.copy(
                    transcript = transcript,
                    summary = "",
                    minutes = "",
                    segments = listOf(TranscriptSegment(0, 0, it.durationMs, text = transcript)),
                    stats = it.stats.copy(asrCalls = it.stats.asrCalls + 1, transcriptChars = transcript.length),
                )
            }
        }
    }

    fun summarize(meeting: Meeting) {
        launchMeetingWork(meeting.id) {
            val summary = cloud.summarize(meeting.transcript, _state.value.settings)
            store.update(meeting.id) { it.copy(summary = summary, stats = it.stats.copy(llmCalls = it.stats.llmCalls + 1)) }
        }
    }

    fun createMinutes(meeting: Meeting) {
        launchMeetingWork(meeting.id) {
            val minutes = cloud.createMinutes(meeting, _state.value.settings)
            store.update(meeting.id) { it.copy(minutes = minutes, stats = it.stats.copy(llmCalls = it.stats.llmCalls + 1)) }
        }
    }

    fun ask(meeting: Meeting, question: String) {
        launchMeetingWork(meeting.id) {
            val answer = cloud.ask(meeting, question, _state.value.settings)
            store.update(meeting.id) {
                it.copy(
                    qa = it.qa + QaEntry(question, answer, System.currentTimeMillis()),
                    stats = it.stats.copy(llmCalls = it.stats.llmCalls + 1),
                )
            }
        }
    }

    fun translate(meeting: Meeting) {
        val target = _state.value.settings.translateTo
        if (target.isBlank()) {
            _state.value = _state.value.copy(message = "请先在设置中选择翻译语言")
            return
        }
        launchMeetingWork(meeting.id) {
            val segments = meeting.segments.ifEmpty {
                if (meeting.transcript.isBlank()) emptyList() else listOf(TranscriptSegment(0, 0, meeting.durationMs, text = meeting.transcript))
            }
            val translated = cloud.translateSegments(segments, target, _state.value.settings)
            store.update(meeting.id) {
                it.copy(
                    translateTo = target,
                    segments = segments.mapIndexed { index, segment -> segment.copy(translated = translated.getOrElse(index) { "" }) },
                    stats = it.stats.copy(llmCalls = it.stats.llmCalls + 1),
                )
            }
        }
    }

    fun renameMeeting(meeting: Meeting, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            store.update(meeting.id) { it.copy(title = title.trim()) }
            refresh()
        }
    }

    fun deleteMeeting(meeting: Meeting) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(meeting.id) }
            refresh()
        }
    }

    private fun finalizeLatestMeeting() {
        viewModelScope.launch {
            val meeting = withContext(Dispatchers.IO) { store.list().firstOrNull() } ?: return@launch
            _state.value = _state.value.copy(selectedMeetingId = meeting.id, workingMeetingId = meeting.id)
            runCatching {
                var current = meeting
                if (current.transcript.isBlank()) {
                    val transcript = cloud.transcribe(File(current.audioPath), _state.value.settings)
                    current = store.update(current.id) {
                        it.copy(
                            transcript = transcript,
                            segments = listOf(TranscriptSegment(0, 0, it.durationMs, text = transcript)),
                            stats = it.stats.copy(asrCalls = it.stats.asrCalls + 1, transcriptChars = transcript.length),
                        )
                    }
                }
                if (current.summary.isBlank()) {
                    val summary = cloud.summarize(current.transcript, _state.value.settings)
                    current = store.update(current.id) { it.copy(summary = summary, stats = it.stats.copy(llmCalls = it.stats.llmCalls + 1)) }
                }
                val minutes = cloud.createMinutes(current, _state.value.settings)
                store.update(current.id) { it.copy(minutes = minutes, stats = it.stats.copy(llmCalls = it.stats.llmCalls + 1)) }
            }.onFailure { _state.value = _state.value.copy(message = it.message ?: it.javaClass.simpleName) }
            val meetings = withContext(Dispatchers.IO) { store.list() }
            _state.value = _state.value.copy(meetings = meetings, workingMeetingId = null)
        }
    }

    private fun launchMeetingWork(id: String, block: suspend () -> Unit) {
        if (_state.value.workingMeetingId != null) return
        _state.value = _state.value.copy(workingMeetingId = id, message = null)
        viewModelScope.launch {
            runCatching { block() }
                .onFailure { _state.value = _state.value.copy(message = it.message ?: it.javaClass.simpleName) }
            val meetings = withContext(Dispatchers.IO) { store.list() }
            _state.value = _state.value.copy(meetings = meetings, workingMeetingId = null)
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().unregisterReceiver(recordingReceiver) }
        super.onCleared()
    }
}
