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
import app.babelecho.android.withUsage
import app.babelecho.android.MainActivity
import app.babelecho.android.R
import app.babelecho.android.cloud.CloudClient
import app.babelecho.android.data.Meeting
import app.babelecho.android.data.MeetingStats
import app.babelecho.android.data.MeetingStore
import app.babelecho.android.data.SecureSettings
import app.babelecho.android.data.TranscriptSegment
import app.babelecho.android.data.TranscriptAssembler
import app.babelecho.android.data.Sentences
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Timer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.concurrent.timerTask
import kotlin.math.max
import kotlinx.coroutines.runBlocking

class RecordingService : Service() {
    private val running = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val readersDone = AtomicBoolean(false)
    private val captureDrained = AtomicBoolean(false)
    private val recordedSamples = AtomicLong(0)
    @Volatile private var liveCloud: CloudClient? = null
    private val liveEnabled = AtomicBoolean(true)
    @Volatile private var liveText: LiveTextWorker? = null
    @Volatile private var processing = false
    private var captureSettings = app.babelecho.android.data.AppSettings()
    private var lastError: String? = null
    private var lastErrorAt = 0L
    private val records = mutableListOf<AudioRecord>()
    private val readers = mutableListOf<Thread>()
    private var mixer: Thread? = null
    @Volatile private var liveTranscriber: Thread? = null
    private val liveQueue = ArrayBlockingQueue<ShortArray>(1_800)
    @Volatile private var mediaProjection: MediaProjection? = null
    // Guards wavWriter so stop never closes the file under a mixer write that outlived its join.
    private val writerLock = Any()
    @Volatile private var wavWriter: WavFileWriter? = null
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
            ACTION_PAUSE -> if (running.get() && !stopping.get()) {
                paused.set(true)
                if (captureSettings.liveUtterances) liveQueue.offer(ShortArray(0))
                broadcastState(true)
            }
            ACTION_RESUME -> if (running.get() && !stopping.get()) {
                paused.set(false)
                broadcastState(true)
            }
            ACTION_START -> startCapture(intent)
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        if (stopping.get() || !running.compareAndSet(false, true)) return
        stopping.set(false)
        useMicrophone = intent.getBooleanExtra(EXTRA_MICROPHONE, true)
        usePlayback = intent.getBooleanExtra(EXTRA_PLAYBACK, true)
        try {
            startForegroundCompat(buildNotification())
            check(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                "Microphone permission is required"
            }
            val (id, directory) = MeetingStore(this).createDirectory()
            meetingId = id
            outputFile = File(directory, "recording.wav")
            wavWriter = WavFileWriter(outputFile!!, SAMPLE_RATE)
            startedAt = System.currentTimeMillis()
            val settings = SecureSettings(this).load()
            captureSettings = settings
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
                    mode = settings.noteMode,
                    notesDetail = settings.notesDetail,
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

            val frames = ArrayBlockingQueue<PcmFrame>(FRAME_QUEUE_CAPACITY)
            val sources = listOfNotNull(
                micRecord?.let { "babel-microphone" to it },
                playbackRecord?.let { "babel-playback" to it },
            )
            sources.forEachIndexed { index, (name, record) -> readers += startReader(name, index, record, frames) }
            if (settings.liveTranscription && settings.hasAsr) liveTranscriber = startLiveTranscriber(settings)
            mixer = startMixer(frames, sources.size)
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

    private fun startReader(name: String, source: Int, record: AudioRecord, frames: ArrayBlockingQueue<PcmFrame>) =
        thread(name = name) {
            val buffer = ShortArray(FRAME_SAMPLES)
            var position = 0L
            while (running.get()) {
                val count = runCatching { record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) }.getOrDefault(0)
                if (count > 0) {
                    // A full queue drops this frame; its position gap becomes silence in the mixer.
                    frames.offer(PcmFrame(source, position, buffer.copyOf(count)))
                    position += count
                }
            }
        }

