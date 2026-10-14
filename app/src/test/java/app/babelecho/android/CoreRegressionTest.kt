package app.babelecho.android

import app.babelecho.android.cloud.CloudClient
import app.babelecho.android.cloud.ModelUsage
import app.babelecho.android.cloud.WavChunker
import app.babelecho.android.data.*
import app.babelecho.android.recording.PcmResampler
import app.babelecho.android.recording.WavFileWriter
import app.babelecho.android.recording.UtteranceSegmenter
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

    @Test fun noAuthOmitsSavedKeysForEveryAdapterAndTextAndDiscovery() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val value = settings(server).copy(asrApiKey = "fixture-saved-asr", llmApiKey = "fixture-saved-text")
            val client = CloudClient(temp.root)
            server.enqueue(asr("hello")); client.transcribeResult(wav(1), value)
            server.enqueue(chat("hello")); client.transcribeResult(wav(1), value.copy(asrKind = "mimo", asrLanguage = "zh"))
            server.enqueue(MockResponse().setBody("""{"results":{"channels":[{"alternatives":[{"transcript":"hello"}]}]}}"""))
            client.transcribeResult(wav(1), value.copy(asrKind = "deepgram"))
            server.enqueue(chat("summary")); client.summarize("hello", value)
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"model-b"},{"id":"model-a"},{"id":"model-b"},{"id":""}]}"""))
            assertEquals(listOf("model-a", "model-b"), client.discoverModels(value.llmBaseUrl, value.llmApiKey, true, true))
            repeat(5) { assertNull(server.takeRequest().getHeader("Authorization")) }
        }
    }

    @Test fun discoveryUsesOnlyDraftEndpointAndDraftKeyWithoutMeetingData() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("""{"data":[{"id":"custom-model"}]}"""))
            assertEquals(listOf("custom-model"), CloudClient(temp.root).discoverModels(server.url("/draft/").toString(), "fixture-draft", false, true))
            val request = server.takeRequest()
            assertEquals("GET", request.method); assertEquals("/draft/models", request.path)
            assertEquals("Bearer fixture-draft", request.getHeader("Authorization")); assertEquals(0L, request.bodySize)
            assertTrue(runCatching { CloudClient(temp.root).discoverModels(server.url("/").toString(), "", false, true) }.isFailure)
            assertTrue(runCatching { CloudClient(temp.root).discoverModels(server.url("/").toString(), "", true, false) }.isFailure)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun emptyAndTruncatedResponsesReportUsageBeforeFailingWithoutRetry() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val usages = mutableListOf<ModelUsage>()
            val client = CloudClient(temp.root) { usages += it }
            for (content in listOf("", "unfinished")) {
                server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"length","message":{"content":"$content"}}],"usage":{"prompt_tokens":12,"completion_tokens":20,"completion_tokens_details":{"reasoning_tokens":15}}}"""))
                assertTrue(runCatching { client.summarize("hello", settings(server)) }.isFailure)
            }
            assertEquals(2, server.requestCount); assertEquals(2, usages.size)
            assertEquals(40L, usages.sumOf { it.outputTokens }); assertEquals(30L, usages.sumOf { it.reasoningTokens ?: 0 })
            assertEquals(setOf("summary"), usages.map { it.task }.toSet())
        }
    }

    @Test fun missingUsageIsExplicitAndNonDeepSeekNeverReceivesThinking() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"Done"}}]}"""))
            var usage = ModelUsage()
            CloudClient(temp.root) { usage = it }.createMinutes(meeting(), settings(server).copy(textThinking = mapOf("minutes" to "enabled")))
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertFalse(body.has("thinking")); assertEquals(8192, body.getInt("max_tokens"))
            assertEquals(1, usage.unreportedCalls); assertNull(usage.reasoningTokens)
        }
    }

    @Test fun thinkingScopeAndProviderSwitchAreExactAndPreserveOldDefaults() {
        assertTrue(TextModelPolicy.isDeepSeek("https://API.DEEPSEEK.COM/v1"))
        assertFalse(TextModelPolicy.isDeepSeek("https://api.deepseek.com.example/v1"))
        assertFalse(TextModelPolicy.isDeepSeek("https://example.com/api.deepseek.com"))
        assertEquals(16384, TextModelPolicy.outputLimit("https://api.deepseek.com/v1", "summary", null))
        assertEquals(4096, TextModelPolicy.outputLimit("https://api.deepseek.com/v1", "summary", "disabled"))
        assertEquals(8192, TextModelPolicy.outputLimit("https://api.deepseek.com/v1", "minutes", "disabled"))
        val changed = TextModelPolicy.initial(TextProfile("local", "http://localhost:1234/v1"), "summary")
        assertEquals("", changed.model); assertNull(changed.thinking)
        val old = TextRole("deepseek", "my-old-model")
        val settings = AppSettings(useTextProfiles = true, textProfiles = listOf(TextProfile("deepseek", "https://api.deepseek.com/v1", true, noAuth = true)), textRoles = mapOf("summary" to old))
        assertNull(settings.forText("summary", "").first.textThinking["summary"])
    }

    @Test fun taskUsageAndSummaryCursorRoundTripWithoutDoubleCountingReasoning() {
        val result = meeting().withUsage(ModelUsage(llmCalls = 1, inputTokens = 10, outputTokens = 20, reasoningTokens = 15, task = "summary"))
            .withUsage(ModelUsage(llmCalls = 1, task = "ask", unreportedCalls = 1)).copy(summaryUpTo = 1)
        assertEquals(result, MeetingJson.decode(MeetingJson.encode(result)))
        assertEquals(20L, result.stats.outputTokens); assertEquals(15L, result.stats.textUsageByTask.getValue("summary").reasoningTokens)
        assertEquals(1, result.stats.textUsageByTask.getValue("ask").unreportedCalls)
        assertEquals(0, MeetingJson.decode(JSONObject("""{"id":"old","createdAt":1,"audioPath":"old.wav"}""")).summaryUpTo)
    }

    @Test fun copyingAndSharingIncludeTranslationsSpeakersAndQuestions() {
        val value = meeting().copy(qa = listOf(QaEntry("When?", "Tomorrow", 1)))
        assertTrue(MeetingExport.transcript(value).contains("Alice: 决定明天交付"))
        assertTrue(MeetingExport.transcript(value).contains("Deliver tomorrow"))
        assertEquals("Q: When?\nA: Tomorrow", MeetingExport.questions(value))
        assertTrue(MeetingExport.text(value, ExportFormat.Markdown).contains("Deliver tomorrow"))
    }

    @Test fun utterancesSkipSilenceKeepPreRollAndUseRecordingSampleClock() {
        val segmenter = UtteranceSegmenter(1000, 10, 1.0)
        assertTrue(segmenter.append(ShortArray(5000)).isEmpty())
        assertTrue(segmenter.append(ShortArray(1000) { 1000 }).isEmpty())
        val first = segmenter.append(ShortArray(1200)).single()
        assertEquals(4700L, first.startSample); assertEquals(2500, first.samples.size)
        assertTrue(segmenter.append(ShortArray(3000)).isEmpty())
        assertTrue(segmenter.append(ShortArray(500) { 1000 }).isEmpty())
        val last = segmenter.finish()!!
        assertEquals(9900L, last.startSample); assertEquals(800, last.samples.size)
        assertNull(segmenter.finish())
    }

    @Test fun longUtterancesRetainOverlapAndNeverRepeatTheFinalOverlapOnStop() {
        val segmenter = UtteranceSegmenter(1000, 10, 1.0)
        val first = segmenter.append(ShortArray(10000) { 1000 }).single()
        assertEquals(0L, first.startSample); assertEquals(10000, first.samples.size)
        assertNull(segmenter.finish())
        val continuous = UtteranceSegmenter(1000, 10, 1.0)
        val cuts = continuous.append(ShortArray(19500) { 1000 })
        assertEquals(listOf(0L, 9000L), cuts.map { it.startSample })
        val tail = continuous.finish()!!
        assertEquals(18000L, tail.startSample); assertEquals(1500, tail.samples.size)
    }

    @Test fun utterancePauseFlushDoesNotInsertWallClockTimeOrUploadSilentRecording() {
        val segmenter = UtteranceSegmenter(1000)
        segmenter.append(ShortArray(1000) { 1000 })
        assertEquals(0L, segmenter.finish()!!.startSample)
        segmenter.append(ShortArray(1000) { 1000 })
        assertEquals(1000L, segmenter.finish()!!.startSample)
        segmenter.append(ShortArray(120000))
        assertNull(segmenter.finish())
    }
}
