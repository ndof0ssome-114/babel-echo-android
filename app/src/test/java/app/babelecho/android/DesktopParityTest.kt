package app.babelecho.android

import app.babelecho.android.cloud.CloudClient
import app.babelecho.android.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Behaviour ported from the desktop app (prompts, scene modes, structured notes, translation context). */
class DesktopParityTest {
    @get:Rule val temp = TemporaryFolder()

    private fun chat(content: String) = MockResponse().setBody(JSONObject().put("choices", JSONArray().put(
        JSONObject().put("message", JSONObject().put("content", content)))).toString())
    private fun settings(server: MockWebServer) = AppSettings(llmBaseUrl = server.url("/v1").toString(), llmNoAuth = true,
        llmModel = "m", summaryModel = "m", minutesModel = "m", translateModel = "m", askModel = "m", allowInsecureHttp = true)
    private fun segment(id: Int, text: String, start: Long = id * 1_000L) = TranscriptSegment(id, start, start + 900, "老师", text)

    @Test fun classModeAndDetailChangeTheNotesPrompt() {
        val meeting = NotesPrompts.minutes("[00:00] 老师：函数", "函数", "", "zh", "meeting", "standard")
        val lesson = NotesPrompts.minutes("[00:00] 老师：函数", "函数", "", "zh", "class", "detailed")
        assertTrue(meeting.system.contains("会议记录撰写者")); assertTrue(meeting.system.contains("标准模式"))
        assertFalse(meeting.user.contains("keyConcepts"))
        assertTrue(lesson.system.contains("课堂笔记整理者")); assertTrue(lesson.system.contains("详细模式"))
        assertTrue(lesson.user.contains("keyConcepts") && lesson.user.contains("reviewQuestions"))
        assertTrue(lesson.user.contains("课程主题（用户填写）：函数"))
    }

    @Test fun summaryPromptIsIncrementalAndHasNoMermaidOnMobile() {
        val first = NotesPrompts.summary(null, listOf(segment(1, "开始")), 900, "周会", "meeting")
        assertTrue(first.user.contains("（暂无，这是第一版摘要）"))
        assertTrue(first.system.contains("900 字以内"))
        assertTrue(first.system.contains("不要输出 Mermaid"))
        val next = NotesPrompts.summary("## 旧摘要", listOf(segment(2, "新内容")), 900, "", "class")
        assertTrue(next.user.contains("## 旧摘要") && next.user.contains("新内容") && !next.user.contains("开始"))
        assertTrue(next.system.contains("课堂摘要"))
        assertEquals("1:01:01", NotesPrompts.timestamp(3_661_000))
        assertEquals("02:05", NotesPrompts.timestamp(125_000))
    }

    @Test fun structuredNotesRenderLikeDesktop() {
        val json = MinutesRenderer.parse("""```json
            {"abstract":"概述。","topics":[{"title":"函数","detail":"对应关系","points":["步骤一"]}],
             "keyConcepts":[{"term":"定义域","explanation":"自变量取值"}],
             "decisions":[{"decision":"采用方案A","context":"成本低"}],
             "actionItems":[{"task":"写报告|草稿","owner":"待定","due":"未定"}],
             "risks":["进度"],"keywords":["函数","映射"],"openQuestions":["何时考试"],
             "chapters":[{"start":"00:00","title":"导入","summary":"背景"}]}
            ```""")!!
        val md = MinutesRenderer.render(json, "class")
        for (part in listOf("## 课程笔记", "概述。", "### 函数", "- 步骤一", "### 核心概念与公式", "#### 定义域",
                "### 关键决议", "- 采用方案A（成本低）", "| 写报告\\|草稿 | 待定 | 未定 |", "- 进度", "关键词：函数、映射",
                "### 待确认问题", "#### 00:00 导入")) assertTrue(part, md.contains(part))
        assertTrue(MinutesRenderer.render(json, "meeting", "en").startsWith("## Minutes"))
        assertNull(MinutesRenderer.parse("no json here"))
    }

