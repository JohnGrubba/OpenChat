package dev.jonas.openchat.core.tool

import dev.jonas.openchat.core.model.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object LocalTools {
    const val WEB_SEARCH = "web_search"
    const val WEB_FETCH = "web_fetch"

    val webSearch: ToolSpec = ToolSpec(
        name = WEB_SEARCH,
        description = "Search the public web. Use this for current events, facts you are unsure about, " +
            "or anything that needs up-to-date sources. Returns titles, URLs and snippets.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "The search query")
                })
                put("max_results", buildJsonObject {
                    put("type", "integer")
                    put("description", "How many results to return (1-8, default 5)")
                })
            })
            put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("query")) })
            put("additionalProperties", false)
        },
    )

    val webFetch: ToolSpec = ToolSpec(
        name = WEB_FETCH,
        description = "Fetch a public web page and return its readable text. Use it after web_search to " +
            "read a promising result, or when the user provides a URL.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute http(s) URL")
                })
                put("max_chars", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum characters to return (1000-20000, default 12000)")
                })
            })
            put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("url")) })
            put("additionalProperties", false)
        },
    )

    fun all(): List<ToolSpec> = listOf(webSearch, webFetch)

    fun parseArguments(arguments: String): JsonObject =
        runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(arguments) as JsonObject
        }.getOrElse { buildJsonObject { } }
}
