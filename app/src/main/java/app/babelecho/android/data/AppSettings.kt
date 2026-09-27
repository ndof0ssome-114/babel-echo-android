package app.babelecho.android.data

data class AppSettings(
    val uiLanguage: String = "zh",
    val asrBaseUrl: String = "https://api.groq.com/openai/v1",
    val asrModel: String = "whisper-large-v3-turbo",
    val asrLanguage: String = "auto",
    val asrApiKey: String = "",
    val asrNoAuth: Boolean = false,
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
)
