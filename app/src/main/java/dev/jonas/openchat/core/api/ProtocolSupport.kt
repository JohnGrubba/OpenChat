package dev.jonas.openchat.core.api

import dev.jonas.openchat.core.model.RequestBlock
import dev.jonas.openchat.core.model.Usage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)
        ?.takeUnless { it is JsonNull }
        ?.content

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.longOrNull

internal fun JsonObject.int(key: String): Int? = long(key)?.toInt()

internal fun JsonObject.double(key: String): Double? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.doubleOrNull

internal fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.booleanOrNull

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asArray(): JsonArray? = this as? JsonArray

internal object P {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = false
    }

    fun parse(data: String): JsonObject? =
        runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull()

    fun dataUri(mimeType: String, base64: String): String =
        "data:${mimeType.ifBlank { "image/jpeg" }};base64,$base64"

    fun documentText(block: RequestBlock.Document): String = buildString {
        append("[Attached document: ").append(block.name)
        if (block.mimeType.isNotBlank()) append(" (").append(block.mimeType).append(')')
        append("]\n")
        append(block.text)
        append("\n[End of document]")
    }

    fun openAiUsage(usage: JsonObject): Usage {
        val promptDetails = usage.obj("prompt_tokens_details")
        val completionDetails = usage.obj("completion_tokens_details")
        val opencode = usage.obj("opencode")
        val tokens = opencode?.obj("tokens")
        return Usage(
            inputTokens = tokens?.long("input") ?: usage.long("prompt_tokens") ?: 0,
            outputTokens = tokens?.long("output") ?: usage.long("completion_tokens") ?: 0,
            reasoningTokens = tokens?.long("reasoning")
                ?: completionDetails?.long("reasoning_tokens") ?: 0,
            cacheReadTokens = tokens?.long("cache_read")
                ?: promptDetails?.long("cached_tokens") ?: 0,
            cacheWriteTokens = tokens?.long("cache_write_5m")
                ?: tokens?.long("cache_write_1h") ?: 0,
            costUsd = usage.double("cost") ?: opencode?.double("list_cost") ?: 0.0,
            allowanceUsd = opencode?.double("allowance") ?: 0.0,
            billingSource = opencode?.str("billing_source"),
        )
    }

    fun anthropicUsage(usage: JsonObject): Usage {
        val opencode = usage.obj("opencode")
        val tokens = opencode?.obj("tokens")
        return Usage(
            inputTokens = usage.long("input_tokens") ?: tokens?.long("input") ?: 0,
            outputTokens = usage.long("output_tokens") ?: tokens?.long("output") ?: 0,
            reasoningTokens = tokens?.long("reasoning") ?: 0,
            cacheReadTokens = usage.long("cache_read_input_tokens")
                ?: tokens?.long("cache_read") ?: 0,
            cacheWriteTokens = usage.long("cache_creation_input_tokens")
                ?: tokens?.long("cache_write_5m") ?: 0,
            costUsd = usage.double("cost") ?: opencode?.double("list_cost") ?: 0.0,
            allowanceUsd = opencode?.double("allowance") ?: 0.0,
            billingSource = opencode?.str("billing_source"),
        )
    }

    fun responsesUsage(usage: JsonObject): Usage {
        val inputDetails = usage.obj("input_tokens_details")
        val outputDetails = usage.obj("output_tokens_details")
        val opencode = usage.obj("opencode")
        val tokens = opencode?.obj("tokens")
        return Usage(
            inputTokens = usage.long("input_tokens") ?: tokens?.long("input") ?: 0,
            outputTokens = usage.long("output_tokens") ?: tokens?.long("output") ?: 0,
            reasoningTokens = outputDetails?.long("reasoning_tokens")
                ?: tokens?.long("reasoning") ?: 0,
            cacheReadTokens = inputDetails?.long("cached_tokens")
                ?: tokens?.long("cache_read") ?: 0,
            cacheWriteTokens = tokens?.long("cache_write_5m") ?: 0,
            costUsd = usage.double("cost") ?: opencode?.double("list_cost") ?: 0.0,
            allowanceUsd = opencode?.double("allowance") ?: 0.0,
            billingSource = opencode?.str("billing_source"),
        )
    }

    fun geminiUsage(usage: JsonObject): Usage {
        val opencode = usage.obj("opencode")
        val tokens = opencode?.obj("tokens")
        return Usage(
            inputTokens = tokens?.long("input") ?: usage.long("promptTokenCount") ?: 0,
            outputTokens = tokens?.long("output") ?: usage.long("candidatesTokenCount") ?: 0,
            reasoningTokens = tokens?.long("reasoning") ?: usage.long("thoughtsTokenCount") ?: 0,
            cacheReadTokens = tokens?.long("cache_read")
                ?: usage.long("cachedContentTokenCount") ?: 0,
            cacheWriteTokens = tokens?.long("cache_write_5m") ?: 0,
            costUsd = usage.double("cost") ?: opencode?.double("list_cost") ?: 0.0,
            allowanceUsd = opencode?.double("allowance") ?: 0.0,
            billingSource = opencode?.str("billing_source"),
        )
    }

    fun errorMessage(obj: JsonObject): String? {
        val error = obj["error"]
        if (error is JsonPrimitive && error !is JsonNull) return error.content
        if (error is JsonObject) {
            return error.str("message") ?: error.str("status") ?: error.toString()
        }
        obj.str("message")?.let { return it }
        if (obj.str("type") == "error") return obj.toString()
        return null
    }
}
