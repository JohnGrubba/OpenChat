package dev.jonas.openchat.core.text

sealed interface MdBlock {
    data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock
    data class Paragraph(val inlines: List<MdInline>) : MdBlock
    data class CodeBlock(
        val language: String,
        val code: String,
        val closed: Boolean,
    ) : MdBlock

    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data class BulletList(val items: List<List<MdBlock>>) : MdBlock
    data class OrderedList(val start: Int, val items: List<List<MdBlock>>) : MdBlock
    data object Divider : MdBlock
    data class Table(
        val headers: List<List<MdInline>>,
        val rows: List<List<List<MdInline>>>,
    ) : MdBlock
}

sealed interface MdInline {
    data class Text(val text: String) : MdInline
    data class Bold(val children: List<MdInline>) : MdInline
    data class Italic(val children: List<MdInline>) : MdInline
    data class Strike(val children: List<MdInline>) : MdInline
    data class Code(val text: String) : MdInline
    data class Link(val label: String, val url: String) : MdInline
}

/**
 * Small, dependency-free Markdown parser. It intentionally supports the subset LLMs emit most:
 * headings, paragraphs, fenced code, lists, quotes, rules, tables and the usual inline styles.
 * Unknown syntax degrades to plain text instead of failing.
 */
object MarkdownParser {

