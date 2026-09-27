package app.babelecho.android.cloud

import android.content.Context
import app.babelecho.android.data.AppSettings
import app.babelecho.android.data.Meeting
import app.babelecho.android.data.TranscriptSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

class CloudClient(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun transcribe(audio: File, settings: AppSettings): String = withContext(Dispatchers.IO) {
        require(settings.asrNoAuth || settings.asrApiKey.isNotBlank()) { "Please configure an ASR API key" }
        validateUrl(settings.asrBaseUrl, settings.allowInsecureHttp, "ASR")
        require(settings.asrModel.isNotBlank()) { "Please configure an ASR model" }
        val chunks = WavChunker.split(
            audio,
            context.cacheDir,
            settings.chunkSeconds,
            settings.overlapSeconds,
        )
        try {
            var transcript = ""
            chunks.forEachIndexed { index, chunk ->
                val builder = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("model", settings.asrModel)
                    .addFormDataPart("response_format", "json")
                    .addFormDataPart("temperature", "0")
                    .addFormDataPart("file", "chunk-${index + 1}.wav", chunk.asRequestBody("audio/wav".toMediaType()))
                if (!settings.asrLanguage.equals("auto", ignoreCase = true)) {
                    builder.addFormDataPart("language", settings.asrLanguage)
                }
                if (transcript.isNotBlank()) {
                    builder.addFormDataPart("prompt", transcript.takeLast(240))
                }
                val text = executeAsr(builder, settings)
                transcript = mergeOverlap(transcript, text)
            }
            transcript.trim()
        } finally {
            chunks.forEach { it.delete() }
        }
    }

    suspend fun transcribeChunk(audio: File, settings: AppSettings, prompt: String = ""): String = withContext(Dispatchers.IO) {
        require(settings.asrNoAuth || settings.asrApiKey.isNotBlank()) { "Please configure an ASR API key" }
        validateUrl(settings.asrBaseUrl, settings.allowInsecureHttp, "ASR")
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", settings.asrModel)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("temperature", "0")
            .addFormDataPart("file", audio.name, audio.asRequestBody("audio/wav".toMediaType()))
        if (!settings.asrLanguage.equals("auto", ignoreCase = true)) builder.addFormDataPart("language", settings.asrLanguage)
        if (prompt.isNotBlank()) builder.addFormDataPart("prompt", prompt.takeLast(240))
        executeAsr(builder, settings)
    }

    suspend fun summarize(transcript: String, settings: AppSettings): String = withContext(Dispatchers.IO) {
        require(settings.llmNoAuth || settings.llmApiKey.isNotBlank()) { "Please configure an LLM API key" }
        validateUrl(settings.llmBaseUrl, settings.allowInsecureHttp, "LLM")
        require(settings.llmModel.isNotBlank()) { "Please configure an LLM model" }
        require(transcript.isNotBlank()) { "Transcribe this recording first" }

        val parts = transcript.chunked(24_000)
        if (parts.size == 1) {
            chat(settings, settings.summaryModel, SUMMARY_PROMPT, transcript)
        } else {
            val partials = parts.mapIndexed { index, part ->
                chat(
                    settings,
                    settings.summaryModel,
                    "Summarize part ${index + 1} of ${parts.size}. Preserve names, decisions, numbers, and action items. Do not invent details.",
                    part,
                )
            }
            chat(settings, settings.summaryModel, SUMMARY_PROMPT, partials.joinToString("\n\n---\n\n"))
        }
    }

    suspend fun updateSummary(previous: String, newSegments: List<TranscriptSegment>, settings: AppSettings): String =
        withContext(Dispatchers.IO) {
            val added = renderSegments(newSegments)
            chat(
                settings,
                settings.summaryModel,
                """You maintain a concise live meeting summary. Use only stated facts. Return the complete updated summary in the transcript's primary language using short Markdown headings and bullets. Preserve names, numbers, dates, decisions, disagreements, and unresolved items. Do not describe the update process.""",
                "已有摘要：\n${previous.ifBlank { "（暂无）" }}\n\n新增转写：\n$added",
            )
        }

    suspend fun createMinutes(meeting: Meeting, settings: AppSettings): String = withContext(Dispatchers.IO) {
        val transcript = if (meeting.segments.isNotEmpty()) renderSegments(meeting.segments) else meeting.transcript
        chat(
            settings,
            settings.minutesModel,
            """You write rigorous structured meeting minutes in the transcript's primary language. Use only the transcript. Return Markdown with: meeting overview, participants, key points, decisions, action items (owner and due date only when stated), risks or disagreements, unresolved questions, and 2–6 timestamped chapters. Never invent missing details.""",
            "会议标题：${meeting.title}\n\n实时摘要：\n${meeting.summary}\n\n完整转写：\n$transcript",
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
            "摘要：\n${meeting.summary}\n\n转写：\n$transcript\n\n此前问答：\n$history\n\n问题：$question",
        )
    }

    suspend fun translateSegments(segments: List<TranscriptSegment>, target: String, settings: AppSettings): List<String> =
        withContext(Dispatchers.IO) {
            if (segments.isEmpty() || target.isBlank()) return@withContext emptyList()
            val input = JSONArray().apply { segments.forEach { put(JSONObject().put("id", it.id).put("text", it.text)) } }
            val raw = chat(
                settings,
                settings.translateModel,
                """Translate every supplied meeting segment into language code '$target'. Preserve meaning, tone, names, and technical terms. Return only JSON: {"translations":[{"id":1,"text":"..."}]}""",
                input.toString(),
            )
            val firstBrace = raw.indexOf('{')
            val lastBrace = raw.lastIndexOf('}')
            require(firstBrace >= 0 && lastBrace > firstBrace) { "Translation model returned invalid JSON" }
            val jsonText = raw.substring(firstBrace, lastBrace + 1)
            val array = JSONObject(jsonText).getJSONArray("translations")
            val byId = (0 until array.length()).associate { index ->
                val item = array.getJSONObject(index)
                item.getInt("id") to item.getString("text")
            }
            segments.map { byId[it.id].orEmpty() }
        }

    private fun executeAsr(builder: MultipartBody.Builder, settings: AppSettings): String {
        val request = Request.Builder()
            .url(endpoint(settings.asrBaseUrl, "audio/transcriptions"))
            .post(builder.build())
            .apply {
                if (settings.asrApiKey.isNotBlank()) header("Authorization", "Bearer ${settings.asrApiKey}")
            }
            .build()
        return JSONObject(execute(request)).optString("text").trim()
    }

    private fun chat(settings: AppSettings, model: String, system: String, user: String): String {
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
        return json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
    }

    private fun execute(request: Request): String = client.newCall(request).execute().use { response ->
        val text = response.body.string()
        if (!response.isSuccessful) {
            val message = runCatching {
                JSONObject(text).optJSONObject("error")?.optString("message")
            }.getOrNull().orEmpty().ifBlank { text.take(500) }
            error("HTTP ${response.code}: $message")
        }
        text
    }

    private fun endpoint(baseUrl: String, path: String): String = "${baseUrl.trimEnd('/')}/$path"

    private fun validateUrl(url: String, allowInsecure: Boolean, label: String) {
        val valid = url.startsWith("https://") || (allowInsecure && url.startsWith("http://"))
        require(valid) { "$label base URL must use HTTPS, unless insecure local HTTP is explicitly enabled" }
    }

    private fun mergeOverlap(previous: String, current: String): String {
        if (previous.isBlank()) return current
        if (current.isBlank()) return previous
        val max = minOf(240, previous.length, current.length)
        for (length in max downTo 6) {
            val suffix = previous.takeLast(length).trim().lowercase()
            val prefix = current.take(length).trim().lowercase()
            if (suffix == prefix) return previous + current.drop(length)
        }
        return "$previous\n$current"
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
