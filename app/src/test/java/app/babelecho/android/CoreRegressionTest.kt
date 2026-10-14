package app.babelecho.android

import app.babelecho.android.cloud.CloudClient
import app.babelecho.android.cloud.ModelUsage
import app.babelecho.android.cloud.WavChunker
import app.babelecho.android.data.*
import app.babelecho.android.recording.PcmResampler
import app.babelecho.android.recording.WavFileWriter
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.sin

class CoreRegressionTest {
    @get:Rule val temp = TemporaryFolder()
    private fun meeting() = Meeting("old-id", "测试会议", 1, 3_600_500, "/private/recording.wav", true, false,
        transcript = "决定明天交付", segments = listOf(TranscriptSegment(9, 3_600_000, 3_600_500, "Alice", "决定明天交付", "Deliver tomorrow")))

    private fun wav(seconds: Int = 21): File = temp.newFile().also { file ->
        WavFileWriter(file, 16_000).use { it.write(ShortArray(16_000 * seconds) { i -> (sin(i * .1) * 1000).toInt().toShort() }) }
    }
    private fun settings(server: MockWebServer) = AppSettings(asrBaseUrl = server.url("/v1").toString(), asrNoAuth = true,
        llmBaseUrl = server.url("/v1").toString(), llmNoAuth = true, chunkSeconds = 10, overlapSeconds = 2, allowInsecureHttp = true)
    private fun asr(text: String) = MockResponse().setBody(JSONObject().put("text", text).toString())
    private fun chat(content: String) = MockResponse().setBody(JSONObject().put("choices", org.json.JSONArray().put(
        JSONObject().put("message", JSONObject().put("content", content)))).put("usage", JSONObject().put("prompt_tokens", 40).put("completion_tokens", 10)).toString())

    @Test fun oldMeetingWithoutNewFieldsStillDecodes() {
        val old = JSONObject("""{"id":"old","createdAt":5,"audioPath":"/old.wav","transcript":"keep me"}""")
        val loaded = MeetingJson.decode(old)
        assertEquals("keep me", loaded.transcript)
        assertEquals(0L, loaded.stats.inputTokens)
        assertEquals(0.0, loaded.stats.asrSeconds, 0.0)
        assertTrue(loaded.segments.isEmpty())
    }

    @Test fun completeMeetingRoundTrips() {
        val original = meeting().copy(qa = listOf(QaEntry("When?", "Tomorrow", 7)),
            stats = MeetingStats(3, 2, 3600, 10, 28.5, 400, 50))
        assertEquals(original, MeetingJson.decode(MeetingJson.encode(original)))
    }

    @Test fun bilingualVttIncludesHoursAndBothLanguages() {
        val text = MeetingExport.text(meeting(), ExportFormat.BilingualVtt)
        assertTrue(text.startsWith("WEBVTT\n\n"))
        assertTrue(text.contains("01:00:00.000 --> 01:00:00.500"))
        assertTrue(text.contains("决定明天交付\nDeliver tomorrow"))
    }

    @Test fun exportDoesNotRevealPrivateAudioPathAndIncludesQuestions() {
        assertFalse(MeetingExport.text(meeting(), ExportFormat.Json).contains("/private"))
        assertTrue(MeetingExport.text(meeting().copy(qa = listOf(QaEntry("When?", "Tomorrow", 0))), ExportFormat.Markdown).contains("Q: When?"))
        assertFalse(MeetingExport.filename(meeting().copy(title = "a/b:c"), ExportFormat.Text).contains("/"))
    }

    @Test fun resamplingAcrossBuffersMatchesSingleBuffer() {
        val input = DoubleArray(48_000) { i -> sin(i * .15) * 1000 }
        val all = PcmResampler(48_000).convert(input)
        val stream = PcmResampler(48_000)
        val pieces = input.toList().chunked(731).flatMap { stream.convert(it.toDoubleArray()).toList() }.toShortArray()
        assertArrayEquals(all, pieces)
        assertEquals(16_000, all.size)
    }

    @Test fun chunkingRetainsConfiguredOverlapAndAudioDuration() {
        val chunks = WavChunker.split(wav(), temp.root, 10, 2)
        assertEquals(listOf(10_000L, 10_000L, 5_000L), chunks.map(WavChunker::durationMs))
    }

    @Test fun interruptedWavHeaderCanBeRecoveredWithoutLosingPcm() {
        val file = wav(2)
        val pcm = file.readBytes().drop(44)
        java.io.RandomAccessFile(file, "rw").use { it.write(ByteArray(44)) }
        assertEquals(2_000L, WavFileWriter.recover(file, 16_000))
        assertEquals(2_000L, WavChunker.durationMs(file))
        assertEquals(pcm, file.readBytes().drop(44))
    }

