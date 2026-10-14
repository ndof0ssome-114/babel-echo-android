package app.babelecho.android.data

data class AsrProfile(
    val id: String, val kind: String, val baseUrl: String, val model: String,
    val enabled: Boolean = false, val apiKey: String = "", val noAuth: Boolean = false,
    val languages: Set<String> = setOf("auto", "zh", "ja", "en"),
    val label: String = "",
) {
    fun supports(language: String) = (language in languages || "auto" in languages) && (kind != "mimo" || language in setOf("zh", "en"))
    val configured get() = enabled && baseUrl.isNotBlank() && model.isNotBlank() && (noAuth || apiKey.isNotBlank())
}

data class TextProfile(val id: String, val baseUrl: String, val enabled: Boolean = false, val apiKey: String = "", val noAuth: Boolean = false, val label: String = "")
data class TextRole(val provider: String, val model: String, val thinking: String? = null)

object TextModelPolicy {
    fun isDeepSeek(url: String): Boolean = runCatching {
        java.net.URI(url.trim()).host.equals("api.deepseek.com", ignoreCase = true)
    }.getOrDefault(false)

    fun initial(provider: TextProfile, task: String): TextRole = TextRole(provider.id,
        if (isDeepSeek(provider.baseUrl)) (if (task == "minutes") "deepseek-v4-pro" else "deepseek-flash") else "",
        if (isDeepSeek(provider.baseUrl) && task in setOf("summary", "translation")) "disabled" else null)

    fun outputLimit(url: String, task: String, thinking: String?): Int =
        if (isDeepSeek(url) && thinking != "disabled") 16_384 else if (task == "minutes") 8_192 else 4_096
}

object ProviderDefaults {
    fun asr() = listOf(
        AsrProfile("groq", "openai", "https://api.groq.com/openai/v1", "whisper-large-v3-turbo", languages = languages.toSet()),
        AsrProfile("mimo", "mimo", "https://api.xiaomimimo.com/v1", "mimo-v2.5-asr", languages = setOf("zh", "en")),
        AsrProfile("deepgram", "deepgram", "https://api.deepgram.com/v1", "nova-3"),
        AsrProfile("local", "openai", "", ""),
    )
    fun text() = listOf(TextProfile("deepseek", "https://api.deepseek.com/v1"), TextProfile("mimo", "https://api.xiaomimimo.com/v1"), TextProfile("local", ""))
    fun routes() = languages.associateWith { "groq" }
    fun roles() = listOf("summary", "minutes", "translation", "ask", "speaker").associateWith {
        TextModelPolicy.initial(text().first(), it)
    }
    val languages = listOf("auto", "zh", "ja", "en", "ko", "fr", "de", "es")
    val tasks = listOf("summary", "minutes", "translation", "ask", "speaker")
    fun textModels(url: String) = if (TextModelPolicy.isDeepSeek(url)) listOf("deepseek-flash", "deepseek-v4-pro") else emptyList()
    fun asrModels(profile: AsrProfile): List<String> = asr().firstOrNull { it.id == profile.id && it.kind == profile.kind && it.baseUrl == profile.baseUrl }
        ?.let { listOf(it.model) } ?: emptyList()
    fun validateCatalog(settings: AppSettings) {
        listOf(settings.asrProfiles.map { it.id }, settings.textProfiles.map { it.id }).forEach { ids ->
            require(ids.size <= 24 && ids.size == ids.toSet().size && ids.all { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }) { "Invalid or duplicate provider IDs" }
        }
        require(settings.asrProfiles.all { it.kind in setOf("openai", "mimo", "deepgram") && it.languages.isNotEmpty() }) { "Invalid ASR protocol or languages" }
    }
}
