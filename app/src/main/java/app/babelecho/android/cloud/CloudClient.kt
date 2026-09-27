package app.babelecho.android.cloud

import android.content.Context
import app.babelecho.android.data.AppSettings
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
        require(settings.asrApiKey.isNotBlank()) { "Please configure an ASR API key" }
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
                val request = Request.Builder()
                    .url(endpoint(settings.asrBaseUrl, "audio/transcriptions"))
                    .header("Authorization", "Bearer ${settings.asrApiKey}")
                    .post(builder.build())
                    .build()
                val text = execute(request).let { JSONObject(it).optString("text") }.trim()
                transcript = mergeOverlap(transcript, text)
            }
            transcript.trim()
        } finally {
            chunks.forEach { it.delete() }
        }
    }

    suspend fun summarize(transcript: String, settings: AppSettings): String = withContext(Dispatchers.IO) {
        require(settings.llmApiKey.isNotBlank()) { "Please configure an LLM API key" }
        validateUrl(settings.llmBaseUrl, settings.allowInsecureHttp, "LLM")
        require(settings.llmModel.isNotBlank()) { "Please configure an LLM model" }
        require(transcript.isNotBlank()) { "Transcribe this recording first" }

        val parts = transcript.chunked(24_000)
        if (parts.size == 1) {
            chat(settings, SUMMARY_PROMPT, transcript)
        } else {
            val partials = parts.mapIndexed { index, part ->
                chat(
                    settings,
                    "Summarize part ${index + 1} of ${parts.size}. Preserve names, decisions, numbers, and action items. Do not invent details.",
                    part,
                )
            }
            chat(settings, SUMMARY_PROMPT, partials.joinToString("\n\n---\n\n"))
        }
    }

    private fun chat(settings: AppSettings, system: String, user: String): String {
        val body = JSONObject()
            .put("model", settings.llmModel)
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
            .header("Authorization", "Bearer ${settings.llmApiKey}")
            .post(body)
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

    companion object {
        private const val SUMMARY_PROMPT = """You are a careful meeting assistant. Write a concise summary in the same primary language as the transcript. Include: overview, key points, decisions, action items with owners and deadlines when stated, and unresolved questions. Never invent missing details."""
    }
}
