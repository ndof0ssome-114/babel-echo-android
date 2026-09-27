package app.babelecho.android.data

data class AppSettings(
    val asrBaseUrl: String = "https://api.groq.com/openai/v1",
    val asrModel: String = "whisper-large-v3-turbo",
    val asrLanguage: String = "auto",
    val asrApiKey: String = "",
    val llmBaseUrl: String = "https://api.deepseek.com/v1",
    val llmModel: String = "deepseek-chat",
    val llmApiKey: String = "",
    val chunkSeconds: Int = 25,
    val overlapSeconds: Int = 2,
    val allowInsecureHttp: Boolean = false,
)
