package app.babelecho.android.recording

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.babelecho.android.MainActivity
import app.babelecho.android.R
import app.babelecho.android.cloud.CloudClient
import app.babelecho.android.data.Meeting
import app.babelecho.android.data.MeetingStats
import app.babelecho.android.data.MeetingStore
import app.babelecho.android.data.SecureSettings
import app.babelecho.android.data.TranscriptSegment
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max
import kotlinx.coroutines.runBlocking

class RecordingService : Service() {
    private val running = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val records = mutableListOf<AudioRecord>()
    private val readers = mutableListOf<Thread>()
    private var mixer: Thread? = null
    private var liveTranscriber: Thread? = null
    private val liveQueue = ArrayBlockingQueue<ShortArray>(1_800)
    private var mediaProjection: MediaProjection? = null
    private var wavWriter: WavFileWriter? = null
    private var meetingId = ""
    private var outputFile: File? = null
    private var startedAt = 0L
    private var useMicrophone = true
    private var usePlayback = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> thread(name = "babel-stop") { stopCapture(save = true) }
            ACTION_PAUSE -> {
                paused.set(true)
                liveQueue.clear()
                broadcastState(true)
            }
            ACTION_RESUME -> {
                paused.set(false)
                broadcastState(true)
            }
            ACTION_START -> startCapture(intent)
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        if (!running.compareAndSet(false, true)) return
        stopping.set(false)
        useMicrophone = intent.getBooleanExtra(EXTRA_MICROPHONE, true)
        usePlayback = intent.getBooleanExtra(EXTRA_PLAYBACK, true)
        startForegroundCompat(buildNotification())

        try {
            check(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                "Microphone permission is required"
            }
            val (id, directory) = MeetingStore(this).createDirectory()
            meetingId = id
            outputFile = File(directory, "recording.wav")
            wavWriter = WavFileWriter(outputFile!!, SAMPLE_RATE)
            startedAt = System.currentTimeMillis()
            val settings = SecureSettings(this).load()
            MeetingStore(this).save(
                Meeting(
                    id = meetingId,
                    title = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(startedAt)),
                    createdAt = startedAt,
                    durationMs = 0,
                    audioPath = outputFile!!.absolutePath,
                    usedMicrophone = useMicrophone,
                    usedPlayback = usePlayback,
                    language = settings.asrLanguage,
                    translateTo = settings.translateTo,
                    state = "recording",
                ),
            )

