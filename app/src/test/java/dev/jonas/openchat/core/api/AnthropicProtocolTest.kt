package dev.jonas.openchat.core.api

import dev.jonas.openchat.core.model.ChatRequest
import dev.jonas.openchat.core.model.ProviderEvent
import dev.jonas.openchat.core.model.RequestBlock
import dev.jonas.openchat.core.model.RequestMessage
import dev.jonas.openchat.core.model.Role
import dev.jonas.openchat.core.model.ToolSpec
import dev.jonas.openchat.core.net.SseEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun request() = ChatRequest(
        model = "claude-haiku-5-5",
        messages = listOf(
            RequestMessage(Role.USER, listOf(RequestBlock.Text("hello"))),
            RequestMessage(
                Role.ASSISTANT,
                listOf(RequestBlock.ToolCall("tool_1", "web_search", """{"query":"news"}""")),
            ),
            RequestMessage(
                Role.TOOL,
                listOf(
                    RequestBlock.ToolResult("tool_1", "web_search", "result"),
                    RequestBlock.Text("please summarize"),
                ),
            ),
        ),
        systemPrompt = "Be brief",
        temperature = 0.2,
        maxTokens = 1024,
        tools = listOf(
            ToolSpec(
                "web_search",
                "search",
                Json.parseToJsonElement("""{"type":"object"}""").jsonObject,
            ),
        ),
    )

    @Test
    fun `request maps system images tools and tool results`() {
        val body = json.parseToJsonElement(AnthropicProtocol.buildRequest(request())).jsonObject
        assertEquals("Be brief", body["system"]!!.jsonPrimitive.content)
        assertEquals(1024, body["max_tokens"]!!.jsonPrimitive.int)
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
        val messages = body["messages"]!!.jsonArray
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(
            "text",
            messages[0].jsonObject["content"]!!.jsonArray[0].jsonObject["type"]!!
                .jsonPrimitive.content,
        )
        assertEquals("assistant", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(
            "tool_use",
            messages[1].jsonObject["content"]!!.jsonArray[0].jsonObject["type"]!!
                .jsonPrimitive.content,
        )
        assertEquals("user", messages[2].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(
            "tool_result",
            messages[2].jsonObject["content"]!!.jsonArray[0].jsonObject["type"]!!
                .jsonPrimitive.content,
        )
        assertEquals(
            "web_search",
            body["tools"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `image uses anthropic base64 source`() {
        val body = json.parseToJsonElement(
            AnthropicProtocol.buildRequest(
                ChatRequest(
                    model = "m",
                    messages = listOf(
                        RequestMessage(
                            Role.USER,
                            listOf(RequestBlock.Image("QUJD", "image/png")),
                        ),
                    ),
                ),
            ),
        ).jsonObject
        val part = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("image", part["type"]!!.jsonPrimitive.content)
        assertEquals(
            "image/png",
            part["source"]!!.jsonObject["media_type"]!!.jsonPrimitive.content,
        )
        assertEquals("QUJD", part["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun `streamed usage text and tool use are merged`() {
        val state = AnthropicProtocol.newState()
        val events = mutableListOf<ProviderEvent>()
        fun parse(type: String, data: String) {
            events += AnthropicProtocol.parse(SseEvent(null, type, data), state)
        }

        parse(
            "message_start",
            """{"type":"message_start","message":{"usage":{"input_tokens":12,"cache_read_input_tokens":5,"output_tokens":1}}}""",
        )
        parse(
            "content_block_start",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        )
        parse(
            "content_block_delta",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}""",
        )
        parse(
            "content_block_start",
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"t1","name":"web_search","input":{}}}""",
        )
        parse(
            "content_block_delta",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"query\":\"x\"}"}}""",
        )
        parse(
            "message_delta",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":7}}""",
        )
        parse("message_stop", """{"type":"message_stop"}""")

        assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().any { it.text == "Hi" })
        assertEquals(1, events.filterIsInstance<ProviderEvent.ToolCallStart>().size)
        assertTrue(
            events.filterIsInstance<ProviderEvent.ToolCallArgumentsDelta>().any {
                it.delta.contains("query")
            },
        )
        val usage = events.filterIsInstance<ProviderEvent.UsageEvent>().last().usage
        assertEquals(12, usage.inputTokens)
        assertEquals(7, usage.outputTokens)
        assertEquals(5, usage.cacheReadTokens)
        assertTrue(state.done)
        assertEquals("end_turn", state.stopReason)
    }
}
