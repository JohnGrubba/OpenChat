package dev.jonas.openchat.core.api

import dev.jonas.openchat.core.model.ModelInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ModelListParser {

    fun parse(json: Json, body: String): List<ModelInfo> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        val array = findArray(root) ?: return emptyList()
        val seen = mutableSetOf<String>()
        return array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.string("id") ?: item.string("model") ?: item.string("slug") ?: return@mapNotNull null
            if (id.isBlank() || !seen.add(id)) return@mapNotNull null
            val name = item.string("display_name")
                ?: item.string("displayName")
                ?: item.string("name")
                ?: id
            ModelInfo(
                id = id,
                name = name,
                contextWindow = item.objOrNull("limit")?.int("context")
                    ?: item.int("context_length")
                    ?: item.int("context_window")
                    ?: item.int("max_context_length")
                    ?: item.int("input_token_limit")
                    ?: item.int("max_input_tokens"),
                supportsVision = item.supportsVision(),
                supportsTools = item.supportsTools(),
            )
        }
    }

    private fun findArray(element: JsonElement): JsonArray? {
        if (element is JsonArray) return element
        val obj = element as? JsonObject ?: return null
        for (key in listOf("data", "models", "result", "results")) {
            val value = obj[key]
            if (value is JsonArray) return value
            if (value is JsonObject) findArray(value)?.let { return it }
        }
        return null
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNullSafe()?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull

    private fun JsonObject.supportsVision(): Boolean {
        if (boolean("supports_vision") == true) return true
        if (boolean("vision") == true) return true
        val modalities = this["architecture"]?.jsonObjectOrNull()?.get("input_modalities")
            ?: this["input_modalities"]
            ?: (this["modalities"] as? JsonObject)?.get("input")
            ?: this["modalities"]
        if (modalities is JsonArray) {
            if (modalities.any {
                    val text = runCatching { it.jsonPrimitive.content }.getOrNull().orEmpty()
                    text.equals("image", true) ||
                        text.startsWith("image/", ignoreCase = true) ||
                        text.equals("vision", true)
                }
            ) return true
        }
        val capabilities = this["capabilities"]
        if (capabilities is JsonArray) {
            if (capabilities.any {
                    runCatching { it.jsonPrimitive.content }.getOrNull()
                        ?.equals("vision", true) == true
                }
            ) return true
        }
        if (capabilities is JsonObject && capabilities.boolean("vision") == true) return true
        return false
    }

    private fun JsonObject.supportsTools(): Boolean {
        boolean("supports_tools")?.let { return it }
        val capabilities = this["capabilities"]
        if (capabilities is JsonObject) {
            val tools = capabilities["tools"] as? JsonObject
            tools?.boolean("supported")?.let { return it }
        }
        if (capabilities is JsonArray) {
            if (capabilities.any {
                    runCatching { it.jsonPrimitive.content }.getOrNull()
                        ?.equals("tools", true) == true ||
                        runCatching { it.jsonPrimitive.content }.getOrNull()
                            ?.equals("function_calling", true) == true
                }
            ) return true
        }
        return true
    }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonObject.objOrNull(key: String): JsonObject? = this[key] as? JsonObject

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content }.getOrNull()
}
