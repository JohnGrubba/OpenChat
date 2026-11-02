package dev.jonas.openchat.core.text

import dev.jonas.openchat.core.model.RequestBlock
import dev.jonas.openchat.core.model.RequestMessage
import dev.jonas.openchat.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTrimmerTest {
    private fun user(text: String) =
        RequestMessage(Role.USER, listOf(RequestBlock.Text(text)))
    private fun assistant(text: String) =
        RequestMessage(Role.ASSISTANT, listOf(RequestBlock.Text(text)))

    @Test
    fun `short conversations are unchanged`() {
        val messages = listOf(user("hello"), assistant("hi there"), user("again"))
        assertEquals(messages, ConversationTrimmer.trim(messages, "", 200_000))
    }

    @Test
    fun `oldest user turn is dropped when over budget`() {
        val huge = "x".repeat(60_000)
        val messages = listOf(
            user(huge),
            assistant("answer"),
            user("new question"),
            assistant("new answer"),
        )
        val trimmed = ConversationTrimmer.trim(messages, "", 10_000)
        assertEquals(2, trimmed.size)
        assertEquals(Role.USER, trimmed.first().role)
        assertEquals("new question", (trimmed.first().blocks.single() as RequestBlock.Text).text)
    }

    @Test
    fun `the newest user turn is always kept`() {
        val huge = "x".repeat(200_000)
        val messages = listOf(user(huge), assistant("answer"))
        val trimmed = ConversationTrimmer.trim(messages, "", 10_000)
        assertEquals(messages, trimmed)
    }

    @Test
    fun `tool sequences are not split across the cut`() {
        val huge = "x".repeat(60_000)
        val messages = listOf(
            user(huge),
            assistant("old"),
            RequestMessage(
                Role.ASSISTANT,
                listOf(RequestBlock.ToolCall("call", "web_search", """{"query":"x"}""")),
            ),
            RequestMessage(
                Role.TOOL,
                listOf(RequestBlock.ToolResult("call", "web_search", "result")),
            ),
            user("fresh"),
            assistant("fresh answer"),
        )
        val trimmed = ConversationTrimmer.trim(messages, "", 10_000)
        assertEquals(2, trimmed.size)
        assertTrue(trimmed.first().role == Role.USER)
        assertEquals("fresh", (trimmed.first().blocks.single() as RequestBlock.Text).text)
    }
}