    private val heading = Regex("^(#{1,6})\\s+(.*)$")
    private val bullet = Regex("^\\s*[-*+]\\s+(.*)$")
    private val orderedPattern = Regex("^\\s*(\\d{1,9})[.)]\\s+(.*)$")
    private val rule = Regex("^\\s*([-*_])(?:\\s*\\1){2,}\\s*$")
    private val tableSeparator = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    fun parse(source: String): List<MdBlock> {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = mutableListOf<MdBlock>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                i++
                continue
            }

            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                    val fence = if (trimmed.startsWith("```")) "```" else "~~~"
                    val language = trimmed.removePrefix(fence).trim().substringBefore(' ')
                    val code = StringBuilder()
                    var closed = false
                    i++
                    while (i < lines.size) {
                        if (lines[i].trimStart().startsWith(fence)) {
                            closed = true
                            i++
                            break
                        }
                        if (code.isNotEmpty()) code.append('\n')
                        code.append(lines[i])
                        i++
                    }
                    blocks += MdBlock.CodeBlock(language, code.toString(), closed)
                }

                heading.matches(line) -> {
                    val match = heading.matchEntire(line)!!
                    blocks += MdBlock.Heading(
                        level = match.groupValues[1].length,
                        inlines = parseInline(match.groupValues[2].trim()),
                    )
                    i++
                }

                rule.matches(line) -> {
                    blocks += MdBlock.Divider
                    i++
                }

                trimmed.startsWith(">") -> {
                    val quoteLines = mutableListOf<String>()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                        val raw = lines[i].trimStart().removePrefix(">")
                        quoteLines += raw.removePrefix(" ")
                        i++
                    }
                    blocks += MdBlock.Quote(parse(quoteLines.joinToString("\n")))
                }

                bullet.matches(line) -> {
                    val result = parseList(lines, i, ordered = false)
                    blocks += result.first
                    i = result.second
                }

                orderedPattern.matches(line) -> {
                    val result = parseList(lines, i, ordered = true)
                    blocks += result.first
                    i = result.second
                }

                isTableStart(lines, i) -> {
                    val result = parseTable(lines, i)
                    blocks += result.first
                    i = result.second
                }

                else -> {
                    val paragraph = mutableListOf<String>()
                    while (i < lines.size && lines[i].isNotBlank() && !startsBlock(lines, i)) {
                        paragraph += lines[i].trim()
                        i++
                    }
                    if (paragraph.isEmpty()) {
                        paragraph += line.trim()
                        i++
                    }
                    blocks += MdBlock.Paragraph(parseInline(paragraph.joinToString(" ")))
                }
            }
        }
        return blocks
    }

    private fun startsBlock(lines: List<String>, index: Int): Boolean {
        val line = lines[index]
        val trimmed = line.trimStart()
        return trimmed.startsWith("```") ||
            trimmed.startsWith("~~~") ||
            trimmed.startsWith(">") ||
            heading.matches(line) ||
            rule.matches(line) ||
            bullet.matches(line) ||
            orderedPattern.matches(line) ||
            isTableStart(lines, index)
    }

    private fun parseList(
        lines: List<String>,
        startIndex: Int,
        ordered: Boolean,
    ): Pair<MdBlock, Int> {
        val items = mutableListOf<List<MdBlock>>()
        var startNumber = 1
        var i = startIndex

        while (i < lines.size) {
            val line = lines[i]
            val match = if (ordered) orderedPattern.matchEntire(line) else bullet.matchEntire(line)
            if (match == null) break
            if (ordered && items.isEmpty()) startNumber = match.groupValues[1].toIntOrNull() ?: 1
            val content = if (ordered) match.groupValues[2] else match.groupValues[1]
            val itemLines = mutableListOf(content)
            i++

            while (i < lines.size) {
                val next = lines[i]
                if (next.isBlank()) break
                val nextIsItem = if (ordered) {
                    orderedPattern.matches(next)
                } else {
                    bullet.matches(next)
                }
                if (nextIsItem) break
                if (next.takeWhile { it == ' ' || it == '\t' }.length >= 2) {
                    itemLines += next.trimStart()
                    i++
                } else {
                    break
                }
            }

            items += parse(itemLines.joinToString("\n"))
            while (i < lines.size && lines[i].isBlank()) {
                if (i + 1 < lines.size && (if (ordered) orderedPattern.matches(lines[i + 1]) else bullet.matches(lines[i + 1]))) {
                    i++
                } else {
                    break
                }
            }
        }

        return if (ordered) {
            MdBlock.OrderedList(startNumber, items) to i
        } else {
            MdBlock.BulletList(items) to i
        }
    }

    private fun isTableStart(lines: List<String>, index: Int): Boolean {
        if (index + 1 >= lines.size) return false
        val header = lines[index]
        val separator = lines[index + 1]
        return header.contains('|') && separator.contains('-') && tableSeparator.matches(separator)
    }

    private fun parseTable(lines: List<String>, startIndex: Int): Pair<MdBlock, Int> {
        val headers = splitRow(lines[startIndex]).map(::parseInline)
        val rows = mutableListOf<List<List<MdInline>>>()
        var i = startIndex + 2
        while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
            rows += splitRow(lines[i]).map(::parseInline)
            i++
        }
        return MdBlock.Table(headers, rows) to i
    }

    private fun splitRow(line: String): List<String> {
        var value = line.trim()
        if (value.startsWith("|")) value = value.substring(1)
        if (value.endsWith("|")) value = value.dropLast(1)
        return value.split('|').map { it.trim() }
    }

    fun parseInline(text: String): List<MdInline> {
        val out = mutableListOf<MdInline>()
        val plain = StringBuilder()
        var i = 0

        fun flush() {
            if (plain.isNotEmpty()) {
                out += MdInline.Text(plain.toString())
                plain.clear()
            }
        }

        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '\\' && i + 1 < text.length -> {
                    plain.append(text[i + 1])
                    i += 2
                }

                ch == '`' -> {
                    val run = countRun(text, i, '`')
                    val marker = "`".repeat(run)
                    val end = text.indexOf(marker, i + run)
                    if (end >= 0) {
                        flush()
                        out += MdInline.Code(text.substring(i + run, end).trim())
                        i = end + run
                    } else {
                        plain.append(marker)
                        i += run
                    }
                }

                text.startsWith("**", i) || text.startsWith("__", i) -> {
                    val marker = text.substring(i, i + 2)
                    val end = text.indexOf(marker, i + 2)
                    if (end > i + 2) {
                        flush()
                        out += MdInline.Bold(parseInline(text.substring(i + 2, end)))
                        i = end + 2
                    } else {
                        plain.append(marker)
                        i += 2
                    }
                }

                text.startsWith("~~", i) -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end > i + 2) {
                        flush()
                        out += MdInline.Strike(parseInline(text.substring(i + 2, end)))
                        i = end + 2
                    } else {
                        plain.append("~~")
                        i += 2
                    }
                }

                ch == '*' || ch == '_' -> {
                    val end = text.indexOf(ch, i + 1)
                    if (end > i + 1 && !text[i + 1].isWhitespace()) {
                        flush()
                        out += MdInline.Italic(parseInline(text.substring(i + 1, end)))
                        i = end + 1
                    } else {
                        plain.append(ch)
                        i++
                    }
                }

                ch == '[' -> {
                    val close = text.indexOf(']', i + 1)
                    if (close > i && close + 1 < text.length && text[close + 1] == '(') {
                        val end = text.indexOf(')', close + 2)
                        if (end > close) {
                            flush()
                            val label = text.substring(i + 1, close)
                            val url = text.substring(close + 2, end).trim()
                            out += MdInline.Link(label, url)
                            i = end + 1
                        } else {
                            plain.append(ch)
                            i++
                        }
                    } else {
                        plain.append(ch)
                        i++
                    }
                }

                ch == '<' -> {
                    val end = text.indexOf('>', i + 1)
                    if (end > i) {
                        val candidate = text.substring(i + 1, end)
                        if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
                            flush()
                            out += MdInline.Link(candidate, candidate)
                            i = end + 1
                        } else {
                            plain.append(ch)
                            i++
                        }
                    } else {
                        plain.append(ch)
                        i++
                    }
                }

                else -> {
                    plain.append(ch)
                    i++
                }
            }
        }
        flush()
        return out
    }

    private fun countRun(text: String, start: Int, ch: Char): Int {
        var i = start
        while (i < text.length && text[i] == ch) i++
        return i - start
    }
}
