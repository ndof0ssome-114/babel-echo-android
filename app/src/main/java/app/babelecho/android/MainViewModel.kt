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
            if (intent?.action != RecordingService.ACTION_STATE) return
            val recording = intent.getBooleanExtra(RecordingService.EXTRA_IS_RECORDING, false)
            val error = intent.getStringExtra(RecordingService.EXTRA_ERROR)
            _state.value = _state.value.copy(isRecording = recording, message = error)
            if (!recording) refresh()
        }
    }

    init {
        ContextCompat.registerReceiver(
            application,
            recordingReceiver,
            IntentFilter(RecordingService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val meetings = withContext(Dispatchers.IO) { store.list() }
            _state.value = _state.value.copy(meetings = meetings)
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
        val context = getApplication<Application>()
        context.startService(
            Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP),
        )
    }

    fun saveSettings(settings: AppSettings) {
        runCatching { secureSettings.save(settings) }
            .onSuccess { _state.value = _state.value.copy(settings = secureSettings.load(), message = "设置已保存") }
            .onFailure { _state.value = _state.value.copy(message = it.message) }
    }

    fun transcribe(meeting: Meeting) {
        launchMeetingWork(meeting.id) {
            val transcript = cloud.transcribe(File(meeting.audioPath), _state.value.settings)
            store.update(meeting.id) { it.copy(transcript = transcript, summary = "") }
        }
    }

    fun summarize(meeting: Meeting) {
        launchMeetingWork(meeting.id) {
            val summary = cloud.summarize(meeting.transcript, _state.value.settings)
            store.update(meeting.id) { it.copy(summary = summary) }
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
