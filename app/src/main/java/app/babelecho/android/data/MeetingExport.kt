package app.babelecho.android.data

import java.util.Locale

enum class ExportFormat(val extension: String, val mime: String, val label: String) {
    Markdown("md", "text/markdown", "Markdown"),
    MarkdownNotes("md", "text/markdown", "Markdown · 不含转写 / without transcript"),
    Text("txt", "text/plain", "TXT"),
    Vtt("vtt", "text/vtt", "VTT"), BilingualVtt("vtt", "text/vtt", "VTT · 双语 / Bilingual"),
    Srt("srt", "application/x-subrip", "SRT"),
    Json("json", "application/json", "JSON"), Audio("wav", "audio/wav", "WAV")
}

object MeetingExport {
    fun transcript(meeting: Meeting): String = if (meeting.segments.isEmpty()) meeting.transcript else
        meeting.segments.joinToString("\n\n") {
            "[${timestamp(it.startMs)}] ${it.speaker}: ${it.text}" + if (it.translated.isBlank()) "" else "\n${it.translated}"
        }

    fun questions(meeting: Meeting): String = meeting.qa.joinToString("\n\n") { "Q: ${it.question}\nA: ${it.answer}" }

    fun text(meeting: Meeting, format: ExportFormat): String {
        val segments = meeting.segments.ifEmpty {
            if (meeting.transcript.isBlank()) emptyList() else listOf(TranscriptSegment(0, 0, meeting.durationMs, text = meeting.transcript))
        }
        return when (format) {
            ExportFormat.Json -> MeetingJson.encode(meeting).apply { remove("audioPath") }.toString(2)
            ExportFormat.Vtt, ExportFormat.BilingualVtt -> buildString {
                append("WEBVTT\n\n")
                segments.forEachIndexed { index, segment ->
                    append("${index + 1}\n${timestamp(segment.startMs)} --> ${timestamp(cueEnd(segments, index))}\n")
                    append("${segment.speaker}: ${safeCue(segment.text)}\n")
                    if (format == ExportFormat.BilingualVtt && segment.translated.isNotBlank()) append("${safeCue(segment.translated)}\n")
                    append("\n")
                }
            }
            ExportFormat.Srt -> segments.mapIndexed { index, segment ->
                // SubRip uses a comma before milliseconds and a blank line between cues.
                "${index + 1}\n${timestamp(segment.startMs).replace('.', ',')} --> ${timestamp(cueEnd(segments, index)).replace('.', ',')}\n" +
                    "${segment.speaker}: ${safeCue(segment.text)}\n"
            }.joinToString("\n")
            ExportFormat.Markdown, ExportFormat.MarkdownNotes, ExportFormat.Text -> buildString {
                val markdown = format != ExportFormat.Text
                val heading = if (markdown) "## " else ""
                val classroom = meeting.mode == NotesPrompts.MODE_CLASS
                append(if (markdown) "# ${meeting.title}\n\n" else "${meeting.title}\n\n")
                append("- ${if (classroom) "使用场景：上课 / Class" else "使用场景：会议 / Meeting"}\n\n")
                if (meeting.summary.isNotBlank()) append("${heading}${if (classroom) "课堂摘要" else "摘要"} / Summary\n\n${meeting.summary}\n\n")
                // Structured notes already start with their own "## 课程笔记/智能纪要" heading.
                if (meeting.minutes.isNotBlank()) append(if (meeting.minutes.trimStart().startsWith("## ")) "${meeting.minutes.trim()}\n\n"
                    else "${heading}${if (classroom) "课程笔记" else "纪要"} / Minutes\n\n${meeting.minutes}\n\n")
                if (format == ExportFormat.MarkdownNotes) {
                    if (meeting.qa.isNotEmpty()) {
                        append("${heading}问答 / Q&A\n\n")
                        meeting.qa.forEach { append("Q: ${it.question}\nA: ${it.answer}\n\n") }
                    }
                    return@buildString
                }
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
            (if (format == ExportFormat.BilingualVtt) "-bilingual" else if (format == ExportFormat.MarkdownNotes) "-notes" else "") + ".${format.extension}"

    /**
     * A cue ends no later than the next one starts. Overlapping recognition windows (older
     * recordings) would otherwise stack two captions on screen at once.
     */
    private fun cueEnd(segments: List<TranscriptSegment>, index: Int): Long {
        val segment = segments[index]
        val next = segments.getOrNull(index + 1)?.startMs
        val end = if (next != null && next > segment.startMs) minOf(segment.endMs, next) else segment.endMs
        return maxOf(end, segment.startMs + 1)
    }

    private fun safeCue(text: String) = text.replace("-->", "→").replace(Regex("[\\r\\n]+"), " ").trim()
    private fun timestamp(ms: Long): String {
        val time = ms.coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", time / 3_600_000, time / 60_000 % 60, time / 1_000 % 60, time % 1_000)
    }
}
