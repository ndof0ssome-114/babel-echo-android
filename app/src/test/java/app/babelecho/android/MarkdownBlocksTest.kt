package app.babelecho.android

import app.babelecho.android.data.MarkdownBlock
import app.babelecho.android.data.MarkdownBlocks
import app.babelecho.android.data.MinutesRenderer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MarkdownBlocksTest {
    @Test fun notesMarkdownBecomesBlocksWithoutLosingText() {
        val md = """
            ## 智能纪要

            概述**重点**在这里。
            第二行。

            ### 待办事项
            | 事项 | 负责人 | 截止 |
            | --- | --- | --- |
            | 写报告\|草稿 | 待定 | 未定 |

            - 第一点
              - 子要点
            1. 步骤一
            - [ ] 任务
            > 引用
            ---
            ```
            code
            ```
        """.trimIndent()
        val blocks = MarkdownBlocks.parse(md)
        assertEquals(MarkdownBlock.Heading(2, "智能纪要"), blocks[0])
        assertEquals(MarkdownBlock.Paragraph("概述**重点**在这里。\n第二行。"), blocks[1])
        assertEquals(MarkdownBlock.Heading(3, "待办事项"), blocks[2])
        val table = blocks[3] as MarkdownBlock.Table
        assertEquals(listOf("事项", "负责人", "截止"), table.header)
        assertEquals(listOf(listOf("写报告|草稿", "待定", "未定")), table.rows)
        assertEquals(MarkdownBlock.Bullet("第一点", 0, "•"), blocks[4])
        assertEquals(MarkdownBlock.Bullet("子要点", 1, "•"), blocks[5])
        assertEquals(MarkdownBlock.Bullet("步骤一", 0, "1."), blocks[6])
        assertEquals(MarkdownBlock.Bullet("任务", 0, "•"), blocks[7])
        assertEquals(MarkdownBlock.Quote("引用"), blocks[8])
        assertEquals(MarkdownBlock.Rule, blocks[9])
        assertEquals(MarkdownBlock.Code("code"), blocks[10])
        assertEquals(listOf(MarkdownBlocks.Span("概述"), MarkdownBlocks.Span("重点", bold = true), MarkdownBlocks.Span("在这里。")),
            MarkdownBlocks.inline("概述**重点**在这里。"))
    }

    @Test fun renderedStructuredNotesParseIntoHeadingsAndATable() {
        val md = MinutesRenderer.render(JSONObject("""{"abstract":"概述","actionItems":[{"task":"写","owner":"王","due":"周五"}]}"""), "meeting")
        val blocks = MarkdownBlocks.parse(md)
        assertTrue(blocks.first() is MarkdownBlock.Heading)
        assertEquals(listOf(listOf("写", "王", "周五")), blocks.filterIsInstance<MarkdownBlock.Table>().single().rows)
        // Plain text without Markdown is one paragraph per block of lines.
        assertEquals(listOf(MarkdownBlock.Paragraph("只是普通文字")), MarkdownBlocks.parse("只是普通文字"))
    }
}
