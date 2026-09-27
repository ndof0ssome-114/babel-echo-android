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
    val minutes: String = "",
    val language: String = "auto",
    val translateTo: String = "",
    val state: String = "stopped",
    val segments: List<TranscriptSegment> = emptyList(),
    val qa: List<QaEntry> = emptyList(),
    val stats: MeetingStats = MeetingStats(),
)

data class TranscriptSegment(
    val id: Int,
    val startMs: Long,
    val endMs: Long,
    val speaker: String = "说话人1",
    val text: String,
    val translated: String = "",
)

data class QaEntry(val question: String, val answer: String, val createdAt: Long)

data class MeetingStats(
    val asrCalls: Int = 0,
    val llmCalls: Int = 0,
    val audioSeconds: Long = 0,
    val transcriptChars: Int = 0,
)
