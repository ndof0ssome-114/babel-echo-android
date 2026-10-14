package app.babelecho.android.cloud

import android.content.Context
import app.babelecho.android.data.AppSettings
import app.babelecho.android.data.Meeting
import app.babelecho.android.data.TranscriptSegment
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

data class ModelUsage(val asrCalls: Int = 0, val llmCalls: Int = 0, val asrSeconds: Double = 0.0, val inputTokens: Long = 0, val outputTokens: Long = 0)

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

    suspend fun transcribe(audio: File, settings: AppSettings): String =
        transcribeSegments(audio, settings).joinToString("\n") { it.text }

    suspend fun transcribeSegments(audio: File, settings: AppSettings): List<TranscriptSegment> = withContext(Dispatchers.IO) {
        val chunks = WavChunker.split(audio, cacheDirectory, settings.chunkSeconds, settings.overlapSeconds)
        try {
            val result = mutableListOf<TranscriptSegment>()
            var previous = ""
            chunks.forEachIndexed { index, chunk ->
                val start = index * (settings.chunkSeconds - settings.overlapSeconds) * 1_000L
                val received = transcribeResult(chunk, settings, previous)
                received.forEach { item ->
                    val clean = removeOverlap(previous, item.text)
                    if (clean.isNotBlank()) {
                        result += item.copy(id = result.size, startMs = start + item.startMs, endMs = start + item.endMs, text = clean)
                        previous = (previous + "\n" + clean).takeLast(240)
                    }
                }
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
                    .apply { if (settings.asrApiKey.isNotBlank()) header("Authorization", "Token ${settings.asrApiKey}") }.build()))
            }
            "mimo" -> {
                val content = JSONObject().put("type", "input_audio").put("input_audio", JSONObject().put("data", "data:audio/wav;base64," + Base64.getEncoder().encodeToString(audio.readBytes())))
                val body = JSONObject().put("model", settings.asrModel)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray().put(content))))
                    .put("asr_options", JSONObject().put("language", settings.asrLanguage))
                JSONObject(execute(Request.Builder().url(endpoint(settings.asrBaseUrl, "chat/completions"))
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .apply { if (settings.asrApiKey.isNotBlank()) header("Authorization", "Bearer ${settings.asrApiKey}") }.build()))
            }
            else -> {
                val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("model", settings.asrModel).addFormDataPart("response_format", "verbose_json")
                    .addFormDataPart("temperature", "0").addFormDataPart("file", audio.name, audio.asRequestBody("audio/wav".toMediaType()))
                if (settings.asrLanguage != "auto") builder.addFormDataPart("language", settings.asrLanguage)
                if (prompt.isNotBlank()) builder.addFormDataPart("prompt", prompt.takeLast(240))
                JSONObject(execute(Request.Builder().url(endpoint(settings.asrBaseUrl, "audio/transcriptions"))
                    .post(builder.build()).apply { if (settings.asrApiKey.isNotBlank()) header("Authorization", "Bearer ${settings.asrApiKey}") }.build()))
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

    suspend fun summarize(transcript: String, settings: AppSettings): String = withContext(Dispatchers.IO) {
        require(transcript.isNotBlank()) { "Transcribe this recording first" }

        val parts = transcript.chunked(24_000)
        if (parts.size == 1) {
            chat(settings, settings.summaryModel, SUMMARY_PROMPT, transcript, "summary")
        } else {
            val partials = parts.mapIndexed { index, part ->
                chat(
                    settings,
                    settings.summaryModel,
                    "Summarize part ${index + 1} of ${parts.size}. Preserve names, decisions, numbers, and action items. Do not invent details.",
                    part, "summary",
                )
            }
            chat(settings, settings.summaryModel, SUMMARY_PROMPT, partials.joinToString("\n\n---\n\n"), "summary")
        }
    }

    suspend fun updateSummary(previous: String, newSegments: List<TranscriptSegment>, settings: AppSettings): String =
        withContext(Dispatchers.IO) {
            val added = renderSegments(newSegments)
            chat(
                settings,
                settings.summaryModel,
                """You maintain a concise live meeting summary. Use only stated facts. Return the complete updated summary in the transcript's primary language using short Markdown headings and bullets. Preserve names, numbers, dates, decisions, disagreements, and unresolved items. Do not describe the update process.""",
                "已有摘要：\n${previous.ifBlank { "（暂无）" }}\n\n新增转写：\n$added", "summary",
            )
        }

    suspend fun createMinutes(meeting: Meeting, settings: AppSettings): String = withContext(Dispatchers.IO) {
        val full = if (meeting.segments.isNotEmpty()) renderSegments(meeting.segments) else meeting.transcript
        require(full.isNotBlank()) { "Transcribe this recording first" }
        val transcript = if (full.length > 24_000) summarize(full, settings) else full
        chat(
            settings,
            settings.minutesModel,
            """You write rigorous structured meeting minutes in the transcript's primary language. Use only the transcript. Return Markdown with: meeting overview, participants, key points, decisions, action items (owner and due date only when stated), risks or disagreements, unresolved questions, and 2–6 timestamped chapters. Never invent missing details.""",
            "会议标题：${meeting.title}\n\n实时摘要：\n${meeting.summary}\n\n完整转写：\n$transcript", "minutes",
        )
    }

    suspend fun ask(meeting: Meeting, question: String, settings: AppSettings): String = withContext(Dispatchers.IO) {
        require(question.isNotBlank()) { "Question cannot be empty" }
        val transcript = if (meeting.segments.isNotEmpty()) renderSegments(meeting.segments) else meeting.transcript
        val history = meeting.qa.takeLast(4).joinToString("\n") { "Q: ${it.question}\nA: ${it.answer}" }
        chat(
            settings,
            settings.askModel,
            """Answer only from the supplied meeting record. If the answer is absent, say so directly. Keep the answer concise and cite timestamps such as (12:34). Never invent facts.""",
            "摘要：\n${meeting.summary}\n\n转写：\n$transcript\n\n此前问答：\n$history\n\n问题：$question", "ask",
        )
    }

    suspend fun translateSegments(segments: List<TranscriptSegment>, target: String, settings: AppSettings): List<String> =
        withContext(Dispatchers.IO) {
            if (segments.isEmpty() || target.isBlank()) return@withContext emptyList()
            val translations = mutableMapOf<Int, String>()
            segments.chunked(32).forEach { batch ->
                val input = JSONArray().apply { batch.forEach { put(JSONObject().put("id", it.id).put("text", it.text)) } }
                val raw = chat(settings, settings.translateModel,
                    "Translate every segment into language code '$target'. Preserve meaning and names. Return only JSON: {\"translations\":[{\"id\":1,\"text\":\"...\"}]}", input.toString(), "translation")
                val array = JSONObject(extractJson(raw)).getJSONArray("translations")
                val byId = (0 until array.length()).associate { index ->
                    val item = array.getJSONObject(index)
                    item.getInt("id") to item.getString("text")
                }
                require(array.length() == batch.size && byId.keys == batch.map { it.id }.toSet() && byId.values.all { it.isNotBlank() }) {
                    "Translation response has missing or duplicate IDs; previous translations were preserved"
                }
                translations.putAll(byId)
            }
            segments.map { translations.getValue(it.id) }
        }

    suspend fun resplitSpeakers(segments: List<TranscriptSegment>, settings: AppSettings): Map<Int, String> = withContext(Dispatchers.IO) {
        require(segments.isNotEmpty()) { "Transcribe this recording first" }
        val input = JSONArray().apply { segments.forEach { put(JSONObject().put("id", it.id).put("text", it.text).put("speaker", it.speaker)) } }
        require(input.toString().length < 24_000) { "This meeting is too long for automatic speaker labeling; rename speakers manually" }
        val raw = chat(settings, settings.askModel,
            "Assign likely speaker labels from the text only, keeping labels when uncertain. Do not invent participant names. Return only JSON: {\"speakers\":[{\"id\":1,\"speaker\":\"Speaker 1\"}]}. Include every supplied ID once.", input.toString(), "speaker")
        val array = JSONObject(extractJson(raw)).getJSONArray("speakers")
        val map = (0 until array.length()).associate { i -> array.getJSONObject(i).let { it.getInt("id") to it.getString("speaker").trim() } }
        require(array.length() == segments.size && map.keys == segments.map { it.id }.toSet() && map.values.all { it.isNotBlank() }) { "Invalid speaker labels; original labels were preserved" }
        map
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
                if (settings.llmApiKey.isNotBlank()) header("Authorization", "Bearer ${settings.llmApiKey}")
            }
            .build()
        val json = JSONObject(execute(request))
        val content = json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
        require(content.isNotBlank()) { "Text model returned empty content" }
        val usage = json.optJSONObject("usage")
        onUsage(ModelUsage(llmCalls = 1, inputTokens = usage?.optLong("prompt_tokens") ?: 0, outputTokens = usage?.optLong("completion_tokens") ?: 0))
        return content
    }

    private fun execute(request: Request): String = client.newCall(request).execute().use { response ->
        val text = response.body.string()
        if (!response.isSuccessful) {
            val message = runCatching {
                JSONObject(text).optJSONObject("error")?.optString("message")
            }.getOrNull().orEmpty().ifBlank { text.take(500) }.take(500)
            val secret = request.header("Authorization")?.substringAfter(' ').orEmpty()
            val safe = if (secret.isBlank()) message else message.replace(secret, "[redacted]")
            error("HTTP ${response.code}: $safe")
        }
        text
    }

    private fun endpoint(baseUrl: String, path: String): String = "${baseUrl.trimEnd('/')}/$path"

    private fun validateUrl(url: String, allowInsecure: Boolean, label: String) {
        val parsed = runCatching { url.toHttpUrl() }.getOrNull()
        val valid = parsed != null && parsed.username.isBlank() && parsed.password.isBlank() &&
            (parsed.scheme == "https" || (allowInsecure && parsed.scheme == "http"))
        require(valid) { "$label base URL must use HTTPS, unless insecure local HTTP is explicitly enabled" }
    }

    fun removeOverlap(previous: String, current: String): String {
        if (previous.isBlank()) return current.trim()
        val max = minOf(240, previous.length, current.length)
        for (length in max downTo 6) {
            if (previous.takeLast(length).trim().equals(current.take(length).trim(), ignoreCase = true)) return current.drop(length).trim()
        }
        return current.trim()
    }

    private fun renderSegments(segments: List<TranscriptSegment>): String = segments.joinToString("\n") {
        "[${formatTimestamp(it.startMs)}] ${it.speaker}: ${it.text}"
    }

    private fun formatTimestamp(ms: Long): String {
        val seconds = (ms / 1_000).coerceAtLeast(0)
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    companion object {
        private const val SUMMARY_PROMPT = """You are a careful meeting assistant. Write a concise summary in the same primary language as the transcript. Include: overview, key points, decisions, action items with owners and deadlines when stated, and unresolved questions. Never invent missing details."""
    }
}
