package dev.jonas.openchat.network

import dev.jonas.openchat.core.api.ModelListParser
import dev.jonas.openchat.core.model.ApiFamily
import dev.jonas.openchat.core.model.ModelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class ModelService(
    private val client: OkHttpClient = defaultClient(),
    private val userAgent: String = "OpenChat/1.0 (Android)",
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    suspend fun fetchModels(
        modelsUrl: String,
        apiKey: String,
        sessionId: String,
        family: ApiFamily,
    ): Result<List<ModelInfo>> = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(modelsUrl)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", userAgent)
            .header("x-opencode-session", sessionId)
        if (apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer $apiKey")
            if (family == ApiFamily.ANTHROPIC) {
                builder.header("x-api-key", apiKey)
                builder.header("anthropic-version", "2023-06-01")
            }
            if (family == ApiFamily.GEMINI) builder.header("x-goog-api-key", apiKey)
        }
        runCatching {
            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = body.take(400).ifBlank { "No response body" }
                    throw ApiException(
                        response.code,
                        when (response.code) {
                            401, 403 -> "Could not load models: authentication failed. Check your API key."
                            404 -> "This endpoint does not expose a model list. Enter the model id manually."
                            else -> "Could not load models (HTTP ${response.code}). $detail"
                        },
                    )
                }
                val models = ModelListParser.parse(json, body)
                if (models.isEmpty()) {
                    throw ApiException(response.code, "The endpoint returned an empty model list.")
                }
                models
            }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()
    }
}