    @Test fun minutesUseMeetingModeAndFallBackToRawText() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(chat("""{"abstract":"课程概述","keyConcepts":[{"term":"极限","explanation":"趋近"}]}"""))
            server.enqueue(chat("Plain minutes without JSON"))
            val lesson = Meeting("m1", "微积分", 1, 60_000, "/x.wav", true, false, segments = listOf(segment(0, "今天讲极限。")),
                mode = "class", notesDetail = "standard", language = "zh")
            val rendered = CloudClient(temp.root).createMinutes(lesson, settings(server))
            assertTrue(rendered.contains("## 课程笔记") && rendered.contains("#### 极限"))
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(16_384, body.getInt("max_tokens"))
            val system = body.getJSONArray("messages").getJSONObject(0).getString("content")
            assertTrue(system.contains("课堂笔记整理者") && system.contains("标准模式"))
            assertEquals("Plain minutes without JSON", CloudClient(temp.root).createMinutes(lesson, settings(server)))
        }
    }

    @Test fun translationCarriesTwoPreviousSentencesAndBatchesOfTwenty() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val segments = (1..25).map { segment(it, "第${it}句。") }
            fun reply(ids: IntRange) = chat(JSONObject().put("translations", JSONArray().apply {
                ids.forEach { put(JSONObject().put("id", it).put("text", "T$it")) } }).toString())
            server.enqueue(reply(1..20)); server.enqueue(reply(21..25))
            val out = CloudClient(temp.root).translateSegments(segments, "ja", settings(server), context = listOf("前文A。", "前文B。", "前文C。"))
            assertEquals((1..25).map { "T$it" }, out)
            val first = server.takeRequest().body.readUtf8()
            assertTrue(first.contains("前文B。") && first.contains("前文C。") && !first.contains("前文A。"))
            val second = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages").getJSONObject(1).getString("content")
            assertTrue("next batch uses the previous batch as context", second.startsWith("前文（仅供理解，不要翻译）：\n第19句。\n第20句。"))
        }
    }

    @Test fun askKeepsThreePreviousPairsAndSpeakerSplitIsBatched() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(chat("答案（00:01）"))
            val qa = (1..5).map { QaEntry("问$it", "答$it", it.toLong()) }
            val meeting = Meeting("m", "t", 1, 1, "/x", true, false, segments = listOf(segment(1, "内容")), qa = qa)
            CloudClient(temp.root).ask(meeting, "现在？", settings(server))
            val user = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages").getJSONObject(1).getString("content")
            assertTrue(user.contains("用户：问3") && user.contains("用户：问5") && !user.contains("用户：问2"))

            val many = (1..400).map { segment(it, "句$it") }
            repeat(3) { batch ->
                val ids = many.drop(batch * 150).take(150).map { it.id }
                server.enqueue(chat(JSONObject().put("assignments", JSONArray().apply {
                    ids.forEach { put(JSONObject().put("id", it).put("speaker", if (it % 2 == 0) "说话人2" else "说话人1")) } }).toString()))
            }
            val labels = CloudClient(temp.root).resplitSpeakers(many, settings(server))
            assertEquals(400, labels.size)
            assertEquals("说话人2", labels[400])
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun oldMeetingsDefaultToMeetingModeAndDetailedNotes() {
        val old = MeetingJson.decode(JSONObject("""{"id":"old","createdAt":5,"audioPath":"/old.wav"}"""))
        assertEquals("meeting", old.mode); assertEquals("detailed", old.notesDetail)
        val lesson = old.copy(mode = "class", notesDetail = "standard")
        assertEquals(lesson.mode, MeetingJson.decode(MeetingJson.encode(lesson)).mode)
        assertEquals("standard", MeetingJson.decode(MeetingJson.encode(lesson)).notesDetail)
    }

    @Test fun exportsSrtAndNotesWithoutTranscript() {
        val meeting = Meeting("m", "微积分", 1, 5_000, "/x", true, false, mode = "class",
            segments = listOf(TranscriptSegment(0, 61_234, 62_000, "老师", "极限 --> 定义"), TranscriptSegment(1, 62_000, 63_500, "学生", "明白")),
            summary = "## 要点", minutes = "## 课程笔记\n\n概述", qa = listOf(QaEntry("考什么？", "会议记录中没有提到", 0)))
        val srt = MeetingExport.text(meeting, ExportFormat.Srt)
        assertTrue(srt.startsWith("1\n00:01:01,234 --> 00:01:02,000\n老师: 极限 → 定义\n\n2\n"))
        val notes = MeetingExport.text(meeting, ExportFormat.MarkdownNotes)
        assertTrue(notes.contains("## 课堂摘要") && notes.contains("## 课程笔记") && notes.contains("Q: 考什么？"))
        assertFalse(notes.contains("转写 / Transcript") || notes.contains("极限"))
        assertTrue(MeetingExport.text(meeting, ExportFormat.Markdown).contains("极限"))
        assertTrue(MeetingExport.filename(meeting, ExportFormat.MarkdownNotes).endsWith("-notes.md"))
        assertTrue(MeetingExport.filename(meeting, ExportFormat.Srt).endsWith(".srt"))
    }

    @Test fun overlappingCuesEndWhereTheNextOneStarts() {
        val meeting = Meeting("m", "t", 1, 30_000, "/x", true, false, segments = listOf(
            TranscriptSegment(0, 0, 12_000, "A", "第一段"), TranscriptSegment(1, 10_000, 20_000, "A", "第二段")))
        assertTrue(MeetingExport.text(meeting, ExportFormat.Srt).contains("00:00:00,000 --> 00:00:10,000"))
        assertTrue(MeetingExport.text(meeting, ExportFormat.Vtt).contains("00:00:10.000 --> 00:00:20.000"))
    }
}
