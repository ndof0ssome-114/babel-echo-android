package app.babelecho.android

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.babelecho.android.cloud.WavChunker
import app.babelecho.android.data.*
import app.babelecho.android.recording.MediaImporter
import app.babelecho.android.recording.WavFileWriter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteOrder
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class DeviceRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun stereoWavImportConvertsToPlayableMono16k() {
        val source = File.createTempFile("stereo", ".wav", context.cacheDir)
        val output = File.createTempFile("imported", ".wav", context.cacheDir)
        try {
            WavFileWriter(source, 44_100, channels = 2).use { writer ->
                writer.write(ShortArray(44_100 * 2) { i -> (sin((i / 2) * .07) * if (i % 2 == 0) 8000 else 4000).toInt().toShort() })
            }
            val duration = MediaImporter.decode(context, Uri.fromFile(source), output)
            assertEquals(1_000L, duration)
            assertEquals(1_000L, WavChunker.durationMs(output))
            android.media.MediaPlayer().apply {
                try { setDataSource(output.absolutePath); prepare(); assertTrue(this.duration in 990..1010); seekTo(500) }
                finally { release() }
            }
            assertTrue(source.exists())
        } finally { source.delete(); output.delete() }
    }

    @Test fun aacMediaImportDecodesAudioTrackLocally() {
        val source = File.createTempFile("aac", ".mp4", context.cacheDir)
        val output = File.createTempFile("aac-imported", ".wav", context.cacheDir)
        try {
            createAacFixture(source)
            val duration = MediaImporter.decode(context, Uri.fromFile(source), output)
            assertTrue("AAC duration $duration", duration in 950..1200)
            assertTrue(output.length() > 30_000)
            assertTrue(source.exists())
        } finally { source.delete(); output.delete() }
    }

    @Test fun legacyAndProfileKeysCanBeClearedAndRemainCleared() {
        val settings = SecureSettings(context)
        val original = settings.load()
        try {
            // Synthetic test values never leave this emulator and are not provider credentials.
            val value = original.copy(asrApiKey = "fixture-only", llmApiKey = "fixture-only",
                asrProfiles = original.asrProfiles.map { it.copy(apiKey = "fixture-only") },
                textProfiles = original.textProfiles.map { it.copy(apiKey = "fixture-only") })
            settings.save(value)
            assertEquals("fixture-only", SecureSettings(context).load().asrApiKey)
            settings.save(value.copy(asrApiKey = "", llmApiKey = "",
                asrProfiles = value.asrProfiles.map { it.copy(apiKey = "") }, textProfiles = value.textProfiles.map { it.copy(apiKey = "") }))
            val loaded = SecureSettings(context).load()
            assertTrue(loaded.asrApiKey.isBlank() && loaded.llmApiKey.isBlank())
            assertTrue(loaded.asrProfiles.all { it.apiKey.isBlank() } && loaded.textProfiles.all { it.apiKey.isBlank() })
        } finally { settings.save(original) }
    }

    @Test fun concurrentMeetingUpdatesPreserveEveryChange() {
        val store = MeetingStore(context)
        val (id, directory) = store.createDirectory()
        try {
            store.save(Meeting(id, "Synthetic storage check", 0, 0, File(directory, "recording.wav").absolutePath, false, false))
            val threads = (0 until 4).map { Thread { repeat(20) { store.update(id) { it.copy(stats = it.stats.copy(asrCalls = it.stats.asrCalls + 1)) } } }.apply { start() } }
            threads.forEach { it.join() }
            assertEquals(80, store.get(id)!!.stats.asrCalls)
            assertTrue(store.delete(id))
            assertNull(store.get(id))
        } finally { store.delete(id) }
    }

    @Test fun thinkingAndUtteranceSettingsPersistWithoutDroppingKeys() {
        val settings = SecureSettings(context)
        val original = settings.load()
        try {
            val edited = original.copy(liveUtterances = true, vadThreshold = 0.002, llmApiKey = "fixture-only", llmNoAuth = true,
                textThinking = original.textThinking + ("minutes" to "enabled"),
                textRoles = original.textRoles + ("summary" to TextRole("deepseek", "typed-model", "disabled")))
            settings.save(edited)
            val restored = SecureSettings(context).load()
            assertTrue(restored.liveUtterances); assertEquals(0.002, restored.vadThreshold, 0.0)
            assertEquals("fixture-only", restored.llmApiKey); assertTrue(restored.llmNoAuth)
            assertEquals("enabled", restored.textThinking["minutes"])
            assertEquals(TextRole("deepseek", "typed-model", "disabled"), restored.textRoles["summary"])
        } finally { settings.save(original) }
    }

    @Test fun settingsDraftSurvivesActivityRecreationAndRecordingPermissionReservationCannotDuplicate() {
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { activity ->
            var original = AppSettings()
            activity.onActivity {
                val vm = androidx.lifecycle.ViewModelProvider(it)[MainViewModel::class.java]
                original = vm.settingsDraft.value
                vm.editSettings(original.copy(llmModel = "unsaved-draft-model"))
                assertTrue(vm.reserveRecordingStart())
                assertFalse(vm.reserveRecordingStart())
                vm.cancelRecordingStart("Synthetic cancellation")
                assertFalse(vm.state.value.isStarting)
            }
            activity.recreate()
            activity.onActivity {
                val vm = androidx.lifecycle.ViewModelProvider(it)[MainViewModel::class.java]
                assertEquals("unsaved-draft-model", vm.settingsDraft.value.llmModel)
                assertEquals(original, vm.state.value.settings)
                vm.editSettings(original)
            }
        }
    }

    @Test fun customProvidersLanguagesRoutesAndEncryptedKeysPersistAndDeletionClearsKeys() {
        val store = SecureSettings(context)
        val original = store.load()
        try {
            val asr = AsrProfile("fixture-custom-asr", "openai", "https://fixture.example/v1", "custom-whisper", true, "fixture-custom-key", false, setOf("ko", "ja"), "Custom ASR")
            val text = TextProfile("fixture-custom-text", "https://fixture.example/text/v1", true, "fixture-text-key", false, "Custom text")
            val configured = original.copy(asrProfiles = original.asrProfiles + asr, textProfiles = original.textProfiles + text,
                asrRoutes = original.asrRoutes + ("ko" to asr.id), textRoles = original.textRoles + ("minutes" to TextRole(text.id, "custom-minutes")))
            store.save(configured)
            val loaded = SecureSettings(context).load()
            assertEquals(asr, loaded.asrProfiles.first { it.id == asr.id }); assertEquals(text, loaded.textProfiles.first { it.id == text.id })
            assertEquals(asr.id, loaded.asrRoutes["ko"]); assertEquals("custom-minutes", loaded.forText("minutes", "").second)
            val prefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            assertFalse(prefs.getString("asr_profiles", "")!!.contains(asr.apiKey))
            assertNotEquals(asr.apiKey, prefs.getString("asr_profile_${asr.id}_key", ""))
            store.save(original)
            assertFalse(prefs.contains("asr_profile_${asr.id}_key")); assertFalse(prefs.contains("text_profile_${text.id}_key"))
            assertTrue(SecureSettings(context).load().asrProfiles.none { it.id == asr.id })
        } finally { store.save(original) }
    }

    private fun createAacFixture(output: File) {
        val rate = 32_000
        val encoder = MediaCodec.createEncoderByType("audio/mp4a-latm")
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        try {
            val format = MediaFormat.createAudioFormat("audio/mp4a-latm", rate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val info = MediaCodec.BufferInfo()
            var frame = 0
            var inputDone = false
            var outputDone = false
            var track = -1
            val deadline = System.nanoTime() + 30_000_000_000L
            while (!outputDone) {
                check(System.nanoTime() < deadline)
                if (!inputDone) {
                    val index = encoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = encoder.getInputBuffer(index)!!.apply { clear(); order(ByteOrder.LITTLE_ENDIAN) }
                        val count = minOf(1024, rate - frame)
                        repeat(count) { buffer.putShort((sin((frame + it) * .1) * 5000).toInt().toShort()) }
                        encoder.queueInputBuffer(index, 0, count * 2, frame * 1_000_000L / rate, if (count == 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        frame += count
                        inputDone = count == 0
                    }
                }
                when (val index = encoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(encoder.outputFormat); muxer.start(); muxerStarted = true }
                    else -> if (index >= 0) {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val buffer = encoder.getOutputBuffer(index)!!
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        encoder.releaseOutputBuffer(index, false)
                    }
                }
            }
        } finally {
            runCatching { encoder.stop() }; encoder.release()
            if (muxerStarted) muxer.stop()
            muxer.release()
        }
    }
}
