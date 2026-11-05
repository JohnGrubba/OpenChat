package dev.jonas.openchat.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    @Test
    fun `headings and inline styles`() {
        val blocks = MarkdownParser.parse("# Hello **world** and `code`")
        assertEquals(1, blocks.size)
        val heading = blocks.first() as MdBlock.Heading
        assertEquals(1, heading.level)
        assertTrue(heading.inlines.any { it is MdInline.Bold })
        assertTrue(heading.inlines.any { it is MdInline.Code })
    }

    @Test
    fun `fenced code keeps language and content`() {
        val blocks = MarkdownParser.parse(
            """
            ```kotlin
            fun main() {
                println("hi")
            }
            ```
            """.trimIndent(),
        )
        val code = blocks.single() as MdBlock.CodeBlock
        assertEquals("kotlin", code.language)
        assertTrue(code.code.contains("fun main"))
        assertTrue(code.closed)
    }

    @Test
    fun `unclosed fence while streaming is still rendered`() {
        val blocks = MarkdownParser.parse("```python\nprint('hi')")
        val code = blocks.single() as MdBlock.CodeBlock
        assertEquals("python", code.language)
        assertEquals(false, code.closed)
    }

    @Test
    fun `bullet and ordered lists`() {
        val bullets = MarkdownParser.parse("- one\n- two\n- three")
        val bulletList = bullets.single() as MdBlock.BulletList
        assertEquals(3, bulletList.items.size)

        val ordered = MarkdownParser.parse("1. first\n2. second")
        val orderedList = ordered.single() as MdBlock.OrderedList
        assertEquals(1, orderedList.start)
        assertEquals(2, orderedList.items.size)
    }

    @Test
    fun `tables are parsed`() {
        val blocks = MarkdownParser.parse(
            "| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |",
        )
        val table = blocks.single() as MdBlock.Table
        assertEquals(2, table.headers.size)
        assertEquals(2, table.rows.size)
    }

    @Test
    fun `quotes and dividers`() {
        val blocks = MarkdownParser.parse("> quoted\n\n---\n")
        assertTrue(blocks.first() is MdBlock.Quote)
        assertTrue(blocks.last() is MdBlock.Divider)
    }

    @Test
    fun `inline links and autolinks`() {
        val inlines = MarkdownParser.parseInline("See [docs](https://example.com) or <https://x.dev>")
        val links = inlines.filterIsInstance<MdInline.Link>()
        assertEquals(2, links.size)
        assertEquals("https://example.com", links[0].url)
        assertEquals("https://x.dev", links[1].url)
    }

    @Test
    fun `italic and strikethrough`() {
        val inlines = MarkdownParser.parseInline("this is *italic* and ~~gone~~")
        assertTrue(inlines.any { it is MdInline.Italic })
        assertTrue(inlines.any { it is MdInline.Strike })
    }

    @Test
    fun `display math delimiters become math blocks`() {
        val dollars = MarkdownParser.parse("$$\nE = mc^2\n$$").single() as MdBlock.Math
        assertEquals("E = mc^2", dollars.tex)

        val brackets = MarkdownParser.parse("\\[\\frac{a}{b}\\]").single() as MdBlock.Math
        assertEquals("\\frac{a}{b}", brackets.tex)

        val environment = MarkdownParser.parse(
            "\\begin{aligned}\na &= b + c \\\\\nd &= e\n\\end{aligned}",
        ).single() as MdBlock.Math
        assertTrue(environment.tex.startsWith("\\begin{aligned}"))
    }

    @Test
    fun `fenced math is not treated as code`() {
        val block = MarkdownParser.parse("```math\n\\sqrt{x}\n```").single()
        val math = block as MdBlock.Math
        assertEquals("\\sqrt{x}", math.tex)
    }

    @Test
    fun `inline math delimiters and currency`() {
        val inlines = MarkdownParser.parseInline("Use \\(x^2\\) or \$y\$ and \$\\alpha\$")
        val math = inlines.filterIsInstance<MdInline.Math>()
        assertEquals(3, math.size)
        assertEquals("x^2", math[0].tex)
        assertEquals("y", math[1].tex)
        assertEquals("\\alpha", math[2].tex)

        val prices = MarkdownParser.parseInline("It costs \$5 or \$10 today")
        assertTrue(prices.none { it is MdInline.Math })
    }

    @Test
    fun `table pipes inside inline math do not split cells`() {
        val table = MarkdownParser.parse(
            "| value | note |\n|---|---|\n| \$|x|\$ | abs |",
        ).single() as MdBlock.Table
        assertEquals(2, table.rows.single().size)
        assertTrue(table.rows.single()[0].any { it is MdInline.Math })

        val prices = MarkdownParser.parse(
            "| price | note |\n|---|---|\n| \$5 | each |",
        ).single() as MdBlock.Table
        assertEquals(2, prices.rows.single().size)
        assertTrue(prices.rows.single().none { cell -> cell.any { it is MdInline.Math } })
    }
}