    private fun startMixer(frames: ArrayBlockingQueue<PcmFrame>, sourceCount: Int) = thread(name = "babel-mixer") {
        val pcm = PcmMixer(sourceCount, MAX_SOURCE_LAG_SAMPLES)
        val batch = ArrayList<PcmFrame>()
        var meterSamples = 0
        var meterSum = 0L
        var gapReported = false

        fun write(mixed: ShortArray) {
            if (mixed.isEmpty()) return
            synchronized(writerLock) { wavWriter?.write(mixed) ?: return }
            recordedSamples.addAndGet(mixed.size.toLong())
            if (liveTranscriber != null && liveEnabled.get() && !liveQueue.offer(mixed)) {
                // Keep local audio intact and stop live processing rather than creating false timestamps.
                liveCloud?.cancel()
                liveTranscriber?.interrupt()
                liveEnabled.set(false)
                liveQueue.clear()
                broadcastState(true, "实时转写跟不上录音，已停止上传。音频已保留，可结束后重新转写 / Live ASR fell behind; audio is safe for later transcription")
            }
            meterSum += mixed.sumOf { kotlin.math.abs(it.toInt()).toLong() }
            meterSamples += mixed.size
            if (meterSamples >= METER_SAMPLES) {
                broadcastState(true, level = (meterSum.toFloat() / meterSamples / 32768f).coerceIn(0f, 1f))
                meterSamples = 0
                meterSum = 0
            }
        }

        try {
            while (!readersDone.get() || frames.isNotEmpty()) {
                val first = frames.poll(100, TimeUnit.MILLISECONDS) ?: continue
                batch.add(first)
                frames.drainTo(batch)
                batch.forEach(pcm::add)
                batch.clear()
                if (!gapReported && pcm.gapSamples > 0) {
                    gapReported = true
                    broadcastState(true, "录音处理一度跟不上，缺失的片段已用静音补齐 / Capture fell behind briefly; the missing audio was filled with silence")
                }
                if (paused.get()) pcm.discard() else write(pcm.mix())
            }
            if (!paused.get()) write(pcm.mix(flush = true))
        } catch (error: Exception) {
            broadcastState(running.get(), "录音写入失败，正在保存已有音频 / Recording write failed: ${error.message}")
            thread(name = "babel-write-error-stop") { stopCapture(save = true) }
        }
    }

