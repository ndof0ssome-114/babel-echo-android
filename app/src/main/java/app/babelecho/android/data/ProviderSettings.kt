package app.babelecho.android.data

data class AsrProfile(
    val id: String, val kind: String, val baseUrl: String, val model: String,
    val enabled: Boolean = false, val apiKey: String = "", val noAuth: Boolean = false,
    val languages: Set<String> = setOf("auto", "zh", "ja", "en"),
) {
    fun supports(language: String) = language in languages && (kind != "mimo" || language in setOf("zh", "en"))
    val configured get() = enabled && baseUrl.isNotBlank() && model.isNotBlank() && (noAuth || apiKey.isNotBlank())
}

data class TextProfile(val id: String, val baseUrl: String, val enabled: Boolean = false, val apiKey: String = "", val noAuth: Boolean = false)
data class TextRole(val provider: String, val model: String, val thinking: String? = null)

object TextModelPolicy {
    fun isDeepSeek(url: String): Boolean = runCatching {
        java.net.URI(url.trim()).host.equals("api.deepseek.com", ignoreCase = true)
    }.getOrDefault(false)

    fun initial(provider: TextProfile, task: String): TextRole = TextRole(provider.id,
        if (isDeepSeek(provider.baseUrl)) "deepseek-chat" else "",
        if (isDeepSeek(provider.baseUrl) && task in setOf("summary", "translation")) "disabled" else null)

    fun outputLimit(url: String, task: String, thinking: String?): Int =
        if (isDeepSeek(url) && thinking != "disabled") 16_384 else if (task == "minutes") 8_192 else 4_096
}

object ProviderDefaults {
    fun asr() = listOf(
        AsrProfile("groq", "openai", "https://api.groq.com/openai/v1", "whisper-large-v3-turbo"),
        AsrProfile("mimo", "mimo", "https://api.xiaomimimo.com/v1", "mimo-v2.5-asr", languages = setOf("zh", "en")),
        AsrProfile("deepgram", "deepgram", "https://api.deepgram.com/v1", "nova-3"),
        AsrProfile("local", "openai", "", ""),
    )
    fun text() = listOf(TextProfile("deepseek", "https://api.deepseek.com/v1"), TextProfile("mimo", "https://api.xiaomimimo.com/v1"), TextProfile("local", ""))
    fun routes() = mapOf("zh" to "groq", "ja" to "groq", "en" to "groq", "auto" to "groq")
    fun roles() = listOf("summary", "minutes", "translation", "ask", "speaker").associateWith {
        TextRole("deepseek", "deepseek-chat", if (it in setOf("summary", "translation")) "disabled" else null)
    }
}
