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
}
