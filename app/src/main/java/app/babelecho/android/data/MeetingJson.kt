package app.babelecho.android.data

import org.json.JSONArray
import org.json.JSONObject

object MeetingJson {
    fun encode(meeting: Meeting): JSONObject {
        return JSONObject()
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
                .put("transcriptChars", meeting.stats.transcriptChars)
                .put("asrSeconds", meeting.stats.asrSeconds)
                .put("inputTokens", meeting.stats.inputTokens)
                .put("outputTokens", meeting.stats.outputTokens))
    }

    fun decode(json: JSONObject): Meeting {
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
                return Meeting(
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
                        asrSeconds = stats.optDouble("asrSeconds", 0.0),
                        inputTokens = stats.optLong("inputTokens"),
                        outputTokens = stats.optLong("outputTokens"),
                    ),
                )
    }
}
