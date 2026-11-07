package app.babelecho.android.cloud

import android.content.Context
import app.babelecho.android.data.AppSettings
import app.babelecho.android.data.Meeting
import app.babelecho.android.data.MinutesRenderer
import app.babelecho.android.data.NotesPrompts
import app.babelecho.android.data.Sentences
import app.babelecho.android.data.TranscriptAssembler
import app.babelecho.android.data.TranscriptSegment
import app.babelecho.android.data.TextModelPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class ModelUsage(val asrCalls: Int = 0, val llmCalls: Int = 0, val asrSeconds: Double = 0.0,
    val inputTokens: Long = 0, val outputTokens: Long = 0, val reasoningTokens: Long? = null,
    val task: String = "", val unreportedCalls: Int = 0)

class CloudClient(private val cacheDirectory: File, private val onUsage: (ModelUsage) -> Unit = {}) {
    constructor(context: Context, onUsage: (ModelUsage) -> Unit = {}) : this(context.cacheDir, onUsage)
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(150, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    fun cancel() { client.dispatcher.cancelAll() }

    suspend fun discoverModels(baseUrl: String, apiKey: String, noAuth: Boolean, allowInsecure: Boolean): List<String> = withContext(Dispatchers.IO) {
        require(noAuth || apiKey.isNotBlank()) { "Enter this provider's API key or select no API key" }
        validateUrl(baseUrl, allowInsecure, "Model list")
        val request = Request.Builder().url(endpoint(baseUrl, "models"))
            .apply { if (!noAuth) header("Authorization", "Bearer ${apiKey.trim()}") }.build()
        val json = JSONObject(client.newBuilder().callTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
            .newCall(request).execute().use { readResponse(it, request) })
        val items = json.optJSONArray("data") ?: error("No compatible model list; enter a model ID manually")
        val models = (0 until items.length()).mapNotNull { items.optJSONObject(it)?.optString("id")?.trim()?.takeIf(String::isNotBlank) }.distinct().sorted()
        require(models.isNotEmpty()) { "No models returned; enter a model ID manually" }
        models
    }

    suspend fun transcribe(audio: File, settings: AppSettings): String =
        transcribeSegments(audio, settings).joinToString("\n") { it.text }

    suspend fun transcribeSegments(audio: File, settings: AppSettings): List<TranscriptSegment> = withContext(Dispatchers.IO) {
        val chunks = WavChunker.split(audio, cacheDirectory, settings.chunkSeconds, settings.overlapSeconds)
        try {
            // Same sentence assembly as live recording and desktop: cut sentences are rejoined,
            // short ones grouped, and text heard twice in the chunk overlap is dropped.
            val assembler = TranscriptAssembler(emptyList())
            var result = emptyList<TranscriptSegment>()
            var previous = ""
            var previousEnd = 0L
            chunks.forEachIndexed { index, chunk ->
                val start = index * (settings.chunkSeconds - settings.overlapSeconds) * 1_000L
                val overlapUntil = if (index > 0) previousEnd else null
                val received = transcribeResult(chunk, settings, previous)
                received.forEach { item ->
                    val absoluteStart = start + item.startMs
                    val absoluteEnd = start + item.endMs
                    val clean = if (overlapUntil != null && absoluteStart < overlapUntil) Sentences.trimRepeatedPrefix(previous, item.text) else item.text.trim()
                    if (clean.isNotBlank()) {
                        result = assembler.add(clean, maxOf(absoluteStart, overlapUntil ?: absoluteStart), absoluteEnd, item.speaker).apply(result)
                        previous = (previous + "\n" + clean).takeLast(240)
                    }
                }
                previousEnd = start + WavChunker.durationMs(chunk)
            }
            require(result.isNotEmpty()) { "No speech recognized; the recording and previous transcript were preserved" }
            result
        } finally { chunks.forEach { it.delete() } }
    }

    suspend fun transcribeChunk(audio: File, settings: AppSettings, prompt: String = ""): String =
        transcribeResult(audio, settings, prompt).joinToString("\n") { it.text }

    suspend fun transcribeResult(audio: File, settings: AppSettings, prompt: String = ""): List<TranscriptSegment> = withContext(Dispatchers.IO) {
        if (settings.useAsrProfiles) return@withContext transcribeResult(audio, settings.forAsr(), prompt)
        require(settings.hasAsr) { "Please configure an ASR URL, model and API key (or no-key mode)" }
        validateUrl(settings.asrBaseUrl, settings.allowInsecureHttp, "ASR")
        require(settings.asrKind != "mimo" || settings.asrLanguage in setOf("zh", "en")) {
            "MiMo ASR requires explicit Chinese or English; choose Groq/OpenAI or Deepgram for Japanese/automatic language"
        }
        val duration = WavChunker.durationMs(audio)
        val json = when (settings.asrKind) {
            "deepgram" -> {
                val url = endpoint(settings.asrBaseUrl, "listen").toHttpUrl().newBuilder()
                    .addQueryParameter("model", settings.asrModel).addQueryParameter("smart_format", "true")
                    .addQueryParameter("diarize", "true").addQueryParameter("utterances", "true")
                    .addQueryParameter("punctuate", "true")
                    .apply { if (settings.asrLanguage != "auto") addQueryParameter("language", settings.asrLanguage) }
                    .build()
                JSONObject(execute(Request.Builder().url(url).post(audio.asRequestBody("audio/wav".toMediaType()))
                    .apply { if (!settings.asrNoAuth && settings.asrApiKey.isNotBlank()) header("Authorization", "Token ${settings.asrApiKey.trim()}") }.build()))
            }
            "mimo" -> {
                val content = JSONObject().put("type", "input_audio").put("input_audio", JSONObject().put("data", "data:audio/wav;base64," + Base64.getEncoder().encodeToString(audio.readBytes())))
                val body = JSONObject().put("model", settings.asrModel)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray().put(content))))
                    .put("asr_options", JSONObject().put("language", settings.asrLanguage))
                JSONObject(execute(Request.Builder().url(endpoint(settings.asrBaseUrl, "chat/completions"))
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .apply { if (!settings.asrNoAuth && settings.asrApiKey.isNotBlank()) header("Authorization", "Bearer ${settings.asrApiKey.trim()}") }.build()))
            }
            else -> {
                val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("model", settings.asrModel).addFormDataPart("response_format", "verbose_json")
                    .addFormDataPart("temperature", "0").addFormDataPart("file", audio.name, audio.asRequestBody("audio/wav".toMediaType()))
                if (settings.asrLanguage != "auto") builder.addFormDataPart("language", settings.asrLanguage)
                if (prompt.isNotBlank()) builder.addFormDataPart("prompt", prompt.takeLast(240))
                JSONObject(execute(Request.Builder().url(endpoint(settings.asrBaseUrl, "audio/transcriptions"))
                    .post(builder.build()).apply { if (!settings.asrNoAuth && settings.asrApiKey.isNotBlank()) header("Authorization", "Bearer ${settings.asrApiKey.trim()}") }.build()))
            }
        }
        val parsed = parseAsr(json, settings.asrKind, duration)
        onUsage(ModelUsage(asrCalls = 1, asrSeconds = duration / 1_000.0))
        parsed
    }

    internal fun parseAsr(json: JSONObject, kind: String, duration: Long): List<TranscriptSegment> {
        val text = when (kind) {
            "mimo" -> json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                .replace(Regex("^\\s*<[a-z_-]{2,20}>\\s*", RegexOption.IGNORE_CASE), "")
            "deepgram" -> json.getJSONObject("results").getJSONArray("channels").getJSONObject(0)
                .getJSONArray("alternatives").getJSONObject(0).getString("transcript")
            else -> json.getString("text")
        }.trim()
        val items = if (kind == "deepgram") json.getJSONObject("results").optJSONArray("utterances") else json.optJSONArray("segments")
        val segments = (0 until (items?.length() ?: 0)).mapNotNull { index ->
            val item = items!!.getJSONObject(index)
            val body = item.optString(if (kind == "deepgram") "transcript" else "text").trim()
            if (body.isBlank()) null else TranscriptSegment(index,
                (item.optDouble("start", 0.0) * 1_000).toLong().coerceIn(0, duration),
                (item.optDouble("end", duration / 1_000.0) * 1_000).toLong().coerceIn(0, duration),
                speaker = if (item.has("speaker")) {
                    if (kind == "deepgram") "Speaker ${item.getInt("speaker") + 1}" else item.get("speaker").toString()
                } else "Speaker 1", text = body)
        }
        return segments.ifEmpty { if (text.isBlank()) emptyList() else listOf(TranscriptSegment(0, 0, duration, speaker = "Speaker 1", text = text)) }
    }

    /** Full summary of a transcript; long text is folded in rolling chunks, like the live summary. */
    suspend fun summarize(transcript: String, settings: AppSettings): String = summarize(
        Meeting("", "", 0, 0, "", true, false, transcript = transcript), settings)

    suspend fun summarize(meeting: Meeting, settings: AppSettings): String = withContext(Dispatchers.IO) {
        val segments = meeting.segments.ifEmpty {
            require(meeting.transcript.isNotBlank()) { "Transcribe this recording first" }
            listOf(TranscriptSegment(0, 0, meeting.durationMs, text = meeting.transcript))
        }
        // Same incremental shape as desktop: previous summary + the next slice of transcript.
        var summary = ""
        for (part in chunkSegments(segments, SUMMARY_CHUNK_CHARS)) {
            val prompt = NotesPrompts.summary(summary, part, settings.summaryMaxChars, meeting.title, meeting.mode)
            summary = chat(settings, settings.summaryModel, prompt.system, prompt.user, "summary")
        }
        summary
    }

    suspend fun updateSummary(previous: String, newSegments: List<TranscriptSegment>, settings: AppSettings,
                              title: String = "", mode: String = NotesPrompts.MODE_MEETING): String = withContext(Dispatchers.IO) {
        val prompt = NotesPrompts.summary(previous, newSegments, settings.summaryMaxChars, title, mode)
        chat(settings, settings.summaryModel, prompt.system, prompt.user, "summary")
    }

    /**
     * Structured notes in the desktop schema, rendered to Markdown. A reply that is not JSON is
     * kept as the model wrote it rather than discarded.
     */
    suspend fun createMinutes(meeting: Meeting, settings: AppSettings): String = withContext(Dispatchers.IO) {
        val full = if (meeting.segments.isNotEmpty()) NotesPrompts.renderTranscript(meeting.segments) else meeting.transcript
        require(full.isNotBlank()) { "Transcribe this recording first" }
        // Desktop sends the whole transcript. Only beyond what a typical context window holds is
        // it replaced by a rolling summary, and the prompt says so.
        val transcript = if (full.length > MINUTES_FULL_CHARS) "（转写过长，以下为按顺序整理的完整要点）\n" + summarize(meeting, settings) else full
        val target = meeting.language.takeUnless { it.isBlank() || it == "auto" } ?: "zh"
        val prompt = NotesPrompts.minutes(transcript, meeting.title, meeting.summary, target, meeting.mode, meeting.notesDetail)
        val raw = chat(settings, settings.minutesModel, prompt.system, prompt.user, "minutes")
        MinutesRenderer.parse(raw)?.let { MinutesRenderer.render(it, meeting.mode, settings.uiLanguage) } ?: raw
    }

    suspend fun ask(meeting: Meeting, question: String, settings: AppSettings): String = withContext(Dispatchers.IO) {
        require(question.isNotBlank()) { "Question cannot be empty" }
        val transcript = if (meeting.segments.isNotEmpty()) NotesPrompts.renderTranscript(meeting.segments) else meeting.transcript
        val prompt = NotesPrompts.ask(question, transcript, meeting.summary, meeting.qa.map { it.question to it.answer })
        chat(settings, settings.askModel, prompt.system, prompt.user, "ask")
    }

    /**
     * Translates in batches of 20 with the two preceding sentences as context (desktop behaviour).
     * [context] is the text just before the first segment, for live use.
     */
    suspend fun translateSegments(segments: List<TranscriptSegment>, target: String, settings: AppSettings,
                                  context: List<String> = emptyList()): List<String> =
        withContext(Dispatchers.IO) {
            if (segments.isEmpty() || target.isBlank()) return@withContext emptyList()
            val translations = mutableMapOf<Int, String>()
            var before = context.takeLast(2)
            segments.chunked(TRANSLATE_BATCH).forEach { batch ->
                val prompt = NotesPrompts.translate(batch.map { it.id to it.text }, target, before)
                val raw = chat(settings, settings.translateModel, prompt.system, prompt.user, "translation")
                val array = JSONObject(extractJson(raw)).getJSONArray("translations")
                val byId = (0 until array.length()).associate { index ->
                    val item = array.getJSONObject(index)
                    item.getInt("id") to item.getString("text")
                }
                require(array.length() == batch.size && byId.keys == batch.map { it.id }.toSet() && byId.values.all { it.isNotBlank() }) {
                    "Translation response has missing or duplicate IDs; previous translations were preserved"
                }
                translations.putAll(byId)
                before = batch.takeLast(2).map { it.text }
            }
            segments.map { translations.getValue(it.id) }
        }

    /** Text-only speaker attribution in batches of 150, keeping labels consistent across batches. */
    suspend fun resplitSpeakers(segments: List<TranscriptSegment>, settings: AppSettings): Map<Int, String> = withContext(Dispatchers.IO) {
        require(segments.isNotEmpty()) { "Transcribe this recording first" }
        val known = linkedSetOf<String>().apply { segments.forEach { add(it.speaker) } }
        val labels = mutableMapOf<Int, String>()
        segments.chunked(RESPLIT_BATCH).forEach { batch ->
            val prompt = NotesPrompts.speakerSplit(batch, known)
            val raw = chat(settings, settings.askModel, prompt.system, prompt.user, "speaker")
            val array = JSONObject(extractJson(raw)).optJSONArray("assignments") ?: JSONArray()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val label = item.optString("speaker").trim().take(60)
                if (label.isNotBlank() && item.has("id")) { labels[item.optInt("id")] = label; known += label }
            }
        }
        require(labels.isNotEmpty()) { "Invalid speaker labels; original labels were preserved" }
        // Segments the model skipped keep their current label.
        segments.associate { it.id to (labels[it.id] ?: it.speaker) }
    }

    private fun chunkSegments(segments: List<TranscriptSegment>, maxChars: Int): List<List<TranscriptSegment>> {
        val out = mutableListOf<List<TranscriptSegment>>()
        var current = mutableListOf<TranscriptSegment>()
        var size = 0
        for (segment in segments) {
            val length = segment.text.length + 16
            if (current.isNotEmpty() && size + length > maxChars) { out += current; current = mutableListOf(); size = 0 }
            if (length > maxChars) {
                // One enormous segment (an unsegmented transcript) is split by characters.
                segment.text.chunked(maxChars - 16).forEach { out += listOf(segment.copy(text = it)) }
                continue
            }
            current += segment; size += length
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    private fun extractJson(raw: String): String {
        val first = raw.indexOf('{'); val last = raw.lastIndexOf('}')
        require(first >= 0 && last > first) { "Model returned invalid JSON" }
        return raw.substring(first, last + 1)
    }

    private fun chat(settings: AppSettings, model: String, system: String, user: String, task: String): String {
        if (settings.useTextProfiles) {
            val (resolved, selected) = settings.forText(task, model)
            return chat(resolved, selected, system, user, task)
        }
        require(settings.llmNoAuth || settings.llmApiKey.isNotBlank()) { "Please configure an LLM API key" }
        validateUrl(settings.llmBaseUrl, settings.allowInsecureHttp, "LLM")
        val selectedModel = model.ifBlank { settings.llmModel }
        require(selectedModel.isNotBlank()) { "Please configure an LLM model" }
        val body = JSONObject()
            .put("model", selectedModel)
            .put("temperature", 0.2)
            .put("max_tokens", TextModelPolicy.outputLimit(settings.llmBaseUrl, task, settings.textThinking[task]))
            .apply {
                val thinking = settings.textThinking[task]
                if (TextModelPolicy.isDeepSeek(settings.llmBaseUrl) && thinking in setOf("enabled", "disabled")) {
                    put("thinking", JSONObject().put("type", thinking))
                }
            }
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(endpoint(settings.llmBaseUrl, "chat/completions"))
            .post(body)
            .apply {
                if (!settings.llmNoAuth && settings.llmApiKey.isNotBlank()) header("Authorization", "Bearer ${settings.llmApiKey.trim()}")
            }
            .build()
        val json = JSONObject(execute(request))
        val usage = json.optJSONObject("usage")
        val details = usage?.optJSONObject("completion_tokens_details")
        onUsage(ModelUsage(llmCalls = 1, inputTokens = (usage?.optLong("prompt_tokens") ?: 0).coerceAtLeast(0),
            outputTokens = (usage?.optLong("completion_tokens") ?: 0).coerceAtLeast(0),
            reasoningTokens = if (details?.has("reasoning_tokens") == true && !details.isNull("reasoning_tokens")) details.optLong("reasoning_tokens").coerceAtLeast(0) else null,
            task = task, unreportedCalls = if (usage == null || !usage.has("prompt_tokens") || !usage.has("completion_tokens") || usage.isNull("prompt_tokens") || usage.isNull("completion_tokens")) 1 else 0))
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        val content = choice?.optJSONObject("message")?.opt("content") as? String
        require(!content.isNullOrBlank()) { "Text model returned no content. Check the model and thinking mode; usage saved and previous content preserved" }
        require(choice.optString("finish_reason") != "length") { "Text model reached its output limit; usage saved and previous content preserved" }
        return content.trim()
    }

    private fun execute(request: Request): String {
        var attempt = 0
        while (true) {
            val delay = client.newCall(request).execute().use { response ->
                // Rate limits and transient server errors are retried after the provider's
                // Retry-After; network failures are not, because they may already be billed.
                val wait = if (response.isSuccessful) null
                    else NetworkPolicy.retryDelayMs(response.code, response.header("Retry-After"), attempt)
                if (wait == null) return readResponse(response, request)
                wait
            }
            Thread.sleep(delay)
            attempt++
        }
    }

    private fun readResponse(response: okhttp3.Response, request: Request): String {
        val text = response.body.string()
        if (!response.isSuccessful) {
            val message = runCatching {
                JSONObject(text).optJSONObject("error")?.optString("message")
            }.getOrNull().orEmpty().ifBlank { text.take(500) }.take(500)
            val secret = request.header("Authorization")?.substringAfter(' ').orEmpty()
            val safe = if (secret.isBlank()) message else message.replace(secret, "[redacted]")
            error("HTTP ${response.code}: $safe")
        }
        return text
    }

    private fun endpoint(baseUrl: String, path: String): String = "${baseUrl.trimEnd('/')}/$path"

    private fun validateUrl(url: String, allowInsecure: Boolean, label: String) {
        val parsed = runCatching { url.toHttpUrl() }.getOrNull()
        val valid = parsed != null && parsed.username.isBlank() && parsed.password.isBlank() &&
            (parsed.scheme == "https" || (allowInsecure && parsed.scheme == "http" && NetworkPolicy.isLocalNetworkHost(parsed.host)))
        require(valid) { "$label base URL must use HTTPS; plain HTTP is only allowed to this device or the local network after enabling it" }
    }

    /** Desktop-equivalent overlap trimming (kept as a method for existing callers). */
    fun removeOverlap(previous: String, current: String): String = Sentences.trimRepeatedPrefix(previous, current)

    companion object {
        private const val SUMMARY_CHUNK_CHARS = 24_000
        // Roughly 50k tokens of Chinese; beyond this the transcript is condensed first.
        private const val MINUTES_FULL_CHARS = 120_000
        private const val TRANSLATE_BATCH = 20
        private const val RESPLIT_BATCH = 150
    }
}
