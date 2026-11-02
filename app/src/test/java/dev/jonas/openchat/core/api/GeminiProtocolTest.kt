package dev.jonas.openchat.core.api

import dev.jonas.openchat.core.model.ChatRequest
import dev.jonas.openchat.core.model.ProviderEvent
import dev.jonas.openchat.core.model.RequestBlock
import dev.jonas.openchat.core.model.RequestMessage
import dev.jonas.openchat.core.model.Role
import dev.jonas.openchat.core.model.ToolSpec
import dev.jonas.openchat.core.net.SseEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun request() = ChatRequest(
        model = "gemini-4-argon",
        messages = listOf(
            RequestMessage(
                Role.USER,
                listOf(
                    RequestBlock.Text("describe"),
                    RequestBlock.Image("AAAA", "image/jpeg"),
                ),
            ),
        ),
        systemPrompt = "Be concise",
        maxTokens = 256,
        tools = listOf(
            ToolSpec(
                "web_search",
                "search",
                Json.parseToJsonElement(
                    """{"type":"object","properties":{"query":{"type":"string"}},"additionalProperties":false}""",
                ).jsonObject,
            ),
        ),
    )

    @Test
    fun `request maps contents system and tools`() {
        val body = json.parseToJsonElement(GeminiProtocol.buildRequest(request())).jsonObject
        val contents = body["contents"]!!.jsonArray
        assertEquals("user", contents[0].jsonObject["role"]!!.jsonPrimitive.content)
        val parts = contents[0].jsonObject["parts"]!!.jsonArray
        assertEquals("describe", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(
            "image/jpeg",
            parts[1].jsonObject["inlineData"]!!.jsonObject["mimeType"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "Be concise",
            body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!
                .jsonPrimitive.content,
        )
        val declaration = body["tools"]!!.jsonArray[0]
            .jsonObject["functionDeclarations"]!!.jsonArray[0].jsonObject
        assertEquals("web_search", declaration["name"]!!.jsonPrimitive.content)
        val parameters = declaration["parameters"]!!.jsonObject
        assertTrue("additionalProperties" !in parameters)
        assertTrue("properties" in parameters)
    }

    @Test
    fun `streamed text thoughts usage and function calls are parsed`() {
        val state = GeminiProtocol.newState()
        val events = mutableListOf<ProviderEvent>()
        fun parse(data: String) {
            events += GeminiProtocol.parse(SseEvent(null, null, data), state)
        }

        parse(
            """{"candidates":[{"content":{"parts":[{"text":"thinking","thought":true},{"text":"Hi"}]}}],"usageMetadata":{"promptTokenCount":11,"candidatesTokenCount":2,"thoughtsTokenCount":4,"cachedContentTokenCount":3}}""",
        )
        parse(
            """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"web_search","args":{"query":"x"}}}]},"finishReason":"STOP"}]}""",
        )

        assertEquals("Hi", events.filterIsInstance<ProviderEvent.TextDelta>().single().text)
        assertEquals("thinking", events.filterIsInstance<ProviderEvent.ReasoningDelta>().single().text)
        assertEquals(1, events.filterIsInstance<ProviderEvent.ToolCallStart>().size)
        assertTrue(
            events.filterIsInstance<ProviderEvent.ToolCallArgumentsDelta>().single()
                .delta.contains("query"),
        )
        val usage = events.filterIsInstance<ProviderEvent.UsageEvent>().single().usage
        assertEquals(11, usage.inputTokens)
        assertEquals(2, usage.outputTokens)
        assertEquals(4, usage.reasoningTokens)
        assertEquals(3, usage.cacheReadTokens)
        assertTrue(state.done)
        assertEquals("STOP", state.stopReason)
    }
}
