package app.babelecho.android.data

data class AppSettings(
    val uiLanguage: String = "zh",
    val asrBaseUrl: String = "https://api.groq.com/openai/v1",
    val asrModel: String = "whisper-large-v3-turbo",
    val asrLanguage: String = "auto",
    val asrApiKey: String = "",
    val asrNoAuth: Boolean = false,
    val asrKind: String = "openai",
    val liveTranscription: Boolean = false,
    val liveUtterances: Boolean = true,
    val vadThreshold: Double = 0.003,
    val autoMinutes: Boolean = true,
    val llmBaseUrl: String = "https://api.deepseek.com/v1",
    val llmModel: String = "deepseek-chat",
    val summaryModel: String = "deepseek-chat",
    val minutesModel: String = "deepseek-chat",
    val translateModel: String = "deepseek-chat",
    val askModel: String = "deepseek-chat",
    val llmApiKey: String = "",
    val llmNoAuth: Boolean = false,
    val chunkSeconds: Int = 25,
    val overlapSeconds: Int = 2,
    val autoSummarySeconds: Int = 180,
    val translateTo: String = "",
    val allowInsecureHttp: Boolean = false,
    val asrPricePerHour: Double = 0.0,
    val inputPricePerMillion: Double = 0.0,
    val outputPricePerMillion: Double = 0.0,
    val priceCurrency: String = "USD",
    val useAsrProfiles: Boolean = false,
    val asrProfiles: List<AsrProfile> = ProviderDefaults.asr(),
    val asrRoutes: Map<String, String> = ProviderDefaults.routes(),
    val useTextProfiles: Boolean = false,
    val textProfiles: List<TextProfile> = ProviderDefaults.text(),
    val textRoles: Map<String, TextRole> = ProviderDefaults.roles(),
    val textThinking: Map<String, String?> = mapOf("summary" to "disabled", "translation" to "disabled"),
) {
    val hasAsr: Boolean get() = if (useAsrProfiles) asrProfiles.any { it.id == asrRoutes[asrLanguage] && it.configured && it.supports(asrLanguage) }
        else asrBaseUrl.isNotBlank() && asrModel.isNotBlank() && (asrNoAuth || asrApiKey.isNotBlank())
    val hasLlm: Boolean get() = if (useTextProfiles) textRoles["minutes"]?.let { role -> textProfiles.any { it.id == role.provider && it.enabled && it.baseUrl.isNotBlank() && (it.noAuth || it.apiKey.isNotBlank()) && role.model.isNotBlank() } } == true
        else llmBaseUrl.isNotBlank() && (llmNoAuth || llmApiKey.isNotBlank()) &&
        (llmModel.isNotBlank() || minutesModel.isNotBlank())

    fun forAsr(): AppSettings {
        if (!useAsrProfiles) return this
        val profile = asrProfiles.firstOrNull { it.id == asrRoutes[asrLanguage] && it.configured && it.supports(asrLanguage) }
        requireNotNull(profile) { "No configured ASR route supports '$asrLanguage'; check language routes and provider keys" }
        return copy(useAsrProfiles = false, asrBaseUrl = profile.baseUrl, asrModel = profile.model,
            asrApiKey = profile.apiKey, asrNoAuth = profile.noAuth, asrKind = profile.kind)
    }

    fun forText(task: String, fallbackModel: String): Pair<AppSettings, String> {
        if (!useTextProfiles) return this to fallbackModel
        val role = requireNotNull(textRoles[task]) { "No model assigned to '$task'" }
        val profile = textProfiles.firstOrNull { it.id == role.provider && it.enabled && it.baseUrl.isNotBlank() && (it.noAuth || it.apiKey.isNotBlank()) }
        requireNotNull(profile) { "Configure the text provider for '$task'" }
        require(role.model.isNotBlank()) { "Configure the model for '$task'" }
        return copy(useTextProfiles = false, llmBaseUrl = profile.baseUrl, llmApiKey = profile.apiKey, llmNoAuth = profile.noAuth,
            textThinking = textThinking + (task to role.thinking)) to role.model
    }

    val asrDestination: String get() = if (useAsrProfiles) asrProfiles.firstOrNull { it.id == asrRoutes[asrLanguage] }?.baseUrl.orEmpty() else asrBaseUrl
    fun hasText(task: String): Boolean = runCatching {
        val fallback = when (task) { "summary" -> summaryModel; "minutes" -> minutesModel; "translation" -> translateModel; else -> askModel }
        val (settings, model) = forText(task, fallback)
        settings.llmBaseUrl.isNotBlank() && (settings.llmNoAuth || settings.llmApiKey.isNotBlank()) && model.ifBlank { settings.llmModel }.isNotBlank()
    }.getOrDefault(false)
    val minutesDestination: String get() = if (useTextProfiles) textProfiles.firstOrNull { it.id == textRoles["minutes"]?.provider }?.baseUrl.orEmpty() else llmBaseUrl
}
