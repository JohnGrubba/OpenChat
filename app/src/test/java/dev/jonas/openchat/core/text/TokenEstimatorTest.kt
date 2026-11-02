package dev.jonas.openchat.core.text

import dev.jonas.openchat.core.model.ContentBlock
import dev.jonas.openchat.core.model.RequestBlock
import dev.jonas.openchat.core.model.RequestMessage
import dev.jonas.openchat.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenEstimatorTest {
    @Test
    fun `text estimate is positive and scales`() {
        assertTrue(TokenEstimator.text("") == 0)
        assertTrue(TokenEstimator.text("hello world") > 0)
        assertTrue(TokenEstimator.text("x".repeat(400)) > TokenEstimator.text("x".repeat(40)))
    }

    @Test
    fun `images cost more than a short text block`() {
        val image = RequestMessage(Role.USER, listOf(RequestBlock.Image("abc", "image/jpeg")))
        val text = RequestMessage(Role.USER, listOf(RequestBlock.Text("hi")))
        assertTrue(TokenEstimator.request(listOf(image)) > TokenEstimator.request(listOf(text)))
    }

    @Test
    fun `format compacts large counts`() {
        assertEquals("999", TokenEstimator.format(999))
        assertEquals("1.5k", TokenEstimator.format(1_500))
        assertEquals("2.0M", TokenEstimator.format(2_000_000))
    }

    @Test
    fun `message estimate includes documents and tool results`() {
        val message = dev.jonas.openchat.core.model.ChatMessage(
            id = "m",
            conversationId = "c",
            role = Role.USER,
            blocks = listOf(
                ContentBlock.Text("hello"),
                ContentBlock.Document("a.txt", "text/plain", "document body"),
                ContentBlock.ToolResult("id", "web_search", "result"),
            ),
        )
        assertTrue(TokenEstimator.message(message) > TokenEstimator.text("hello"))
    }
}
