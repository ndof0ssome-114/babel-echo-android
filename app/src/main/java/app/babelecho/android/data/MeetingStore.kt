package app.babelecho.android.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MeetingStore(context: Context) {
    private val root = File(context.filesDir, "meetings").apply { mkdirs() }

    fun createDirectory(): Pair<String, File> {
        val id = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val directory = File(root, id).apply { mkdirs() }
        return id to directory
    }

    fun save(meeting: Meeting) {
        val directory = File(root, meeting.id).apply { mkdirs() }
        val json = JSONObject()
            .put("id", meeting.id)
            .put("title", meeting.title)
            .put("createdAt", meeting.createdAt)
            .put("durationMs", meeting.durationMs)
            .put("audioPath", meeting.audioPath)
            .put("usedMicrophone", meeting.usedMicrophone)
            .put("usedPlayback", meeting.usedPlayback)
            .put("transcript", meeting.transcript)
            .put("summary", meeting.summary)
            .put("minutes", meeting.minutes)
            .put("language", meeting.language)
            .put("translateTo", meeting.translateTo)
            .put("state", meeting.state)
            .put("segments", JSONArray().apply {
                meeting.segments.forEach { segment ->
                    put(JSONObject()
                        .put("id", segment.id)
                        .put("startMs", segment.startMs)
                        .put("endMs", segment.endMs)
                        .put("speaker", segment.speaker)
                        .put("text", segment.text)
                        .put("translated", segment.translated))
                }
            })
            .put("qa", JSONArray().apply {
                meeting.qa.forEach { entry ->
                    put(JSONObject().put("question", entry.question).put("answer", entry.answer).put("createdAt", entry.createdAt))
                }
            })
            .put("stats", JSONObject()
                .put("asrCalls", meeting.stats.asrCalls)
                .put("llmCalls", meeting.stats.llmCalls)
                .put("audioSeconds", meeting.stats.audioSeconds)
                .put("transcriptChars", meeting.stats.transcriptChars))
        val temp = File(directory, "meeting.json.tmp")
        temp.writeText(json.toString(2))
        val target = File(directory, "meeting.json")
        runCatching {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun list(): List<Meeting> = root.listFiles()
        .orEmpty()
        .mapNotNull { directory ->
            runCatching {
                val json = JSONObject(File(directory, "meeting.json").readText())
                val segmentsJson = json.optJSONArray("segments") ?: JSONArray()
                val segments = (0 until segmentsJson.length()).map { index ->
                    val item = segmentsJson.getJSONObject(index)
                    TranscriptSegment(
                        id = item.optInt("id", index),
                        startMs = item.optLong("startMs"),
                        endMs = item.optLong("endMs"),
                        speaker = item.optString("speaker", "说话人1"),
                        text = item.optString("text"),
                        translated = item.optString("translated"),
                    )
                }
                val qaJson = json.optJSONArray("qa") ?: JSONArray()
                val qa = (0 until qaJson.length()).map { index ->
                    val item = qaJson.getJSONObject(index)
                    QaEntry(item.optString("question"), item.optString("answer"), item.optLong("createdAt"))
                }
                val stats = json.optJSONObject("stats") ?: JSONObject()
                Meeting(
                    id = json.getString("id"),
                    title = json.optString("title", json.getString("id")),
                    createdAt = json.getLong("createdAt"),
                    durationMs = json.optLong("durationMs"),
                    audioPath = json.getString("audioPath"),
                    usedMicrophone = json.optBoolean("usedMicrophone"),
                    usedPlayback = json.optBoolean("usedPlayback"),
                    transcript = json.optString("transcript"),
                    summary = json.optString("summary"),
                    minutes = json.optString("minutes"),
                    language = json.optString("language", "auto"),
                    translateTo = json.optString("translateTo"),
                    state = json.optString("state", "stopped"),
                    segments = segments,
                    qa = qa,
                    stats = MeetingStats(
                        asrCalls = stats.optInt("asrCalls"),
                        llmCalls = stats.optInt("llmCalls"),
                        audioSeconds = stats.optLong("audioSeconds"),
                        transcriptChars = stats.optInt("transcriptChars"),
                    ),
                )
            }.getOrNull()
        }
        .sortedByDescending { it.createdAt }

    fun update(id: String, transform: (Meeting) -> Meeting): Meeting {
        val current = list().first { it.id == id }
        return transform(current).also(::save)
    }

    fun delete(id: String): Boolean = File(root, id).deleteRecursively()
}
