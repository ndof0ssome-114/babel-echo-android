package app.babelecho.android.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Every text-model prompt, ported from the desktop `lib/prompts.mjs` so the three platforms
 * summarise, write notes, translate and answer the same way. Keep the wording in step with the
 * desktop file; differences are deliberate and marked (mobile has no Mermaid renderer).
 */
object NotesPrompts {
    data class Messages(val system: String, val user: String)

    const val MODE_MEETING = "meeting"
    const val MODE_CLASS = "class"
    const val DETAIL_STANDARD = "standard"
    const val DETAIL_DETAILED = "detailed"

    fun normalizeMode(mode: String?) = if (mode == MODE_CLASS) MODE_CLASS else MODE_MEETING
    fun normalizeDetail(detail: String?) = if (detail == DETAIL_STANDARD) DETAIL_STANDARD else DETAIL_DETAILED

    private val languages = mapOf("zh" to "简体中文", "ja" to "日本語", "en" to "English", "ko" to "한국어",
        "fr" to "Français", "de" to "Deutsch", "es" to "Español", "auto" to "与原文一致")

    fun langName(code: String): String = languages[code] ?: code

    /** Desktop `ts()`: h:mm:ss past an hour, otherwise mm:ss. */
    fun timestamp(ms: Long): String {
        val total = (ms.coerceAtLeast(0) + 500) / 1_000
        val h = total / 3_600
        val m = total % 3_600 / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    fun renderTranscript(segments: List<TranscriptSegment>): String = segments.joinToString("\n") {
        "[${timestamp(it.startMs)}] ${it.speaker.ifBlank { "说话人" }}：${it.text}"
    }

    /** Rolling summary: previous summary plus only the transcript added since. */
    fun summary(previous: String?, newSegments: List<TranscriptSegment>, maxChars: Int, title: String, mode: String): Messages {
        val classroom = normalizeMode(mode) == MODE_CLASS
        val system = listOf(
            if (classroom) "你是一名严谨的学习笔记助手，正在为一堂课生成「课堂摘要」。重点保留知识框架、概念定义、公式条件、推导步骤、例题方法、老师强调的重点、易错点和明确布置的作业；不要套用商务会议的决议、负责人和风险模板。"
            else "你是一名资深会议记录员，正在为一场进行中的会议生成「实时摘要」。重点保留议题、论据、讨论分歧、决策依据、行动与未决问题。",
            "要求：",
            "1. 只写已经明确出现的信息，绝对不要推测或补充不存在的内容。",
            "2. 用 Markdown 二级标题和短要点；适合比较的事实可整理成表格，明确待办可用任务列表。",
            "3. 保留具体的数字、金额、日期、人名、产品名、专有名词（原文照抄，不要改写）。",
            "4. 若出现分歧、未定事项、明确结论，单独标注。",
            "5. 输出的是「更新后的完整摘要」，覆盖旧摘要，不要写成增量变更说明。",
            "6. 篇幅控制在 $maxChars 字以内，宁可精炼也不要注水。",
            "7. 直接输出摘要正文，不要任何寒暄或解释。",
            // Mobile differs here: there is no Mermaid renderer, so diagrams would show as code.
            "8. 不要输出 Mermaid 或其他图表代码。",
        ).joinToString("\n")
        val user = buildList {
            if (title.isNotBlank()) add((if (classroom) "课程主题：" else "会议主题：") + title)
            add("【已有摘要】")
            add(previous?.takeIf { it.isNotBlank() } ?: "（暂无，这是第一版摘要）")
            add("")
            add("【新增转写内容】")
            add(renderTranscript(newSegments).ifBlank { "（无）" })
            add("")
            add("请输出覆盖了上述全部内容的、更新后的" + if (classroom) "课堂摘要。" else "会议摘要。")
        }.joinToString("\n")
        return Messages(system, user)
    }

    /** Final structured notes; the reply is one JSON object rendered by [MinutesRenderer]. */
    fun minutes(transcript: String, title: String, summary: String, targetLang: String, mode: String, notesDetail: String): Messages {
        val classroom = normalizeMode(mode) == MODE_CLASS
        val detailed = normalizeDetail(notesDetail) == DETAIL_DETAILED
        val system = listOf(
            if (classroom) "你是一名严谨的课堂笔记整理者。请把完整课堂转写整理成可用于复习的课程笔记，而不是商务会议纪要。"
            else "你是一名专业的会议记录撰写者。请把完整会议转写整理成信息充分、能回溯讨论过程的结构化纪要。",
            "你会收到完整转写（含时间戳与说话人），实时摘要仅供参考，不能代替完整转写或作为删减信息的依据。",
            if (detailed) "详细模式：不要压缩成几个结论。按实际讲授/讨论顺序完整覆盖主要话题，topics 写清背景、解释、依据、过程、例子、限制与结论，points 保留具体步骤和细节；概述在素材充分时写 6–12 句，但不能凑字。"
            else "标准模式：保留每个主要话题及必要解释、依据和关键细节，合并重复表达；不要只列无解释的关键词。",
            if (classroom) "课堂重点：知识框架、定义与适用条件、公式符号含义、推导过程、例题步骤与解法、概念间关系、易错点、老师强调内容、作业与明确的考试提示。keyConcepts 解释概念；examples 保留讲过的例子和步骤；reviewQuestions 只根据已讲内容整理复习问答，答案不能引入课外知识。未提及考试重点时禁止自行标成必考。没有真正的决议/负责人时，对应会议字段留空。"
            else "会议重点：各方观点及依据、备选方案与取舍、关键决议的背景、执行细节、风险的影响和待确认问题。不能把未定讨论写成已达成的共识。",
            "硬性要求：",
            "1. 只使用转写中出现的信息，禁止编造人名、数字、日期或结论。",
            "2. 待办事项的负责人和截止时间只能来自原文；原文没提到就写「待定」「未定」。",
            "3. 章节按真实话题转折与时间戳划分，不固定为 2–6 章；长内容允许更多章节。保留信息量但不逐字抄写转写，不添加未出现的内容；无依据的字段使用空数组。",
            "4. 输出语言：${langName(targetLang.ifBlank { "zh" })}。",
            "5. 只输出一个 JSON 对象，不要 Markdown 代码块，不要任何解释文字。",
        ).joinToString("\n")
        val schema = JSONObject()
            .put("title", if (classroom) "课程主题" else "一句话会议主题")
            .put("abstract", if (detailed) "素材充分时用6–12句概述，覆盖主要内容；禁止补写事实" else "整体概述，包含主要内容与必要背景")
            .put("participants", JSONArray().put("出现的说话人/人名"))
            .put("decisions", JSONArray().put(JSONObject().put("decision", "已明确达成的结论").put("context", "依据")))
            .put("actionItems", JSONArray().put(JSONObject().put("task", "待办事项").put("owner", "负责人，未提及则写\"待定\"").put("due", "截止时间，未提及则写\"未定\"")))
            .put("risks", JSONArray().put(JSONObject().put("risk", "风险、分歧或阻塞").put("impact", "影响")))
            .put("openQuestions", JSONArray().put("尚未解决的问题"))
            .put("keywords", JSONArray().put("关键词/专有名词"))
            .put("topics", JSONArray().put(JSONObject().put("title", "话题或知识点标题").put("detail", "完整解释、依据、过程、条件与结论").put("points", JSONArray().put("具体细节、步骤与原文例子"))))
            .put("chapters", JSONArray().put(JSONObject().put("title", "章节标题").put("start", "原文起始时间 mm:ss").put("summary", "该章的内容、解释与具体细节")))
            .apply {
                if (classroom) {
                    put("keyConcepts", JSONArray().put(JSONObject().put("term", "原文概念或公式").put("explanation", "定义、符号含义和适用条件")))
                    put("examples", JSONArray().put(JSONObject().put("title", "原文例题或案例").put("explanation", "原文条件、步骤、方法和结果")))
                    put("reviewQuestions", JSONArray().put(JSONObject().put("question", "基于所讲内容的复习问题").put("answer", "只依据原文的回答")))
                }
            }
        val user = listOfNotNull(
            title.takeIf { it.isNotBlank() }?.let { (if (classroom) "课程主题（用户填写）：" else "会议主题（用户填写）：") + it },
            summary.takeIf { it.isNotBlank() }?.let { "【实时摘要（参考，可修正）】\n$it" },
            "【完整转写记录】",
            transcript,
            "",
            "请严格按以下 JSON 结构输出（字段名保持英文，值用目标语言）：",
            schema.toString(2),
        ).joinToString("\n")
        return Messages(system, user)
    }

    /** Batch translation; earlier sentences are context only and fix pronouns and omissions. */
    fun translate(items: List<Pair<Int, String>>, target: String, context: List<String>): Messages {
        val system = listOf(
            "你是专业会议口译员。你会收到若干条带编号的会议转写片段。",
            "要求：",
            "1. 翻译成 ${langName(target)}，保持口语自然、术语准确。",
            "2. 保留原有语气与信息量，不要总结、不要增删。",
            "3. 只有在该条内容确实已经是${langName(target)}时才原样返回；其余一律翻译，短句也必须翻译。",
            "4. 专业名词、产品名、人名按业界习惯处理（可保留原文并加括号）。",
            "5. 只输出 JSON：{\"translations\":[{\"id\":<编号>,\"text\":\"<译文>\"}]}，不要代码块，不要解释。",
            "6. 片段按时间顺序排列，可能承接上文；结合前文理解指代和省略，但每条只翻译它自己的内容。",
        ).joinToString("\n")
        val json = JSONArray().apply { items.forEach { (id, text) -> put(JSONObject().put("id", id).put("text", text)) } }
        val before = if (context.isEmpty()) "" else "前文（仅供理解，不要翻译）：\n" + context.joinToString("\n") + "\n\n"
        return Messages(system, before + "待翻译片段：\n" + json.toString(2))
    }

    /** Grounded Q&A over the transcript; [history] holds earlier (question, answer) pairs. */
    fun ask(question: String, transcript: String, summary: String, history: List<Pair<String, String>>): Messages {
        val system = listOf(
            "你是这场会议的助理。你只能依据提供的转写记录和摘要回答。",
            "要求：",
            "1. 如果转写里没有相关信息，直接说「会议记录中没有提到」，不要编造。",
            "2. 回答要引用时间戳，例如「（12:34）」。",
            "3. 简洁，用要点列出，不要重复整段原文。",
        ).joinToString("\n")
        // Desktop keeps the last six messages, i.e. three question/answer pairs.
        val conversation = history.takeLast(3).joinToString("\n") { (q, a) -> "用户：$q\n助手：$a" }
        val user = listOfNotNull(
            summary.takeIf { it.isNotBlank() }?.let { "【会议摘要】\n$it" },
            "【转写记录】",
            transcript,
            conversation.takeIf { it.isNotBlank() }?.let { "【此前的问答】\n$it" },
            "【当前问题】",
            question,
        ).joinToString("\n")
        return Messages(system, user)
    }

    /** Text-only speaker attribution, sent in batches. */
    fun speakerSplit(segments: List<TranscriptSegment>, known: Collection<String>): Messages {
        val system = listOf(
            "你会收到一段会议转写，其中所有内容都被标成了同一个说话人。",
            "请根据称呼、语气、应答、问答关系等语言线索，把片段归到不同的说话人。",
            "不要修改任何文字内容，只做归属。",
            "只输出 JSON：{\"assignments\":[{\"id\":<片段编号>,\"speaker\":\"说话人N\"}]}，不要解释。",
            "若确实无法区分，全部归为「说话人1」。",
        ).joinToString("\n")
        val items = JSONArray().apply { segments.forEach { put(JSONObject().put("id", it.id).put("start", it.startMs).put("text", it.text)) } }
        return Messages(system, "已知说话人：" + known.joinToString("、") + "\n\n片段：\n" + items.toString(2))
    }
}
