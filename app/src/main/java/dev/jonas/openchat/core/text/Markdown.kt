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

    /** A block-level LaTeX formula, e.g. `$$…$$` or `\[…\]`. */
    data class Math(val tex: String) : MdBlock
}

sealed interface MdInline {

    data class Text(val text: String) : MdInline
    data class Bold(val children: List<MdInline>) : MdInline
    data class Italic(val children: List<MdInline>) : MdInline
    data class Strike(val children: List<MdInline>) : MdInline
    data class Code(val text: String) : MdInline
    data class Link(val label: String, val url: String) : MdInline

    /** Inline LaTeX math, e.g. `$…$` or `\(…\)`. */
    data class Math(val tex: String) : MdInline
}

/**
 * Small, dependency-free Markdown parser. It intentionally supports the subset LLMs emit most:
 * headings, paragraphs, fenced code, lists, quotes, rules, tables, LaTeX math and the usual
 * inline styles. Unknown syntax degrades to plain text instead of failing.
 */
object MarkdownParser {

    private val heading = Regex("^(#{1,6})\\s+(.*)$")
    private val bullet = Regex("^\\s*[-*+]\\s+(.*)$")
    private val orderedPattern = Regex("^\\s*(\\d{1,9})[.)]\\s+(.*)$")
    private val rule = Regex("^\\s*([-*_])(?:\\s*\\1){2,}\\s*$")
    private val tableSeparator = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
    private val mathEnvironment = Regex("^\\\\begin\\{([^}]+)}")
    private val mathEnvironments = setOf(
        "equation", "equation*", "align", "align*", "aligned", "alignedat",
        "gather", "gather*", "gathered", "multline", "multline*", "split",
        "matrix", "pmatrix", "bmatrix", "Bmatrix", "vmatrix", "Vmatrix",
        "smallmatrix", "cases", "array", "eqnarray", "eqnarray*",
    )
    private val mathFenceLanguages = setOf("math", "latex", "tex", "katex")

    private data class BlockMath(val tex: String, val next: Int)

    /**
     * Parses [source], but never drops the whole message if one unusual construct trips the
     * full parser. On failure it falls back to parsing blank-line separated chunks, so headings,
     * formulas and tables elsewhere in the answer still render normally.
     */
    fun parseRecovering(source: String): List<MdBlock> {
        val normalized = normalize(source)
        return try {
            parse(normalized)
        } catch (_: Throwable) {
            normalized.split(Regex("\\n\\s*\\n")).flatMap { chunk ->
                val trimmed = chunk.trim()
                if (trimmed.isEmpty()) {
                    emptyList()
                } else {
                    try {
                        parse(trimmed)
                    } catch (_: Throwable) {
                        fallbackBlocks(trimmed)
                    }
                }
            }
        }
    }

    /**
     * Last-resort rendering for a chunk the full parser rejected. It still recognises the most
     * useful shapes (headings, display math and inline math) instead of leaking raw
     * Markdown/LaTeX to the UI.
     */
    private fun fallbackBlocks(chunk: String): List<MdBlock> {
        val lines = chunk.lines()
        val blocks = mutableListOf<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                i++
                continue
            }

            val headingMatch = heading.matchEntire(trimmed)
            if (headingMatch != null) {
                blocks += MdBlock.Heading(
                    level = headingMatch.groupValues[1].length,
                    inlines = parseInlineSafe(headingMatch.groupValues[2].trim()),
                )
                i++
                continue
            }

            val open = when {
                trimmed.startsWith("\\[") -> "\\["
                trimmed.startsWith("$$") -> "$$"
                else -> null
            }
            if (open != null) {
                val close = if (open == "\\[") "\\]" else "$$"
                val afterOpen = trimmed.substring(open.length)
                if (afterOpen.contains(close)) {
                    blocks += MdBlock.Math(afterOpen.substringBefore(close).trim())
                    i++
                    continue
                }
                val content = StringBuilder(afterOpen)
                var j = i + 1
                var found = false
                while (j < lines.size) {
                    val end = lines[j].indexOf(close)
                    if (end >= 0) {
                        content.append('\n').append(lines[j].substring(0, end))
                        j++
                        found = true
                        break
                    }
                    content.append('\n').append(lines[j])
                    j++
                }
                if (found) {
                    blocks += MdBlock.Math(content.toString().trim())
                    i = j
                    continue
                }
            }