            if (usePlayback) {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                check(resultCode != 0 && resultData != null) { "System audio permission was not granted" }
                val manager = getSystemService(MediaProjectionManager::class.java)
                val projection = checkNotNull(manager.getMediaProjection(resultCode, resultData)) {
                    "Cannot start system audio capture"
                }
                projection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        thread(name = "babel-projection-stop") { stopCapture(save = true) }
                    }
                }, null)
                mediaProjection = projection
            }

            val micRecord = if (useMicrophone) createMicrophoneRecord() else null
            val playbackRecord = if (usePlayback) createPlaybackRecord(mediaProjection!!) else null
            records += listOfNotNull(micRecord, playbackRecord)
            check(records.isNotEmpty()) { "Select at least one audio source" }
            records.forEach { it.startRecording() }

            val micQueue = micRecord?.let { ArrayBlockingQueue<ShortArray>(12) }
            val playbackQueue = playbackRecord?.let { ArrayBlockingQueue<ShortArray>(12) }
            if (micRecord != null) readers += startReader("babel-microphone", micRecord, micQueue!!)
            if (playbackRecord != null) readers += startReader("babel-playback", playbackRecord, playbackQueue!!)
            if (settings.asrNoAuth || settings.asrApiKey.isNotBlank()) liveTranscriber = startLiveTranscriber(settings)
            mixer = startMixer(micQueue, playbackQueue)
            broadcastState(true)
        } catch (error: Throwable) {
            broadcastState(false, error.message ?: error.javaClass.simpleName)
            stopCapture(save = false)
        }
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked before either AudioRecord is created.
    private fun createMicrophoneRecord(): AudioRecord {
        val format = audioFormat()
        val bufferSize = max(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            FRAME_SAMPLES * 4,
        )
        return AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .build()
            .also { check(it.state == AudioRecord.STATE_INITIALIZED) { "Cannot initialize microphone" } }
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked before either AudioRecord is created.
    private fun createPlaybackRecord(projection: MediaProjection): AudioRecord {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val bufferSize = max(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            FRAME_SAMPLES * 4,
        )
        return AudioRecord.Builder()
            .setAudioFormat(audioFormat())
            .setAudioPlaybackCaptureConfig(config)
            .setBufferSizeInBytes(bufferSize)
            .build()
            .also { check(it.state == AudioRecord.STATE_INITIALIZED) { "Cannot initialize system audio capture" } }
    }

    private fun audioFormat() = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(SAMPLE_RATE)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .build()

    private fun startReader(name: String, record: AudioRecord, queue: ArrayBlockingQueue<ShortArray>) =
        thread(name = name) {
            val buffer = ShortArray(FRAME_SAMPLES)
            while (running.get()) {
                val count = runCatching { record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) }.getOrDefault(0)
                if (count > 0) {
                    val chunk = buffer.copyOf(count)
                    if (!queue.offer(chunk)) {
                        queue.poll()
                        queue.offer(chunk)
                    }
                }
            }
        }

    private fun startMixer(
        micQueue: ArrayBlockingQueue<ShortArray>?,
        playbackQueue: ArrayBlockingQueue<ShortArray>?,
    ) = thread(name = "babel-mixer") {
        var meterFrames = 0
        while (running.get() || micQueue?.isNotEmpty() == true || playbackQueue?.isNotEmpty() == true) {
            val mic = micQueue?.poll(100, TimeUnit.MILLISECONDS)
            val playback = playbackQueue?.poll(100, TimeUnit.MILLISECONDS)
            if (mic == null && playback == null) continue
            val size = max(mic?.size ?: 0, playback?.size ?: 0)
            val mixed = ShortArray(size) { index ->
                val a = mic?.getOrNull(index)?.toInt() ?: 0
                val b = playback?.getOrNull(index)?.toInt() ?: 0
                (a + b).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            if (paused.get()) continue
            wavWriter?.write(mixed)
            if (liveTranscriber != null && !liveQueue.offer(mixed.copyOf())) {
                liveQueue.poll()
                liveQueue.offer(mixed.copyOf())
            }
            if (++meterFrames % 5 == 0) {
                val level = if (mixed.isEmpty()) 0f else mixed.sumOf { kotlin.math.abs(it.toInt()).toLong() }.toFloat() / mixed.size / 32768f
                broadcastState(true, level = level.coerceIn(0f, 1f))
            }
        }
    }

    private fun startLiveTranscriber(settings: app.babelecho.android.data.AppSettings) = thread(name = "babel-live-asr") {
        val cloud = CloudClient(this)
        val store = MeetingStore(this)
        val accumulator = PcmAccumulator()
        val windowSamples = settings.chunkSeconds * SAMPLE_RATE
        val overlapSamples = settings.overlapSeconds * SAMPLE_RATE
        var windowStartSamples = 0L
        var previousTail = ""
        var lastSummaryAt = System.currentTimeMillis()
        var summarizedSegments = 0

        fun process(samples: ShortArray) {
            val temp = File(cacheDir, "live-$meetingId-${System.nanoTime()}.wav")
            try {
                WavFileWriter(temp, SAMPLE_RATE).use { it.write(samples) }
                val text = runBlocking { cloud.transcribeChunk(temp, settings, previousTail) }.trim()
                if (text.isBlank()) return
                val startMs = windowStartSamples * 1_000 / SAMPLE_RATE
                val endMs = startMs + samples.size * 1_000L / SAMPLE_RATE
                val id = store.list().firstOrNull { it.id == meetingId }?.segments?.size ?: 0
                var segment = TranscriptSegment(id, startMs, endMs, text = text)
                if (settings.translateTo.isNotBlank()) {
                    val translated = runCatching {
                        runBlocking { cloud.translateSegments(listOf(segment), settings.translateTo, settings) }.firstOrNull().orEmpty()
                    }.getOrDefault("")
                    segment = segment.copy(translated = translated)
                }
                store.update(meetingId) { meeting ->
                    val updated = meeting.segments + segment
                    meeting.copy(
                        transcript = updated.joinToString("\n") { it.text },
                        segments = updated,
                        stats = meeting.stats.copy(
                            asrCalls = meeting.stats.asrCalls + 1,
                            transcriptChars = updated.sumOf { it.text.length },
                        ),
                    )
                }
                previousTail = text.takeLast(240)
                sendBroadcast(Intent(ACTION_SEGMENT).setPackage(packageName).putExtra(EXTRA_MEETING_ID, meetingId))

                val current = store.list().firstOrNull { it.id == meetingId } ?: return
                val due = settings.autoSummarySeconds > 0 &&
                    System.currentTimeMillis() - lastSummaryAt >= settings.autoSummarySeconds * 1_000L &&
                    current.segments.size > summarizedSegments
                if (due) {
                    val newSegments = current.segments.drop(summarizedSegments)
                    val summary = runBlocking { cloud.updateSummary(current.summary, newSegments, settings) }
                    store.update(meetingId) { meeting ->
                        meeting.copy(summary = summary, stats = meeting.stats.copy(llmCalls = meeting.stats.llmCalls + 1))
                    }
                    summarizedSegments = current.segments.size
                    lastSummaryAt = System.currentTimeMillis()
                    sendBroadcast(Intent(ACTION_SEGMENT).setPackage(packageName).putExtra(EXTRA_MEETING_ID, meetingId))
                }
            } catch (error: Throwable) {
                broadcastState(true, "实时转写失败：${error.message ?: error.javaClass.simpleName}")
            } finally {
                temp.delete()
            }
        }

        while (running.get() || liveQueue.isNotEmpty()) {
            val chunk = liveQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            accumulator.add(chunk)
            while (accumulator.size >= windowSamples) {
                process(accumulator.take(windowSamples, overlapSamples))
                windowStartSamples += (windowSamples - overlapSamples).toLong()
            }
        }
        if (accumulator.size >= SAMPLE_RATE) process(accumulator.take(accumulator.size, 0))
    }

    private fun stopCapture(save: Boolean) {
        if (!stopping.compareAndSet(false, true)) return
        running.set(false)
        records.forEach { runCatching { it.stop() } }
        readers.forEach { runCatching { it.join(1_000) } }
        mixer?.let { runCatching { it.join(1_500) } }
        liveTranscriber?.let { runCatching { it.join(30_000) } }
        records.forEach { runCatching { it.release() } }
        records.clear()
        readers.clear()
        runCatching { wavWriter?.close() }
        wavWriter = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null

        val file = outputFile
        if (save && file != null && file.exists() && file.length() > 44) {
            val createdAt = startedAt
            val duration = (System.currentTimeMillis() - createdAt).coerceAtLeast(0)
            MeetingStore(this).update(meetingId) { meeting ->
                meeting.copy(
                    durationMs = duration,
                    audioPath = file.absolutePath,
                    state = "stopped",
                    stats = meeting.stats.copy(audioSeconds = duration / 1_000),
                )
            }
        } else {
            file?.parentFile?.deleteRecursively()
        }
        broadcastState(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 30) {
            var types = 0
            if (useMicrophone) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (usePlayback) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(NOTIFICATION_ID, notification, types)
        } else if (Build.VERSION.SDK_INT >= 29 && usePlayback) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_recording))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun broadcastState(isRecording: Boolean, error: String? = null, level: Float? = null) {
        val intent = Intent(ACTION_STATE)
            .setPackage(packageName)
            .putExtra(EXTRA_IS_RECORDING, isRecording)
            .putExtra(EXTRA_IS_PAUSED, paused.get())
            .putExtra(EXTRA_ERROR, error)
        if (level != null) intent.putExtra(EXTRA_LEVEL, level)
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        if (running.get()) thread(name = "babel-destroy-stop") { stopCapture(save = true) }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "app.babelecho.android.action.START"
        const val ACTION_STOP = "app.babelecho.android.action.STOP"
        const val ACTION_PAUSE = "app.babelecho.android.action.PAUSE"
        const val ACTION_RESUME = "app.babelecho.android.action.RESUME"
        const val ACTION_STATE = "app.babelecho.android.action.STATE"
        const val ACTION_SEGMENT = "app.babelecho.android.action.SEGMENT"
        const val EXTRA_MICROPHONE = "microphone"
        const val EXTRA_PLAYBACK = "playback"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_IS_RECORDING = "is_recording"
        const val EXTRA_IS_PAUSED = "is_paused"
        const val EXTRA_LEVEL = "level"
        const val EXTRA_MEETING_ID = "meeting_id"
        const val EXTRA_ERROR = "error"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 4101
        private const val SAMPLE_RATE = 48_000
        private const val FRAME_SAMPLES = 960
    }
}

private class PcmAccumulator(initialCapacity: Int = 48_000) {
    private var data = ShortArray(initialCapacity)
    var size: Int = 0
        private set

    fun add(chunk: ShortArray) {
        ensure(size + chunk.size)
        chunk.copyInto(data, size)
        size += chunk.size
    }

    fun take(count: Int, retain: Int): ShortArray {
        val actual = count.coerceAtMost(size)
        val output = data.copyOfRange(0, actual)
        val keepFrom = (actual - retain.coerceAtMost(actual)).coerceAtLeast(0)
        val remaining = size - keepFrom
        data.copyInto(data, 0, keepFrom, size)
        size = remaining
        return output
    }

    private fun ensure(required: Int) {
        if (required <= data.size) return
        data = data.copyOf(maxOf(required, data.size * 2))
    }
}
