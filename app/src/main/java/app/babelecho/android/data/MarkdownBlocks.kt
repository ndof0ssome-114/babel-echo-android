package app.babelecho.android.data

/**
 * The small Markdown subset that summaries and notes use (headings, lists, tables, quotes,
 * code, paragraphs), split into blocks for native display. Desktop renders full Markdown with
 * markdown-it; mobile only needs it to read cleanly instead of showing raw `#` and `|`.
 * Anything unrecognised stays a paragraph, so no text is ever dropped.
 */
sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Bullet(val text: String, val depth: Int, val marker: String) : MarkdownBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Code(val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data object Rule : MarkdownBlock
}

object MarkdownBlocks {
    private val heading = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val bullet = Regex("^(\\s*)([-*+]|\\d{1,3}[.)])\\s+(?:\\[[ xX]]\\s+)?(.*)$")
    private val tableDivider = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
    private val rule = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")

    fun parse(markdown: String): List<MarkdownBlock> {
        val lines = markdown.replace("\r\n", "\n").split('\n')
        val out = mutableListOf<MarkdownBlock>()
        val paragraph = mutableListOf<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) out += MarkdownBlock.Paragraph(paragraph.joinToString("\n").trim())
            paragraph.clear()
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> flush()
                trimmed.startsWith("```") -> {
                    flush()
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) { code += lines[i]; i++ }
                    out += MarkdownBlock.Code(code.joinToString("\n"))
                }
                heading.matches(trimmed) -> {
                    flush()
                    val match = heading.matchEntire(trimmed)!!
                    out += MarkdownBlock.Heading(match.groupValues[1].length, match.groupValues[2])
                }
                rule.matches(trimmed) -> { flush(); out += MarkdownBlock.Rule }
                trimmed.startsWith("|") && i + 1 < lines.size && tableDivider.matches(lines[i + 1]) -> {
                    flush()
                    val header = cells(trimmed)
                    val rows = mutableListOf<List<String>>()
                    i += 2
                    while (i < lines.size && lines[i].trim().startsWith("|")) { rows += cells(lines[i].trim()); i++ }
                    out += MarkdownBlock.Table(header, rows)
                    continue
                }
                trimmed.startsWith(">") -> { flush(); out += MarkdownBlock.Quote(trimmed.removePrefix(">").trim()) }
                bullet.matches(line) -> {
                    flush()
                    val match = bullet.matchEntire(line)!!
                    val marker = match.groupValues[2].let { if (it.first().isDigit()) it else "•" }
                    out += MarkdownBlock.Bullet(match.groupValues[3], match.groupValues[1].replace("\t", "  ").length / 2, marker)
                }
                else -> paragraph += line
            }
            i++
        }
        flush()
        return out
    }

    /** Splits a table row on unescaped pipes; `\|` stays a literal pipe. */
    fun cells(row: String): List<String> {
        val body = row.trim().removePrefix("|").let { if (it.endsWith("|") && !it.endsWith("\\|")) it.dropLast(1) else it }
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        while (index < body.length) {
            val c = body[index]
            if (c == '\\' && index + 1 < body.length && body[index + 1] == '|') { current.append('|'); index += 2; continue }
            if (c == '|') { out += current.toString().trim(); current.clear() } else current.append(c)
            index++
        }
        out += current.toString().trim()
        return out
    }

    /** Inline spans: **bold**, `code`; everything else is plain text. */
    data class Span(val text: String, val bold: Boolean = false, val code: Boolean = false)

    fun inline(text: String): List<Span> {
        val out = mutableListOf<Span>()
        val pattern = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`")
        var last = 0
        for (match in pattern.findAll(text)) {
            if (match.range.first > last) out += Span(text.substring(last, match.range.first))
            when {
                match.groupValues[1].isNotEmpty() -> out += Span(match.groupValues[1], bold = true)
                match.groupValues[2].isNotEmpty() -> out += Span(match.groupValues[2], bold = true)
                else -> out += Span(match.groupValues[3], code = true)
            }
            last = match.range.last + 1
        }
        if (last < text.length) out += Span(text.substring(last))
        return out
    }
}
