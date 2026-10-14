package app.babelecho.android.data

import java.util.Locale

enum class ExportFormat(val extension: String, val mime: String, val label: String) {
    Markdown("md", "text/markdown", "Markdown"), Text("txt", "text/plain", "TXT"),
    Vtt("vtt", "text/vtt", "VTT"), BilingualVtt("vtt", "text/vtt", "VTT · 双语 / Bilingual"),
    Json("json", "application/json", "JSON"), Audio("wav", "audio/wav", "WAV")
}

object MeetingExport {
    fun text(meeting: Meeting, format: ExportFormat): String {
        val segments = meeting.segments.ifEmpty {
            if (meeting.transcript.isBlank()) emptyList() else listOf(TranscriptSegment(0, 0, meeting.durationMs, text = meeting.transcript))
        }
        return when (format) {
            ExportFormat.Json -> MeetingJson.encode(meeting).apply { remove("audioPath") }.toString(2)
            ExportFormat.Vtt, ExportFormat.BilingualVtt -> buildString {
                append("WEBVTT\n\n")
                segments.forEachIndexed { index, segment ->
                    append("${index + 1}\n${timestamp(segment.startMs)} --> ${timestamp(maxOf(segment.endMs, segment.startMs + 1))}\n")
                    append("${segment.speaker}: ${safeCue(segment.text)}\n")
                    if (format == ExportFormat.BilingualVtt && segment.translated.isNotBlank()) append("${safeCue(segment.translated)}\n")
                    append("\n")
                }
            }
            ExportFormat.Markdown, ExportFormat.Text -> buildString {
                val heading = if (format == ExportFormat.Markdown) "## " else ""
                append(if (format == ExportFormat.Markdown) "# ${meeting.title}\n\n" else "${meeting.title}\n\n")
                if (meeting.summary.isNotBlank()) append("${heading}摘要 / Summary\n\n${meeting.summary}\n\n")
                if (meeting.minutes.isNotBlank()) append("${heading}纪要 / Minutes\n\n${meeting.minutes}\n\n")
                append("${heading}转写 / Transcript\n\n")
                segments.forEach {
                    append("[${timestamp(it.startMs)}] ${it.speaker}: ${it.text}\n")
                    if (it.translated.isNotBlank()) append("${it.translated}\n")
                }
                if (meeting.qa.isNotEmpty()) {
                    append("\n${heading}问答 / Q&A\n\n")
                    meeting.qa.forEach { append("Q: ${it.question}\nA: ${it.answer}\n\n") }
                }
            }
            ExportFormat.Audio -> error("Audio must be copied as a file")
        }
    }

    fun filename(meeting: Meeting, format: ExportFormat): String =
        meeting.title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(80).ifBlank { "BabelEcho" } +
            (if (format == ExportFormat.BilingualVtt) "-bilingual" else "") + ".${format.extension}"

    private fun safeCue(text: String) = text.replace("-->", "→").replace(Regex("[\\r\\n]+"), " ").trim()
    private fun timestamp(ms: Long): String {
        val time = ms.coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", time / 3_600_000, time / 60_000 % 60, time / 1_000 % 60, time % 1_000)
    }
}
