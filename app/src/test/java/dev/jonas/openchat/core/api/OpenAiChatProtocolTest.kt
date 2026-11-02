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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiChatProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun request(messages: List<RequestMessage>, tools: List<ToolSpec> = emptyList()) =
        ChatRequest(
            model = "deepseek-v4.1-flash",
            messages = messages,
            systemPrompt = "You are helpful",
            temperature = 0.4,
            maxTokens = 2048,
            tools = tools,
        )

    @Test
    fun `request contains stream options messages images and tools`() {
        val request = request(
            messages = listOf(
                RequestMessage(Role.USER, listOf(RequestBlock.Text("hello"))),
                RequestMessage(
                    Role.USER,
                    listOf(
                        RequestBlock.Image("AAAA", "image/jpeg"),
                        RequestBlock.Document("notes.txt", "text/plain", "some notes"),
                    ),
                ),
                RequestMessage(
                    Role.ASSISTANT,
                    listOf(
                        RequestBlock.Text("checking"),
                        RequestBlock.ToolCall("call_1", "web_search", """{"query":"kotlin"}"""),
                    ),
                ),
                RequestMessage(
                    Role.TOOL,
                    listOf(RequestBlock.ToolResult("call_1", "web_search", "result text")),
                ),
            ),
            tools = listOf(LocalToolsForTest.webSearch),
        )
        val body = json.parseToJsonElement(OpenAiChatProtocol.buildRequest(request)).jsonObject
        assertEquals("deepseek-v4.1-flash", body["model"]!!.jsonPrimitive.content)
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
        assertTrue(
            body["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.boolean,
        )
        val messages = body["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("You are helpful", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("hello", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
        val imageParts = messages[2].jsonObject["content"]!!.jsonArray
        assertEquals("image_url", imageParts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(
            imageParts[0].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
                .startsWith("data:image/jpeg;base64,"),
        )
        assertEquals("text", imageParts[1].jsonObject["type"]!!.jsonPrimitive.content)
        val assistant = messages[3].jsonObject
        assertEquals("checking", assistant["content"]!!.jsonPrimitive.content)
        assertEquals(
            "web_search",
            assistant["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!
                .jsonObject["name"]!!.jsonPrimitive.content,
        )
        assertEquals("tool", messages[4].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("call_1", messages[4].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals(
            "web_search",
            body["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["name"]!!
                .jsonPrimitive.content,
        )
        assertEquals(0.4, body["temperature"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals(2048, body["max_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun `streaming text tool calls usage and done are parsed`() {
        val state = OpenAiChatProtocol.newState()
        val events = mutableListOf<ProviderEvent>()

        fun parse(data: String) {
            events += OpenAiChatProtocol.parse(SseEvent(null, null, data), state)
        }

        parse("""{"choices":[{"delta":{"content":"Hel"}}]}""")
        parse("""{"choices":[{"delta":{"content":"lo","reasoning_content":"think"}}]}""")
        parse(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"web_search","arguments":"{\"query\":\"k\"}"}}]}}]}""",
        )
        parse(
            """{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":4,"cost":0.002,"opencode":{"billing_source":"go","allowance":0.0015,"tokens":{"input":10,"output":4,"reasoning":2,"cache_read":100}}}}""",
        )
        parse("[DONE]")

        assertEquals("Hello", events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertEquals("think", events.filterIsInstance<ProviderEvent.ReasoningDelta>().single().text)
        assertEquals(1, events.filterIsInstance<ProviderEvent.ToolCallStart>().size)
        assertEquals(
            """{"query":"k"}""",
            events.filterIsInstance<ProviderEvent.ToolCallArgumentsDelta>().single().delta,
        )
        val usage = events.filterIsInstance<ProviderEvent.UsageEvent>().single().usage
        assertEquals(10, usage.inputTokens)
        assertEquals(4, usage.outputTokens)
        assertEquals(2, usage.reasoningTokens)
        assertEquals(100, usage.cacheReadTokens)
        assertEquals(0.0015, usage.allowanceUsd, 0.00001)
        assertEquals("go", usage.billingSource)
        assertTrue(state.done)
        assertTrue(events.last() is ProviderEvent.Finished)
        assertEquals("call_1", state.toolCalls.build().single().id)
    }
}

private object LocalToolsForTest {
    val webSearch = ToolSpec(
        name = "web_search",
        description = "search",
        parameters = Json.parseToJsonElement(
            """{"type":"object","properties":{"query":{"type":"string"}}}""",
        ).jsonObject,
    )
}
