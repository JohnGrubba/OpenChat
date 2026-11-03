package dev.jonas.openchat.core.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DsmlToolCallsTest {

    @Test
    fun `canonical block is parsed and removed from the text`() {
        val input = """
            I'll search for that.

            <｜DSML｜tool_calls>
            <｜DSML｜invoke name="web_search">
            <｜DSML｜parameter name="query" string="true">Kotlin 2.0</｜DSML｜parameter>
            <｜DSML｜parameter name="max_results" string="false">3</｜DSML｜parameter>
            </｜DSML｜invoke>
            </｜DSML｜tool_calls>
        """.trimIndent()

        val parsed = DsmlToolCalls.extract(input, setOf("web_search"))

        assertEquals("I'll search for that.", parsed.text)
        val call = parsed.calls.single()
        assertEquals("web_search", call.name)
        val arguments = Json.parseToJsonElement(call.arguments).jsonObject
        assertEquals("Kotlin 2.0", arguments["query"]!!.jsonPrimitive.content)
        assertEquals(3, arguments["max_results"]!!.jsonPrimitive.int)
    }

    @Test
    fun `degraded ascii pipes spaces and the DMSL spelling are accepted`() {
        val input = "Searching now." +
            "< | | DMSL | | tool_calls>" +
            "< | | DMSL | | invoke name=\"web_fetch\">" +
            "< | | DMSL | | parameter name=\"url\" string=\"true\">https://example.com</ | | DMSL | | parameter>" +
            "</ | | DMSL | | invoke>" +
            "</ | | DMSL | | tool_calls>"

        val parsed = DsmlToolCalls.extract(input, setOf("web_fetch"))

        assertEquals("Searching now.", parsed.text)
        val call = parsed.calls.single()
        assertEquals("web_fetch", call.name)
        assertEquals(
            "https://example.com",
            Json.parseToJsonElement(call.arguments).jsonObject["url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `ASCII single pipe compact markup is accepted`() {
        val input = "one moment<|DSML|tool_calls><|DSML|invoke name=\"web_search\">" +
            "<|DSML|parameter name=\"query\" string=\"true\">weather</|DSML|parameter>" +
            "</|DSML|invoke></|DSML|tool_calls>"

        val parsed = DsmlToolCalls.extract(input, setOf("web_search"))

        assertEquals("one moment", parsed.text)
        assertEquals("web_search", parsed.calls.single().name)
    }

    @Test
    fun `multiple invokes and multiple blocks are all recovered`() {
        val input = "a" +
            "<｜DSML｜tool_calls>" +
            "<｜DSML｜invoke name=\"web_search\">" +
            "<｜DSML｜parameter name=\"query\" string=\"true\">one</｜DSML｜parameter>" +
            "</｜DSML｜invoke>" +
            "<｜DSML｜invoke name=\"web_fetch\">" +
            "<｜DSML｜parameter name=\"url\" string=\"true\">https://example.com</｜DSML｜parameter>" +
            "</｜DSML｜invoke>" +
            "</｜DSML｜tool_calls>" +
            "b" +
            "<｜DSML｜tool_calls>" +
            "<｜DSML｜invoke name=\"web_search\">" +
            "<｜DSML｜parameter name=\"query\" string=\"true\">two</｜DSML｜parameter>" +
            "</｜DSML｜invoke>" +
            "</｜DSML｜tool_calls>"

        val parsed = DsmlToolCalls.extract(input, null)

        assertEquals("ab", parsed.text)
        assertEquals(listOf("web_search", "web_fetch", "web_search"), parsed.calls.map { it.name })
    }

    @Test
    fun `unknown tool names are stripped but not returned`() {
        val input = "text<｜DSML｜tool_calls><｜DSML｜invoke name=\"delete_everything\">" +
            "<｜DSML｜parameter name=\"x\" string=\"true\">1</｜DSML｜parameter>" +
            "</｜DSML｜invoke></｜DSML｜tool_calls>"

        val parsed = DsmlToolCalls.extract(input, setOf("web_search"))

        assertEquals("text", parsed.text)
        assertTrue(parsed.calls.isEmpty())
    }

    @Test
    fun `blocks without an invoke are left verbatim`() {
        val input = "a <｜DSML｜tool_calls>not a call</｜DSML｜tool_calls> b"

        val parsed = DsmlToolCalls.extract(input, null)

        assertEquals(input, parsed.text)
        assertTrue(parsed.calls.isEmpty())
    }

    @Test
    fun `unterminated block is left alone`() {
        val input = "a <｜DSML｜tool_calls><｜DSML｜invoke name=\"web_search\">"

        val parsed = DsmlToolCalls.extract(input, setOf("web_search"))

        assertEquals(input, parsed.text)
        assertTrue(parsed.calls.isEmpty())
    }

    @Test
    fun `ordinary text with pipes is unchanged`() {
        val input = "Use a | b for alternatives, or <|> in some syntaxes."

        val parsed = DsmlToolCalls.extract(input, setOf("web_search"))

        assertEquals(input, parsed.text)
        assertTrue(parsed.calls.isEmpty())
    }

    @Test
    fun `wrapper tag aliases are accepted`() {
        val input = "< | | DMSL | | calls>" +
            "< | | DMSL | | invoke name=\"web_search\">" +
            "< | | DMSL | | parameter name=\"query\" string=\"true\">x</ | | DMSL | | parameter>" +
            "</ | | DMSL | | invoke>" +
            "</ | | DMSL | | calls>"

        val parsed = DsmlToolCalls.extract(input, setOf("web_search"))

        assertEquals("", parsed.text)
        assertEquals("web_search", parsed.calls.single().name)
    }

    @Test
    fun `text is not reformatted when no call block is present`() {
        val input = "line one\n\n\n\nline two  "

        val parsed = DsmlToolCalls.extract(input, setOf("web_search"))

        assertEquals(input, parsed.text)
    }

    @Test
    fun `whitespace inside the wrapper tag is tolerated`() {
        val input = "go<｜DSML｜tool_calls ><｜DSML｜invoke name=\"web_search\">" +
            "<｜DSML｜parameter name=\"query\" string=\"true\">x</｜DSML｜parameter>" +
            "</｜DSML｜invoke></｜DSML｜tool_calls>"

        val parsed = DsmlToolCalls.extract(input, setOf("web_search"))

        assertEquals("go", parsed.text)
        assertEquals("web_search", parsed.calls.single().name)
    }
}
