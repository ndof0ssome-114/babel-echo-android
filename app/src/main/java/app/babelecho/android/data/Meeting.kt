package app.babelecho.android.data

data class Meeting(
    val id: String,
    val title: String,
    val createdAt: Long,
    val durationMs: Long,
    val audioPath: String,
    val usedMicrophone: Boolean,
    val usedPlayback: Boolean,
    val transcript: String = "",
    val summary: String = "",
)
