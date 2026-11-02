package dev.jonas.openchat.core.api

import dev.jonas.openchat.core.model.ApiFamily
import dev.jonas.openchat.core.model.ResolvedEndpoint
import java.net.URI

/**
 * Accepts whatever endpoint a user copies from the OpenCode console or a compatible provider and
 * derives a streaming URL plus a model-list URL.
 *
 * Examples that all resolve to OpenCode Go chat completions:
 *  - https://opencode.ai/inference/go
 *  - https://opencode.ai/inference/go/openai/v1
 *  - https://opencode.ai/inference/go/openai/v1/chat/completions
 */
object EndpointResolver {

    const val OPENCODE_GO_BASE = "https://opencode.ai/inference/go"
    const val OPENCODE_PAYG_BASE = "https://opencode.ai/inference"

    private val localHosts = setOf("localhost", "127.0.0.1", "::1", "10.0.2.2")

    fun resolve(raw: String, modelId: String = ""): Result<ResolvedEndpoint> = runCatching {
        resolveOrThrow(raw, modelId)
    }

    fun resolveOrThrow(raw: String, modelId: String = ""): ResolvedEndpoint {
        val normalized = normalize(raw)
        val uri = URI(normalized)
        val scheme = uri.scheme?.lowercase() ?: error("Endpoint needs a scheme")
        val host = uri.host?.lowercase() ?: error("Endpoint is missing a host")
        require(scheme == "https" || scheme == "http") { "Only http(s) endpoints are supported" }
        if (scheme == "http" && host !in localHosts) {
            error("Use HTTPS for remote endpoints. Plain HTTP is only allowed for localhost.")
        }

        val path = uri.path.orEmpty().trimEnd('/')
        val lowerPath = path.lowercase()
        val family = detectFamilyForPath(lowerPath)
        val authority = uri.authority ?: host
        val base = buildString {
            append(scheme).append("://").append(authority).append(path)
        }
        val openCodeRoot = when {
            host == "opencode.ai" || host.endsWith(".opencode.ai") -> when {
                lowerPath.startsWith("/inference/go") -> "$scheme://$authority/inference/go"
                lowerPath.startsWith("/inference") -> "$scheme://$authority/inference"
                else -> null
            }
            else -> null
        }

        val chatUrl = if (openCodeRoot != null && family != ApiFamily.GEMINI) {
            when (family) {
                ApiFamily.OPENAI_CHAT -> "$openCodeRoot/openai/v1/chat/completions"
                ApiFamily.OPENAI_RESPONSES -> "$openCodeRoot/openai/v1/responses"
                ApiFamily.ANTHROPIC -> "$openCodeRoot/anthropic/v1/messages"
                ApiFamily.GEMINI -> error("unreachable")
            }
        } else {
            when (family) {
                ApiFamily.OPENAI_CHAT -> openAiChatUrl(base, lowerPath)
                ApiFamily.OPENAI_RESPONSES -> openAiResponsesUrl(base, lowerPath)
                ApiFamily.ANTHROPIC -> anthropicUrl(base, lowerPath)
                ApiFamily.GEMINI -> geminiUrl(base, lowerPath, uri.query, modelId)
            }
        }

        return ResolvedEndpoint(
            family = family,
            chatUrl = chatUrl,
            modelsUrl = openCodeRoot?.let { "$it/v1/models" }
                ?: modelsUrl(scheme, authority, path, uri.query),
            normalizedInput = normalized,
        )
    }

    fun detectFamily(input: String): ApiFamily = detectFamilyForPath(input.lowercase())

    private fun detectFamilyForPath(lowerPath: String): ApiFamily = when {
        lowerPath.contains("/chat/completions") -> ApiFamily.OPENAI_CHAT
        lowerPath.contains("/responses") -> ApiFamily.OPENAI_RESPONSES
        lowerPath.contains("/anthropic/") || lowerPath.endsWith("/messages") -> ApiFamily.ANTHROPIC
        lowerPath.contains("/google/") ||
            lowerPath.contains(":generatecontent") ||
            lowerPath.contains(":streamgeneratecontent") -> ApiFamily.GEMINI
        else -> ApiFamily.OPENAI_CHAT
    }

    private fun openAiChatUrl(base: String, lowerPath: String): String = when {
        lowerPath.endsWith("/chat/completions") -> base
        lowerPath.endsWith("/v1") -> "$base/chat/completions"
        lowerPath.endsWith("/openai") -> "$base/v1/chat/completions"
        lowerPath.endsWith("/go") || lowerPath.endsWith("/inference") ->
            "$base/openai/v1/chat/completions"
        else -> "$base/v1/chat/completions"
    }

