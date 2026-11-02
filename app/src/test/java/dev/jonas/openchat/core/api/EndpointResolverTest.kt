package dev.jonas.openchat.core.api

import dev.jonas.openchat.core.model.ApiFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointResolverTest {

    @Test
    fun `opencode go base resolves to chat completions and go models`() {
        val endpoint = EndpointResolver.resolve("https://opencode.ai/inference/go").getOrThrow()
        assertEquals(ApiFamily.OPENAI_CHAT, endpoint.family)
        assertEquals(
            "https://opencode.ai/inference/go/openai/v1/chat/completions",
            endpoint.chatUrl,
        )
        assertEquals("https://opencode.ai/inference/go/v1/models", endpoint.modelsUrl)
        assertTrue(EndpointResolver.isOpenCodeGo("https://opencode.ai/inference/go"))
    }

    @Test
    fun `full chat completions url is preserved`() {
        val url = "https://opencode.ai/inference/go/openai/v1/chat/completions"
        val endpoint = EndpointResolver.resolve(url).getOrThrow()
        assertEquals(url, endpoint.chatUrl)
        assertEquals(ApiFamily.OPENAI_CHAT, endpoint.family)
    }

    @Test
    fun `openai v1 base gets chat completions appended`() {
        val endpoint = EndpointResolver.resolve("https://api.openai.com/v1").getOrThrow()
        assertEquals("https://api.openai.com/v1/chat/completions", endpoint.chatUrl)
        assertEquals("https://api.openai.com/v1/models", endpoint.modelsUrl)
    }

    @Test
    fun `responses endpoints are detected`() {
        val endpoint = EndpointResolver.resolve(
            "https://opencode.ai/inference/go/openai/v1/responses",
        ).getOrThrow()
        assertEquals(ApiFamily.OPENAI_RESPONSES, endpoint.family)
        assertEquals(
            "https://opencode.ai/inference/go/openai/v1/responses",
            endpoint.chatUrl,
        )
    }

    @Test
    fun `anthropic endpoints are detected`() {
        val endpoint = EndpointResolver.resolve(
            "https://opencode.ai/inference/go/anthropic/v1",
        ).getOrThrow()
        assertEquals(ApiFamily.ANTHROPIC, endpoint.family)
        assertEquals(
            "https://opencode.ai/inference/go/anthropic/v1/messages",
            endpoint.chatUrl,
        )
        assertEquals("https://opencode.ai/inference/go/v1/models", endpoint.modelsUrl)
    }

    @Test
    fun `gemini base appends model and streaming method`() {
        val endpoint = EndpointResolver.resolve(
            "https://opencode.ai/inference/go/google/v1beta",
            "gemini-4-argon",
        ).getOrThrow()
        assertEquals(ApiFamily.GEMINI, endpoint.family)
        assertEquals(
            "https://opencode.ai/inference/go/google/v1beta/models/gemini-4-argon" +
                ":streamGenerateContent?alt=sse",
            endpoint.chatUrl,
        )
    }

    @Test
    fun `gemini generate content is rewritten to streaming`() {
        val endpoint = EndpointResolver.resolve(
            "https://opencode.ai/inference/go/google/v1beta/models/gemini-4-argon:generateContent",
        ).getOrThrow()
        assertEquals(
            "https://opencode.ai/inference/go/google/v1beta/models/gemini-4-argon" +
                ":streamGenerateContent?alt=sse",
            endpoint.chatUrl,
        )
    }

    @Test
    fun `localhost http is allowed but remote http is rejected`() {
        assertTrue(EndpointResolver.resolve("http://localhost:8080/v1").isSuccess)
        assertTrue(EndpointResolver.resolve("http://10.0.2.2:11434/v1").isSuccess)
        val remote = EndpointResolver.resolve("http://example.com/v1")
        assertTrue(remote.isFailure)
        assertTrue(remote.exceptionOrNull()!!.message!!.contains("HTTPS"))
    }

    @Test
    fun `scheme is added when missing`() {
        val endpoint = EndpointResolver.resolve("opencode.ai/inference/go").getOrThrow()
        assertEquals(
            "https://opencode.ai/inference/go/openai/v1/chat/completions",
            endpoint.chatUrl,
        )
    }

    @Test
    fun `pasting the go models url canonicalises to the chat endpoint`() {
        val endpoint = EndpointResolver.resolve(
            "https://opencode.ai/inference/go/v1/models",
        ).getOrThrow()
        assertEquals(
            "https://opencode.ai/inference/go/openai/v1/chat/completions",
            endpoint.chatUrl,
        )
        assertEquals("https://opencode.ai/inference/go/v1/models", endpoint.modelsUrl)
    }

    @Test
    fun `pasting a go anthropic endpoint canonicalises correctly`() {
        val endpoint = EndpointResolver.resolve(
            "https://opencode.ai/inference/go/anthropic/v1/messages",
        ).getOrThrow()
        assertEquals(
            "https://opencode.ai/inference/go/anthropic/v1/messages",
            endpoint.chatUrl,
        )
        assertEquals("https://opencode.ai/inference/go/v1/models", endpoint.modelsUrl)
    }

    @Test
    fun `invalid endpoint fails cleanly`() {
        assertTrue(EndpointResolver.resolve("   ").isFailure)
        assertTrue(EndpointResolver.resolve("https://").isFailure)
    }

    @Test
    fun `family detection does not recurse`() {
        assertEquals(ApiFamily.ANTHROPIC, EndpointResolver.detectFamily("/anthropic/v1/messages"))
        assertEquals(ApiFamily.OPENAI_RESPONSES, EndpointResolver.detectFamily("/v1/responses"))
    }
}
