package dev.jonas.openchat.network

import dev.jonas.openchat.core.model.ApiFamily
import dev.jonas.openchat.core.model.ChatRequest
import dev.jonas.openchat.core.model.ProviderEvent
import dev.jonas.openchat.core.model.RequestBlock
import dev.jonas.openchat.core.model.RequestMessage
import dev.jonas.openchat.core.model.Role
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class InferenceClientTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var client: InferenceClient
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        http = OkHttpClient.Builder()
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
        client = InferenceClient(http, "OpenChatTest/1.0")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun request() = ChatRequest(
        model = "deepseek-v4.1-flash",
        messages = listOf(
            RequestMessage(Role.USER, listOf(RequestBlock.Text("hi"))),
        ),
    )

    @Test
    fun `streams text and sends the required go headers`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"choices":[{"delta":{"content":"Hel"}}]}

                    data: {"choices":[{"delta":{"content":"lo"}}]}

                    data: {"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":2,"opencode":{"billing_source":"go","allowance":0.0001}}}

                    data: [DONE]

                    """.trimIndent(),
                ),
        )
        val events = mutableListOf<ProviderEvent>()
        val result = client.stream(
            endpointUrl = server.url("/chat/completions").toString(),
            family = ApiFamily.OPENAI_CHAT,
            apiKey = "oc_sk_test",
            model = "deepseek-v4.1-flash",
            sessionId = "ses_test123",
            request = request(),
        ) { events += it }

        assertTrue(result.isSuccess)
        assertEquals(
            "Hello",
            events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text },
        )
        assertEquals(0.0001, events.filterIsInstance<ProviderEvent.UsageEvent>().single().usage.allowanceUsd, 0.000001)
        assertTrue(events.last() is ProviderEvent.Finished)

        val recorded = server.takeRequest()
        assertEquals("Bearer oc_sk_test", recorded.getHeader("Authorization"))
        assertEquals("ses_test123", recorded.getHeader("x-opencode-session"))
        assertEquals("OpenChatTest/1.0", recorded.getHeader("User-Agent"))
        assertTrue(recorded.getHeader("Accept")!!.contains("text/event-stream"))
        val body = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
        assertTrue(
            body["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.boolean,
        )
    }

    @Test
    fun `retries a 500 before content and then succeeds`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"temporary"}}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"),
        )
        val events = mutableListOf<ProviderEvent>()
        val result = client.stream(
            endpointUrl = server.url("/chat").toString(),
            family = ApiFamily.OPENAI_CHAT,
            apiKey = "k",
            model = "m",
            sessionId = "ses_x",
            request = request(),
        ) { events += it }

        assertTrue(result.isSuccess)
        assertEquals(2, server.requestCount)
        assertEquals(
            "ok",
            events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text },
        )
    }

    @Test
    fun `authentication errors are not retried`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"message":"bad key"}}"""),
        )
        val result = client.stream(
            endpointUrl = server.url("/chat").toString(),
            family = ApiFamily.OPENAI_CHAT,
            apiKey = "bad",
            model = "m",
            sessionId = "ses_x",
            request = request(),
        ) {}
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Authentication failed"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `anthropic requests include the native auth headers`() {
        val request = client.buildStreamRequest(
            url = "https://example.com/v1/messages",
            family = ApiFamily.ANTHROPIC,
            apiKey = "secret",
            sessionId = "ses_1",
            body = "{}".toRequestBody(),
        )
        assertEquals("secret", request.header("x-api-key"))
        assertEquals("2023-06-01", request.header("anthropic-version"))
        assertEquals("Bearer secret", request.header("Authorization"))
        assertEquals("ses_1", request.header("x-opencode-session"))
    }
}