    private fun openAiResponsesUrl(base: String, lowerPath: String): String = when {
        lowerPath.endsWith("/responses") -> base
        lowerPath.endsWith("/v1") -> "$base/responses"
        lowerPath.endsWith("/openai") -> "$base/v1/responses"
        lowerPath.endsWith("/go") || lowerPath.endsWith("/inference") ->
            "$base/openai/v1/responses"
        else -> "$base/v1/responses"
    }

    private fun anthropicUrl(base: String, lowerPath: String): String = when {
        lowerPath.endsWith("/messages") -> base
        lowerPath.endsWith("/v1") -> "$base/messages"
        lowerPath.endsWith("/anthropic") -> "$base/v1/messages"
        lowerPath.endsWith("/go") || lowerPath.endsWith("/inference") ->
            "$base/anthropic/v1/messages"
        else -> "$base/v1/messages"
    }

    private fun geminiUrl(
        base: String,
        lowerPath: String,
        query: String?,
        modelId: String,
    ): String {
        val withoutMethod = when {
            lowerPath.endsWith(":streamgeneratecontent") ->
                base.dropLast(":streamGenerateContent".length)
            lowerPath.endsWith(":generatecontent") ->
                base.dropLast(":generateContent".length)
            else -> base
        }
        var url = when {
            lowerPath.endsWith(":streamgeneratecontent") ||
                lowerPath.endsWith(":generatecontent") -> "$withoutMethod:streamGenerateContent"
            lowerPath.contains("/models/") && !lowerPath.endsWith("/models") ->
                "$withoutMethod:streamGenerateContent"
            lowerPath.endsWith("/models") -> {
                require(modelId.isNotBlank()) { "A model id is required for this Gemini endpoint" }
                "$withoutMethod/$modelId:streamGenerateContent"
            }
            else -> {
                require(modelId.isNotBlank()) { "A model id is required for this Gemini endpoint" }
                "$withoutMethod/models/$modelId:streamGenerateContent"
            }
        }
        if (query?.contains("alt=sse") == true) {
            val separator = if (url.contains('?')) "&" else "?"
            url += separator + query
        } else if (!url.contains("alt=sse")) {
            url += if (url.contains('?')) "&alt=sse" else "?alt=sse"
        }
        return url
    }

    private fun modelsUrl(
        scheme: String,
        authority: String,
        path: String,
        query: String?,
    ): String {
        val lower = path.lowercase()
        if (authority.endsWith("opencode.ai") && lower.contains("/inference/go")) {
            return "$scheme://$authority/inference/go/v1/models"
        }
        if (authority.endsWith("opencode.ai") && lower.contains("/inference")) {
            return "$scheme://$authority/inference/v1/models"
        }
        if (lower.contains("/google/") || lower.contains("/v1beta")) {
            val marker = lower.indexOf("/v1beta")
            val root = if (marker >= 0) path.substring(0, marker + "/v1beta".length) else path
            return "$scheme://$authority${root.trimEnd('/')}/models"
        }
        var base = path.trimEnd('/')
        for (suffix in listOf(
            "/chat/completions",
            "/responses",
            "/messages",
            "/completions",
        )) {
            if (base.lowercase().endsWith(suffix)) {
                base = base.dropLast(suffix.length)
                break
            }
        }
        return when {
            base.lowercase().endsWith("/v1") -> "$scheme://$authority$base/models"
            base.lowercase().endsWith("/openai") -> "$scheme://$authority$base/v1/models"
            base.isEmpty() || base == "/" -> "$scheme://$authority/v1/models"
            else -> "$scheme://$authority$base/v1/models"
        }
    }

    private fun normalize(raw: String): String {
        var value = raw.trim()
        require(value.isNotBlank()) { "Enter an endpoint URL" }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://$value"
        }
        require(!value.any { it.isWhitespace() }) { "Endpoint must not contain spaces" }
        val uri = runCatching { URI(value) }
            .getOrElse { throw IllegalArgumentException("That endpoint is not a valid URL") }
        require(uri.host != null) { "That endpoint is not a valid URL" }
        return value.trimEnd('/')
    }

    fun isOpenCodeGo(endpoint: String): Boolean {
        val resolved = resolve(endpoint).getOrNull() ?: return false
        return resolved.modelsUrl.contains("/inference/go/")
    }
}
