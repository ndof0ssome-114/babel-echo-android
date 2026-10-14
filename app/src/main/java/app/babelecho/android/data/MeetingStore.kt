package app.babelecho.android.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

class MeetingStore(context: Context) {
    private val root = File(context.filesDir, "meetings").apply { mkdirs() }

    fun createDirectory(): Pair<String, File> = synchronized(lock) {
        val id = UUID.randomUUID().toString()
        id to File(root, id).apply { check(mkdirs()) { "Cannot create meeting folder" } }
    }

    fun save(meeting: Meeting): Unit = synchronized(lock) {
        val directory = File(root, meeting.id).apply { mkdirs() }
        val temp = File(directory, "meeting.json.tmp")
        temp.writeText(MeetingJson.encode(meeting).toString(2))
        val target = File(directory, "meeting.json")
        runCatching {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        Unit
    }

    fun get(id: String): Meeting? = synchronized(lock) {
        runCatching { MeetingJson.decode(JSONObject(File(File(root, id), "meeting.json").readText())) }.getOrNull()
    }

    fun list(): List<Meeting> = synchronized(lock) {
        root.listFiles().orEmpty().mapNotNull { get(it.name) }.sortedByDescending { it.createdAt }
    }

    fun update(id: String, transform: (Meeting) -> Meeting): Meeting = synchronized(lock) {
        transform(requireNotNull(get(id)) { "Meeting no longer exists" }).also(::save)
    }

    fun delete(id: String): Boolean = synchronized(lock) { File(root, id).deleteRecursively() }

    companion object { private val lock = Any() }
}