            val paragraph = mutableListOf<String>()
            while (i < lines.size) {
                val current = lines[i]
                val currentTrimmed = current.trim()
                if (currentTrimmed.isEmpty() ||
                    heading.matchEntire(currentTrimmed) != null ||
                    currentTrimmed.startsWith("\\[") ||
                    currentTrimmed.startsWith("$$")
                ) {
                    break
                }
                paragraph += currentTrimmed
                i++
            }
            if (paragraph.isEmpty()) {
                paragraph += trimmed
                i++
            }
            blocks += MdBlock.Paragraph(parseInlineSafe(paragraph.joinToString(" ")))
        }
        return blocks
    }

    private fun parseInlineSafe(text: String): List<MdInline> =
        try {
            parseInline(text)
        } catch (_: Throwable) {
            listOf(MdInline.Text(text))
        }

    /** Removes invisible characters that models occasionally insert and would break block syntax. */
    private fun normalize(source: String): String = source
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace("\uFEFF", "")
        .replace("\u200B", "")
        .replace('\u00A0', ' ')

    fun parse(source: String): List<MdBlock> {
        val lines = normalize(source).split('\n')
        val blocks = mutableListOf<MdBlock>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                i++
                continue
            }

            val trimmed = line.trimStart()

            val math = mathBlockAt(lines, i)
            if (math != null) {
                if (math.tex.isNotBlank()) {
                    blocks += MdBlock.Math(math.tex)
                }
                i = math.next
                continue
            }

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
                    if (language.lowercase() in mathFenceLanguages) {
                        blocks += MdBlock.Math(code.toString().trim())
                    } else {
                        blocks += MdBlock.CodeBlock(language, code.toString(), closed)
                    }
                }

                heading.matches(trimmed) -> {
                    val match = heading.matchEntire(trimmed)!!
                    blocks += MdBlock.Heading(
                        level = match.groupValues[1].length,
                        inlines = parseInline(match.groupValues[2].trim()),
                    )
                    i++
                }

                rule.matches(trimmed) -> {
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

                bullet.matches(trimmed) -> {
                    val result = parseList(lines, i, ordered = false)
                    blocks += result.first
                    i = result.second
                }

                orderedPattern.matches(trimmed) -> {
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
            mathBlockAt(lines, index) != null ||
            heading.matches(trimmed) ||
            rule.matches(trimmed) ||
            bullet.matches(trimmed) ||
            orderedPattern.matches(trimmed) ||
            isTableStart(lines, index)
    }

    /**
     * Recognizes the display-math forms LLMs commonly emit: `$$…$$`, `\[…\]` and standalone
     * LaTeX environments such as `\begin{aligned}…\end{aligned}`.
     */
    private fun mathBlockAt(lines: List<String>, index: Int): BlockMath? {
        val trimmed = lines[index].trimStart()
        return when {
            trimmed.startsWith("$$") -> mathUntil(lines, index, "$$", "$$")
            trimmed.startsWith("\\[") -> mathUntil(lines, index, "\\[", "\\]")
            trimmed.startsWith("\\begin{") -> environmentAt(lines, index)
            else -> null
        }
    }

    private fun mathUntil(
        lines: List<String>,
        index: Int,
        open: String,
        close: String,
    ): BlockMath? {
        val trimmed = lines[index].trimStart()
        val afterOpen = trimmed.substring(open.length)
        val sameLine = afterOpen.indexOf(close)
        if (sameLine >= 0) {
            if (afterOpen.substring(sameLine + close.length).isNotBlank()) return null
            return BlockMath(afterOpen.substring(0, sameLine).trim(), index + 1)
        }

        val content = StringBuilder()
        if (afterOpen.isNotBlank()) content.append(afterOpen)
        var i = index + 1
        while (i < lines.size) {
            val line = lines[i]
            val end = line.indexOf(close)
            if (end >= 0) {
                if (line.substring(end + close.length).isNotBlank()) return null
                if (end > 0) {
                    if (content.isNotEmpty()) content.append('\n')
                    content.append(line.substring(0, end))
                }
                return BlockMath(content.toString().trim(), i + 1)
            }
            if (content.isNotEmpty()) content.append('\n')
            content.append(line)
            i++
        }
        return null
    }

    private fun environmentAt(lines: List<String>, index: Int): BlockMath? {
        val trimmed = lines[index].trimStart()
        val environment = mathEnvironment.find(trimmed)?.groupValues?.getOrNull(1) ?: return null
        if (environment !in mathEnvironments) return null
        val endMarker = "\\end{$environment}"
        val content = StringBuilder()
        var i = index
        while (i < lines.size) {
            val line = lines[i]
            val end = line.indexOf(endMarker)
            if (end >= 0) {
                if (line.substring(end + endMarker.length).isNotBlank()) return null
                if (end > 0) {
                    if (content.isNotEmpty()) content.append('\n')
                    content.append(line.substring(0, end))
                }
                return BlockMath(content.toString().trim(), i + 1)
            }
            if (content.isNotEmpty()) content.append('\n')
            content.append(line)
            i++
        }
        return null
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

        // Do not split on pipes that are escaped or that belong to inline code/math, so values
        // such as `$|x|$` or `\|` stay in one cell.
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            when {
                ch == '\\' && i + 1 < value.length -> {
                    current.append(ch).append(value[i + 1])
                    i += 2
                }
                ch == '`' -> {
                    val run = countRun(value, i, '`')
                    val marker = "`".repeat(run)
                    val end = value.indexOf(marker, i + run)
                    if (end >= 0) {
                        current.append(value, i, end + run)
                        i = end + run
                    } else {
                        current.append(ch)
                        i++
                    }
                }
                ch == '$' -> {
                    val math = inlineDollarMath(value, i)
                    if (math != null) {
                        current.append(value, i, math.second)
                        i = math.second
                    } else {
                        current.append(ch)
                        i++
                    }
                }
                ch == '|' -> {
                    cells += current.toString().trim()
                    current.clear()
                    i++
                }
                else -> {
                    current.append(ch)
                    i++
                }
            }
        }
        cells += current.toString().trim()
        return cells
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
                text.startsWith("\\(", i) -> {
                    val end = text.indexOf("\\)", i + 2)
                    if (end > i + 2) {
                        flush()
                        out += MdInline.Math(text.substring(i + 2, end))
                        i = end + 2
                    } else {
                        plain.append(text[i + 1])
                        i += 2
                    }
                }

                text.startsWith("\\[", i) -> {
                    val end = text.indexOf("\\]", i + 2)
                    if (end > i + 2) {
                        flush()
                        out += MdInline.Math(text.substring(i + 2, end))
                        i = end + 2
                    } else {
                        plain.append(text[i + 1])
                        i += 2
                    }
                }

                ch == '\\' && i + 1 < text.length -> {
                    plain.append(text[i + 1])
                    i += 2
                }

                ch == '$' -> {
                    val math = inlineDollarMath(text, i)
                    if (math != null) {
                        flush()
                        out += MdInline.Math(math.first)
                        i = math.second
                    } else {
                        plain.append(ch)
                        i++
                    }
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

    /**
     * Finds `$…$` or `$$…$$` math without swallowing prices such as "$5 and $10".
     * Returns the formula and the index just past the closing delimiter.
     */
    private fun inlineDollarMath(text: String, start: Int): Pair<String, Int>? {
        val double = text.startsWith("$$", start)
        val contentStart = start + if (double) 2 else 1
        var end = contentStart
        while (end < text.length) {
            if (!double && text[end] == '\\') {
                end += 2
                continue
            }
            if (double && text.startsWith("$$", end)) break
            if (!double && text[end] == '$') break
            if (text[end] == '\n') return null
            end++
        }
        if (end >= text.length) return null
        val closeLength = if (double) 2 else 1
        if (double && !text.startsWith("$$", end)) return null
        val tex = text.substring(contentStart, end)
        if (tex.isBlank() || tex.first().isWhitespace() || tex.last().isWhitespace()) return null
        // A closing dollar followed by a digit is almost always a price range ("$5$10").
        if (!double && end + closeLength < text.length && text[end + closeLength].isDigit()) return null
        return tex to end + closeLength
    }

    private fun countRun(text: String, start: Int, ch: Char): Int {
        var i = start
        while (i < text.length && text[i] == ch) i++
        return i - start
    }
}