    @Test fun httpNeedsExplicitOptIn() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val result = runCatching { CloudClient(temp.root).transcribeResult(wav(1), settings(server).copy(allowInsecureHttp = false)) }
            assertTrue(result.isFailure)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun redirectCannotSendAudioToAnotherServer() = runBlocking {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.start(); other.start()
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/steal")))
            assertTrue(runCatching { CloudClient(temp.root).transcribeResult(wav(1), settings(server)) }.isFailure)
            assertNull(other.takeRequest(100, TimeUnit.MILLISECONDS))
        } }
    }

    @Test fun providerErrorsCannotEchoKeysAndAreBounded() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(401).setBody(JSONObject().put("error", JSONObject().put("message", "fixture-only " + "x".repeat(2_000))).toString()))
            val error = runCatching { CloudClient(temp.root).transcribeResult(wav(1), settings(server).copy(asrNoAuth = false, asrApiKey = "fixture-only")) }.exceptionOrNull()!!
            assertFalse(error.message!!.contains("fixture-only"))
            assertTrue(error.message!!.length < 550)
        }
    }

    @Test fun retranscriptionReturnsTimedSegmentsAndCountsEachRequest() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(asr("first phrase")); server.enqueue(asr("second phrase")); server.enqueue(asr("third phrase"))
            val usages = mutableListOf<ModelUsage>()
            val segments = CloudClient(temp.root) { usages += it }.transcribeSegments(wav(), settings(server))
            assertEquals(listOf(0L, 8_000L, 16_000L), segments.map { it.startMs })
            assertEquals(listOf(0, 1, 2), segments.map { it.id })
            assertEquals(3, usages.sumOf { it.asrCalls })
            assertEquals(25.0, usages.sumOf { it.asrSeconds }, 0.01)
            assertTrue(temp.root.listFiles()!!.none { it.name.startsWith("babel-asr-") })
        }
    }

    @Test fun failedChunkCannotReturnPartialReplacementAndCleansCache() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(asr("first phrase")); server.enqueue(MockResponse().setResponseCode(429).setBody("rate limited"))
            assertTrue(runCatching { CloudClient(temp.root).transcribeSegments(wav(), settings(server)) }.isFailure)
            assertTrue(temp.root.listFiles()!!.none { it.name.startsWith("babel-asr-") })
            assertEquals("决定明天交付", meeting().transcript)
        }
    }

    @Test fun japaneseCannotBeSentToMimo() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            assertTrue(runCatching { CloudClient(temp.root).transcribeResult(wav(1), settings(server).copy(asrKind = "mimo", asrLanguage = "ja")) }.isFailure)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun routesRequireTheSelectedSupportedProviderAndNeverFallbackSilently() {
        val base = AppSettings(useAsrProfiles = true, asrLanguage = "ja", asrRoutes = ProviderDefaults.routes() + ("ja" to "mimo"),
            asrProfiles = ProviderDefaults.asr().map { it.copy(enabled = true, noAuth = true) })
        assertFalse(base.hasAsr)
        assertTrue(runCatching { base.forAsr() }.isFailure)
        val good = base.copy(asrRoutes = base.asrRoutes + ("ja" to "groq"))
        assertEquals("openai", good.forAsr().asrKind)
        assertEquals("https://api.groq.com/openai/v1", good.forAsr().asrBaseUrl)
        val disabled = good.copy(asrProfiles = good.asrProfiles.map { it.copy(enabled = false) })
        assertTrue(runCatching { disabled.forAsr() }.isFailure)
    }

    @Test fun textTasksUseTheirOwnProviderAndModel() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(chat("Minutes result"))
            val value = settings(server).copy(useTextProfiles = true,
                textProfiles = listOf(TextProfile("local", server.url("/text").toString(), true, noAuth = true)),
                textRoles = mapOf("minutes" to TextRole("local", "minutes-only")))
            CloudClient(temp.root).createMinutes(meeting(), value)
            val request = server.takeRequest()
            assertEquals("/text/chat/completions", request.path)
            assertEquals("minutes-only", JSONObject(request.body.readUtf8()).getString("model"))
            assertNull(request.getHeader("Authorization"))
        }
    }

    @Test fun deepgramRetainsSpeakerAndTimestamps() {
        val response = JSONObject("""{"results":{"channels":[{"alternatives":[{"transcript":"Hello"}]}],"utterances":[{"start":0.25,"end":0.8,"speaker":1,"transcript":"Hello"}]}}""")
        val segment = CloudClient(temp.root).parseAsr(response, "deepgram", 1_000).single()
        assertEquals("Speaker 2", segment.speaker)
        assertEquals(250L, segment.startMs)
    }

    @Test fun translationMustReturnEveryStableId() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(chat("""{"translations":[{"id":100,"text":"wrong id"}]}"""))
            assertTrue(runCatching { CloudClient(temp.root).translateSegments(meeting().segments, "en", settings(server)) }.isFailure)
        }
    }

    @Test fun textTokenUsageIsReportedAndMinutesHandleLongMeetings() = runBlocking {
        MockWebServer().use { server ->
            server.start(); repeat(4) { server.enqueue(chat("Useful summary")) }
            val usages = mutableListOf<ModelUsage>()
            CloudClient(temp.root) { usages += it }.createMinutes(meeting().copy(segments = emptyList(), transcript = "a".repeat(30_000)), settings(server))
            assertEquals(4, usages.sumOf { it.llmCalls })
            assertEquals(160L, usages.sumOf { it.inputTokens })
            repeat(4) { assertTrue(server.takeRequest().body.readUtf8().length < 25_500) }
        }
    }
}
