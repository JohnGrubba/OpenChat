package dev.jonas.openchat.network

import dev.jonas.openchat.core.api.P
import dev.jonas.openchat.core.api.Protocols
import dev.jonas.openchat.core.model.ApiFamily
import dev.jonas.openchat.core.model.ChatRequest
import dev.jonas.openchat.core.model.ProviderEvent
import dev.jonas.openchat.core.model.Usage
import dev.jonas.openchat.core.net.RetryPolicy
import dev.jonas.openchat.core.net.SseAccumulator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class StreamResult(
    val usage: Usage,
    val stopReason: String?,
    /**
     * True when the provider explicitly finished the stream (a terminal SSE event, a stop reason,
     * or a protocol-specific completion event). Transports sometimes close the connection cleanly
     * after a partial response, which is indistinguishable from success unless we track this.
     */
    val completed: Boolean = true,
)

class ApiException(
    val statusCode: Int,
    override val message: String,
) : Exception(message)

class InferenceClient(
    private val client: OkHttpClient = defaultClient(),
    private val userAgent: String = "OpenChat/1.0 (Android)",
) {
    suspend fun stream(
        endpointUrl: String,
        family: ApiFamily,
        model: String,
        apiKey: String,
        sessionId: String,
        request: ChatRequest,
        onEvent: (ProviderEvent) -> Unit,
    ): Result<StreamResult> = withContext(Dispatchers.IO) {
        val adapter = Protocols.adapter(family)
        val payload = adapter.buildRequest(request)
        val mediaType = "application/json; charset=utf-8".toMediaType()
        var attempt = 0
        var emittedContent = false

        while (true) {
            currentCoroutineContext().ensureActive()
            val httpRequest = buildStreamRequest(
                url = endpointUrl,
                family = family,
                apiKey = apiKey,
                sessionId = sessionId,
                body = payload.toRequestBody(mediaType),
            )
            val call = client.newCall(httpRequest)
            val cancellation = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val body = response.body?.string().orEmpty().take(8_000)
                        val message = errorMessage(response.code, body)
                        val retryAfter = RetryPolicy.parseRetryAfter(response.header("Retry-After"))
                        val decision = RetryPolicy.decision(attempt, response.code, retryAfter)
                        if (decision.retry && !emittedContent) {
                            attempt++
                            delay(decision.delayMs)
                            return@use
                        }
                        return@withContext Result.failure(ApiException(response.code, message))
                    }

                    val body = response.body
                        ?: return@withContext Result.failure(
                            ApiException(response.code, "The provider returned an empty response"),
                        )
                    val state = adapter.newState()
                    val accumulator = SseAccumulator()
                    val source = body.source()
                    var sawTerminal = false

                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val line = source.readUtf8Line() ?: break
                        val event = accumulator.feed(line) ?: continue
                        val parsed = adapter.parse(event, state)
                        if (parsed.any {
                                it is ProviderEvent.TextDelta ||
                                    it is ProviderEvent.ReasoningDelta ||
                                    it is ProviderEvent.ToolCallStart
                            }
                        ) {
                            // Reasoning is visible to the user too, so retrying after it starts
                            // would duplicate the partial thought. Only retry silent requests.
                            emittedContent = true
                        }
                        parsed.forEach(onEvent)
                        if (state.done) {
                            sawTerminal = true
                            break
                        }
                    }
                    accumulator.end()?.let { event ->
                        adapter.parse(event, state).forEach(onEvent)
                        if (state.done) sawTerminal = true
                    }
                    val completed = sawTerminal || state.stopReason != null
                    if (!sawTerminal) {
                        onEvent(ProviderEvent.Finished(state.stopReason))
                    }
                    return@withContext Result.success(
                        StreamResult(state.usage, state.stopReason, completed),
                    )
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                currentCoroutineContext().ensureActive()
                if (t is IOException && !emittedContent) {
                    val decision = RetryPolicy.decision(attempt)
                    if (decision.retry) {
                        attempt++
                        delay(decision.delayMs)
                        continue
                    }
                }
                return@withContext Result.failure(
                    if (t is ApiException) t
                    else ApiException(0, friendlyNetworkMessage(t)),
                )
            } finally {
                cancellation.dispose()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        Result.failure(ApiException(0, "Request failed"))
    }

    internal fun buildStreamRequest(
        url: String,
        family: ApiFamily,
        apiKey: String,
        sessionId: String,
        body: okhttp3.RequestBody,
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .post(body)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .header("User-Agent", userAgent)
            .header("x-opencode-session", sessionId)
        if (apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer $apiKey")
            if (family == ApiFamily.ANTHROPIC) {
                builder.header("x-api-key", apiKey)
                builder.header("anthropic-version", "2023-06-01")
            }
            if (family == ApiFamily.GEMINI) {
                builder.header("x-goog-api-key", apiKey)
            }
        }
        return builder.build()
    }

    private fun errorMessage(code: Int, body: String): String {
        val parsed = P.parse(body)
        val detail = parsed?.let { P.errorMessage(it) }
            ?: body.take(500).ifBlank { "No response body" }
        return when (code) {
            401, 403 -> "Authentication failed ($code). Check your API key and that this model is included in your plan. $detail"
            404 -> "Endpoint or model not found (404). Check the URL and model id. $detail"
            429 -> "Rate limit or usage limit reached (429). $detail"
            else -> "Request failed with HTTP $code. $detail"
        }
    }

    private fun friendlyNetworkMessage(t: Throwable): String = when (t) {
        is java.net.UnknownHostException -> "Could not reach the server. Check your connection."
        is java.net.SocketTimeoutException -> "The request timed out. Try again."
        is javax.net.ssl.SSLException -> "Secure connection failed. Check the endpoint and your network."
        else -> t.message ?: "Network error"
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
