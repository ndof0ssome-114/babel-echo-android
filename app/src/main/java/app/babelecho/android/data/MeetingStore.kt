package app.babelecho.android.data

import android.content.Context
import org.json.JSONObject
import java.io.File
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
        val temp = File(directory, "meeting.json.tmp")
        temp.writeText(json.toString(2))
        temp.renameTo(File(directory, "meeting.json"))
    }

    fun list(): List<Meeting> = root.listFiles()
        .orEmpty()
        .mapNotNull { directory ->
            runCatching {
                val json = JSONObject(File(directory, "meeting.json").readText())
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
                )
            }.getOrNull()
        }
        .sortedByDescending { it.createdAt }

    fun update(id: String, transform: (Meeting) -> Meeting): Meeting {
        val current = list().first { it.id == id }
        return transform(current).also(::save)
    }
}