    private fun startLiveTranscriber(settings: app.babelecho.android.data.AppSettings) = thread(name = "babel-live-asr") {
        val store = MeetingStore(this)
        val cloud = CloudClient(this) { usage -> store.update(meetingId) { it.withUsage(usage) } }
        liveCloud = cloud
        val textCloud = CloudClient(this) { usage -> store.update(meetingId) { it.withUsage(usage) } }
        liveText = LiveTextWorker(store, textCloud, settings, meetingId,
            onUpdated = { sendBroadcast(Intent(ACTION_SEGMENT).setPackage(packageName).putExtra(EXTRA_MEETING_ID, meetingId)) },
            onError = { message -> if (running.get()) broadcastState(true, message) })
        val accumulator = PcmAccumulator()
        val utterances = if (settings.liveUtterances) UtteranceSegmenter(SAMPLE_RATE, settings.chunkSeconds,
            settings.overlapSeconds.toDouble(), settings.vadThreshold) else null
        val windowSamples = settings.chunkSeconds * SAMPLE_RATE
        val overlapSamples = settings.overlapSeconds * SAMPLE_RATE
        var windowStartSamples = 0L
        var previousTail = ""
        // Desktop-style sentence assembly across chunks; only finished sentences are translated.
        val assembler = TranscriptAssembler(emptyList())
        var lastChunkEndMs = 0L
        var receivedSamples = 0L
        val staleGraceMs = if (utterances != null) 2_000L else settings.chunkSeconds * 1_000L

        fun process(samples: ShortArray, startSample: Long = windowStartSamples) {
            val temp = File(cacheDir, "live-$meetingId-${System.nanoTime()}.wav")
            try {
                // Speech recognition needs 16 kHz; uploading the 48 kHz capture triples traffic.
                WavFileWriter(temp, UPLOAD_SAMPLE_RATE).use { it.write(downsampleForUpload(samples)) }
                val received = runBlocking { cloud.transcribeResult(temp, settings, previousTail) }
                if (Thread.currentThread().isInterrupted) return
                val startMs = startSample * 1_000 / SAMPLE_RATE
                val endMs = (startSample + samples.size) * 1_000 / SAMPLE_RATE
                // Audio before the previous chunk's end was already recognised there (overlap).
                val overlapUntil = lastChunkEndMs.takeIf { startMs < it }
                lastChunkEndMs = maxOf(lastChunkEndMs, endMs)
                var tail = previousTail
                val changes = received.mapNotNull { item ->
                    val absoluteStart = item.startMs + startMs
                    val absoluteEnd = item.endMs + startMs
                    if (overlapUntil != null && absoluteEnd <= overlapUntil) return@mapNotNull null
                    val clean = if (overlapUntil != null && absoluteStart < overlapUntil) Sentences.trimRepeatedPrefix(tail, item.text) else item.text.trim()
                    if (clean.isBlank()) null else {
                        tail = (tail + "\n" + clean).takeLast(240)
                        assembler.add(clean, maxOf(absoluteStart, overlapUntil ?: absoluteStart), absoluteEnd, item.speaker)
                    }
                }
                if (changes.isEmpty()) return
                commitTranscript(store, changes)
                previousTail = tail
            } catch (error: Exception) {
                if (error is InterruptedException) Thread.currentThread().interrupt()
                if (running.get() && !Thread.currentThread().isInterrupted) broadcastState(true, "实时处理失败 / Live processing failed: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                temp.delete()
            }
        }

        while ((!captureDrained.get() || liveQueue.isNotEmpty()) && !Thread.currentThread().isInterrupted) {
            val chunk = try { liveQueue.poll(200, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break } ?: continue
            receivedSamples += chunk.size
            // Nothing recorded after a long enough pause can continue the open sentence: close it so
            // it is translated now instead of at the end of the meeting.
            assembler.closeIfStale(receivedSamples * 1_000 / SAMPLE_RATE, staleGraceMs)?.let { liveText?.segmentsAdded(listOf(it)) }
            if (utterances != null) {
                val ready = if (chunk.isEmpty()) listOfNotNull(utterances.finish()) else utterances.append(chunk)
                ready.forEach { if (!Thread.currentThread().isInterrupted) process(it.samples, it.startSample) }
                continue
            }
            accumulator.add(chunk)
            while (accumulator.size >= windowSamples) {
                process(accumulator.take(windowSamples, overlapSamples))
                windowStartSamples += (windowSamples - overlapSamples).toLong()
            }
        }
        if (!Thread.currentThread().isInterrupted) {
            if (utterances != null) utterances.finish()?.let { process(it.samples, it.startSample) }
            else if (accumulator.size > overlapSamples) process(accumulator.take(accumulator.size, 0))
        }
        // The recording is over; the last sentence is complete as it stands.
        assembler.closeOpen()?.let { liveText?.segmentsAdded(listOf(it)) }
    }

    /** Merges assembled transcript edits into the stored meeting and queues finished sentences for translation. */
    private fun commitTranscript(store: MeetingStore, changes: List<TranscriptAssembler.Change>) {
        store.update(meetingId) { meeting ->
            val updated = changes.fold(meeting.segments) { segments, change -> change.apply(segments) }
            meeting.copy(transcript = updated.joinToString("\n") { it.text }, segments = updated,
                stats = meeting.stats.copy(transcriptChars = updated.sumOf { it.text.length }))
        }
        sendBroadcast(Intent(ACTION_SEGMENT).setPackage(packageName).putExtra(EXTRA_MEETING_ID, meetingId))
        // Translation and summaries run on their own worker: a slow text model must never
        // hold up recognition until the live audio queue overflows.
        changes.flatMap { it.closedIds }.distinct().takeIf { it.isNotEmpty() }?.let { liveText?.segmentsAdded(it) }
    }

    private fun stopCapture(save: Boolean) {
        if (!stopping.compareAndSet(false, true)) return
        running.set(false)
        records.forEach { runCatching { it.stop() } }
        readers.forEach { runCatching { it.join(1_000) } }
        readersDone.set(true)
        mixer?.let { runCatching { it.join(1_500) } }
        captureDrained.set(true)
        liveTranscriber?.let { worker ->
            runCatching { worker.join(8_000) }
            if (worker.isAlive) {
                worker.interrupt()
                liveCloud?.cancel()
                runCatching { worker.join(2_000) }
            }
        }
        // Let queued translations and the last summary finish before minutes read the meeting.
        liveText?.finish(LIVE_TEXT_DRAIN_MS)
        liveText = null
        records.forEach { runCatching { it.release() } }
        records.clear()
        readers.clear()
        synchronized(writerLock) {
            runCatching { wavWriter?.close() }
            wavWriter = null
        }
        runCatching { mediaProjection?.stop() }
        mediaProjection = null

        val file = outputFile
        if (save && file != null && file.exists() && file.length() > 44) {
            val duration = recordedSamples.get() * 1_000 / SAMPLE_RATE
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
        val settings = captureSettings
        val current = MeetingStore(this).get(meetingId)
        if (save && settings.autoMinutes && settings.hasLlm && current?.transcript?.isNotBlank() == true) {
            processing = true
            broadcastState(false)
            // Capture has ended: say so in the notification instead of still claiming to record.
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(minutes = true))
            val store = MeetingStore(this)
            val cloud = CloudClient(this) { usage -> store.update(meetingId) { it.withUsage(usage) } }
            val timedOut = AtomicBoolean(false)
            val watchdog = Timer("babel-minutes-timeout", true)
            watchdog.schedule(timerTask { timedOut.set(true); cloud.cancel() }, MINUTES_TIMEOUT_MS)
            runCatching {
                val minutes = runBlocking { cloud.createMinutes(current, settings) }
                store.update(meetingId) { it.copy(minutes = minutes) }
            }.onFailure {
                processing = false
                val reason = if (timedOut.get()) "timed out after ${MINUTES_TIMEOUT_MS / 1_000} s" else it.message
                broadcastState(false, "纪要生成失败，录音和转写已保留 / Minutes failed: $reason")
            }
            watchdog.cancel()
            processing = false
            broadcastState(false)
            sendBroadcast(Intent(ACTION_SEGMENT).setPackage(packageName).putExtra(EXTRA_MEETING_ID, meetingId))
        }
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

    private fun buildNotification(minutes: Boolean = false): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if (minutes) R.string.notification_minutes else R.string.notification_recording))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (minutes) return builder.setProgress(0, 0, true).build()
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return builder.addAction(0, getString(R.string.notification_stop), stopIntent).build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun broadcastState(isRecording: Boolean, error: String? = null, level: Float? = null) {
        if (error != null) synchronized(this) {
            val now = System.currentTimeMillis()
            if (error == lastError && now - lastErrorAt < 30_000) return
            lastError = error; lastErrorAt = now
        }
        val intent = Intent(ACTION_STATE)
            .setPackage(packageName)
            .putExtra(EXTRA_IS_RECORDING, isRecording)
            .putExtra(EXTRA_IS_PAUSED, paused.get())
            .putExtra(EXTRA_ERROR, error)
            .putExtra(EXTRA_MEETING_ID, meetingId)
            .putExtra(EXTRA_DURATION, recordedSamples.get() * 1_000 / SAMPLE_RATE)
            .putExtra(EXTRA_PROCESSING, processing)
        if (level != null) intent.putExtra(EXTRA_LEVEL, level)
        latestState = Intent(intent)
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
        @Volatile private var latestState: Intent? = null
        fun snapshot(): Intent? = latestState?.let { Intent(it) }
        const val EXTRA_DURATION = "duration_ms"
        const val EXTRA_PROCESSING = "processing"
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
        // About 2.5 s of both sources in 20 ms frames, so a stalled disk write does not lose audio.
        private const val FRAME_QUEUE_CAPACITY = 250
        private const val MAX_SOURCE_LAG_SAMPLES = SAMPLE_RATE / 4
        private const val METER_SAMPLES = SAMPLE_RATE / 10
        // Long meetings with reasoning models routinely need more than 90 s for minutes.
        private const val MINUTES_TIMEOUT_MS = 240_000L
        private const val LIVE_TEXT_DRAIN_MS = 30_000L
        private const val UPLOAD_SAMPLE_RATE = 16_000

        /**
         * 48 kHz to 16 kHz by averaging each group of three samples. The average is a simple
         * low-pass, so speech above 8 kHz does not fold back into the band ASR listens to.
         */
        internal fun downsampleForUpload(samples: ShortArray): ShortArray {
            val factor = SAMPLE_RATE / UPLOAD_SAMPLE_RATE
            return ShortArray(samples.size / factor) { i ->
                var sum = 0
                for (k in 0 until factor) sum += samples[i * factor + k]
                (sum / factor).toShort()
            }
        }
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
