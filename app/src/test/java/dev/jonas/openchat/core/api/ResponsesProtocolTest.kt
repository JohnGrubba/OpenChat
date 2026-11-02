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

class ResponsesProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun request() = ChatRequest(
        model = "gpt-6-luna",
        messages = listOf(
            RequestMessage(
                Role.USER,
                listOf(
                    RequestBlock.Text("what is this?"),
                    RequestBlock.Image("AAAA", "image/png"),
                ),
            ),
            RequestMessage(
                Role.ASSISTANT,
                listOf(
                    RequestBlock.Text("looking"),
                    RequestBlock.ToolCall("call_1", "web_fetch", """{"url":"https://example.com"}"""),
                ),
            ),
            RequestMessage(
                Role.TOOL,
                listOf(RequestBlock.ToolResult("call_1", "web_fetch", "page body")),
            ),
        ),
        systemPrompt = "Be useful",
        maxTokens = 512,
        tools = listOf(
            ToolSpec(
                "web_fetch",
                "fetch",
                Json.parseToJsonElement("""{"type":"object"}""").jsonObject,
            ),
        ),
    )

    @Test
    fun `input items are generated for text images tool calls and outputs`() {
        val body = json.parseToJsonElement(
            OpenAiResponsesProtocol.buildRequest(request()),
        ).jsonObject
        assertEquals("Be useful", body["instructions"]!!.jsonPrimitive.content)
        assertEquals(512, body["max_output_tokens"]!!.jsonPrimitive.content.toInt())
        val input = body["input"]!!.jsonArray
        assertEquals("user", input[0].jsonObject["role"]!!.jsonPrimitive.content)
        val userContent = input[0].jsonObject["content"]!!.jsonArray
        assertEquals("input_text", userContent[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("input_image", userContent[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "assistant",
            input[1].jsonObject["role"]!!.jsonPrimitive.content,
        )
        assertEquals("function_call", input[2].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", input[2].jsonObject["call_id"]!!.jsonPrimitive.content)
        assertEquals("function_call_output", input[3].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("web_fetch", body["tools"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `streamed text function call and completed usage are parsed`() {
        val state = OpenAiResponsesProtocol.newState()
        val events = mutableListOf<ProviderEvent>()
        fun parse(type: String, data: String) {
            events += OpenAiResponsesProtocol.parse(SseEvent(null, type, data), state)
        }

        parse(
            "response.output_text.delta",
            """{"type":"response.output_text.delta","delta":"Hello"}""",
        )
        parse(
            "response.output_item.added",
            """{"type":"response.output_item.added","item":{"type":"function_call","id":"item_1","call_id":"call_1","name":"web_fetch"}}""",
        )
        parse(
            "response.function_call_arguments.delta",
            """{"type":"response.function_call_arguments.delta","item_id":"item_1","delta":"{\"url\":\"https://x.dev\"}"}""",
        )
        parse(
            "response.completed",
            """{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":20,"output_tokens":9,"input_tokens_details":{"cached_tokens":8},"output_tokens_details":{"reasoning_tokens":3},"opencode":{"billing_source":"go","allowance":0.002}}}}""",
        )

        assertEquals("Hello", events.filterIsInstance<ProviderEvent.TextDelta>().single().text)
        assertEquals(1, events.filterIsInstance<ProviderEvent.ToolCallStart>().size)
        assertTrue(
            events.filterIsInstance<ProviderEvent.ToolCallArgumentsDelta>().single()
                .delta.contains("https://x.dev"),
        )
        val usage = events.filterIsInstance<ProviderEvent.UsageEvent>().single().usage
        assertEquals(20, usage.inputTokens)
        assertEquals(9, usage.outputTokens)
        assertEquals(8, usage.cacheReadTokens)
        assertEquals(3, usage.reasoningTokens)
        assertEquals(0.002, usage.allowanceUsd, 0.00001)
        assertTrue(state.done)
        assertTrue(events.last() is ProviderEvent.Finished)
    }
}
