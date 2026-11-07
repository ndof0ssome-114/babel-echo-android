package app.babelecho.android.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Renders the structured notes JSON into Markdown, following the desktop `toMarkdown()` order:
 * abstract, topics, class sections, decisions, actions, risks, keywords, open questions, chapters.
 * Unknown or malformed fields are skipped instead of failing the whole document.
 */
object MinutesRenderer {
    private val labels = mapOf(
        "zh" to listOf("课程笔记", "智能纪要", "内容详解", "核心概念与公式", "例题与案例", "复习问答", "关键决议", "待办事项",
            "事项", "负责人", "截止", "风险与分歧", "关键词：", "待确认问题", "章节回顾", "参与者"),
        "ja" to listOf("授業ノート", "議事録", "詳細", "重要概念と公式", "例題と事例", "復習問答", "決定事項", "ToDo",
            "項目", "担当", "期限", "リスクと相違点", "キーワード：", "未解決の問題", "章の振り返り", "参加者"),
        "en" to listOf("Class notes", "Minutes", "Details", "Key concepts and formulas", "Examples", "Review questions", "Decisions", "Action items",
            "Task", "Owner", "Due", "Risks and disagreements", "Keywords: ", "Open questions", "Chapters", "Participants"),
    )

    /** Parses a model reply that may be fenced or chatty; null when it holds no JSON object. */
    fun parse(raw: String): JSONObject? {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        runCatching { return JSONObject(text) }
        val first = text.indexOf('{')
        val last = text.lastIndexOf('}')
        if (first < 0 || last <= first) return null
        return runCatching { JSONObject(text.substring(first, last + 1)) }.getOrNull()
    }

    fun render(data: JSONObject, mode: String, uiLanguage: String = "zh"): String {
        val l = labels[uiLanguage] ?: labels.getValue("zh")
        val classroom = NotesPrompts.normalizeMode(mode) == NotesPrompts.MODE_CLASS
        val out = StringBuilder()
        fun line(text: String = "") { out.append(text).append('\n') }
        fun strings(key: String): List<String> = data.optJSONArray(key).items().mapNotNull { (it as? String)?.takeIf(String::isNotBlank) }
        fun objects(key: String): List<JSONObject> = data.optJSONArray(key).items().filterIsInstance<JSONObject>()
        fun text(item: Any?, key: String): String = when (item) {
            is JSONObject -> item.optString(key).takeUnless { item.isNull(key) }.orEmpty()
            is String -> item
            else -> ""
        }

        line("## " + if (classroom) l[0] else l[1])
        line()
        data.optString("abstract").takeIf { it.isNotBlank() && !data.isNull("abstract") }?.let { line(it); line() }
        val participants = strings("participants")
        if (participants.isNotEmpty()) { line("**${l[15]}**：" + participants.joinToString("、")); line() }
        for (topic in objects("topics")) {
            line("### " + text(topic, "title").ifBlank { l[2] })
            line()
            text(topic, "detail").takeIf { it.isNotBlank() }?.let { line(it); line() }
            topic.optJSONArray("points").items().mapNotNull { (it as? String)?.takeIf(String::isNotBlank) }.forEach { line("- $it") }
            line()
        }
        for ((key, heading, name, detail) in listOf(
            Quad("keyConcepts", l[3], "term", "explanation"),
            Quad("examples", l[4], "title", "explanation"),
            Quad("reviewQuestions", l[5], "question", "answer"),
        )) {
            val items = objects(key)
            if (items.isEmpty()) continue
            line("### $heading"); line()
            items.forEach { line("#### " + text(it, name)); line(); line(text(it, detail)); line() }
        }
        val decisions = data.optJSONArray("decisions").items()
        if (decisions.isNotEmpty()) {
            line("### ${l[6]}")
            decisions.forEach { d ->
                val context = text(d, "context")
                line("- " + (if (d is JSONObject) text(d, "decision") else text(d, "")) + if (context.isNotBlank()) "（$context）" else "")
            }
            line()
        }
        val actions = objects("actionItems")
        if (actions.isNotEmpty()) {
            line("### ${l[7]}")
            line("| ${l[8]} | ${l[9]} | ${l[10]} |")
            line("| --- | --- | --- |")
            actions.forEach { a -> line("| " + listOf("task", "owner", "due").joinToString(" | ") { cell(text(a, it)) } + " |") }
            line()
        }
        val risks = data.optJSONArray("risks").items()
        if (risks.isNotEmpty()) {
            line("### ${l[11]}")
            risks.forEach { r ->
                val impact = text(r, "impact")
                line("- " + (if (r is JSONObject) text(r, "risk") else text(r, "")) + if (impact.isNotBlank()) "（$impact）" else "")
            }
            line()
        }
        val keywords = strings("keywords")
        if (keywords.isNotEmpty()) { line(l[12] + keywords.joinToString("、")); line() }
        val questions = strings("openQuestions")
        if (questions.isNotEmpty()) { line("### ${l[13]}"); line(); questions.forEach { line("- $it") }; line() }
        val chapters = objects("chapters")
        if (chapters.isNotEmpty()) {
            line("### ${l[14]}"); line()
            chapters.forEach { line("#### " + "${text(it, "start")} ${text(it, "title")}".trim()); line(); line(text(it, "summary")); line() }
        }
        return out.toString().replace(Regex("\n{3,}"), "\n\n").trim() + "\n"
    }

    private data class Quad(val key: String, val heading: String, val name: String, val detail: String)

    private fun cell(value: String) = value.replace("|", "\\|").replace(Regex("[\\r\\n]+"), " ")

    private fun JSONArray?.items(): List<Any> = if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it) }
}
