package dev.jonas.openchat.network

import dev.jonas.openchat.core.model.ApiFamily
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ModelServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var service: ModelService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        service = ModelService(
            client = OkHttpClient.Builder()
                .readTimeout(5, TimeUnit.SECONDS)
                .build(),
            userAgent = "OpenChatTest/1.0",
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `models are fetched with auth and session headers`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data":[{"id":"m1","name":"Model One","limit":{"context":1000}}]}"""),
        )
        val result = service.fetchModels(
            modelsUrl = server.url("/v1/models").toString(),
            apiKey = "secret",
            sessionId = "ses_1",
            family = ApiFamily.OPENAI_CHAT,
        )
        val models = result.getOrThrow()
        assertEquals(1, models.size)
        assertEquals("Model One", models[0].name)
        assertEquals(1000, models[0].contextWindow)

        val recorded = server.takeRequest()
        assertEquals("Bearer secret", recorded.getHeader("Authorization"))
        assertEquals("ses_1", recorded.getHeader("x-opencode-session"))
        assertEquals("OpenChatTest/1.0", recorded.getHeader("User-Agent"))
    }

    @Test
    fun `http errors are surfaced`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"message":"nope"}}"""),
        )
        val result = service.fetchModels(
            modelsUrl = server.url("/v1/models").toString(),
            apiKey = "bad",
            sessionId = "ses_1",
            family = ApiFamily.OPENAI_CHAT,
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("authentication"))
    }
}
