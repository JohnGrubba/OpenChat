package dev.jonas.openchat.core.tool

import dev.jonas.openchat.core.model.ContentBlock
import dev.jonas.openchat.core.model.SearchBackend
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

class LocalToolExecutor(
    private val searchClient: WebSearchClient = WebSearchClient(),
    private val fetchClient: WebFetchClient = WebFetchClient(),
) {
    suspend fun execute(
        call: ContentBlock.ToolCall,
        backend: SearchBackend,
        apiKey: String,
    ): ContentBlock.ToolResult = try {
        when (call.name) {
            LocalTools.WEB_SEARCH -> runSearch(call, call.arguments, backend, apiKey)
            LocalTools.WEB_FETCH -> runFetch(call, call.arguments)
            else -> errorResult(call, "Unknown tool: ${call.name}")
        }
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        errorResult(call, t.message ?: t.javaClass.simpleName)
    }

    private suspend fun runSearch(
        call: ContentBlock.ToolCall,
        arguments: String,
        backend: SearchBackend,
        apiKey: String,
    ): ContentBlock.ToolResult {
        val args = LocalTools.parseArguments(arguments)
        val query = args.string("query")?.trim().orEmpty()
        if (query.isBlank()) return error(call, "Missing required argument: query")
        val maxResults = (args.int("max_results") ?: 5).coerceIn(1, 8)
        val results = searchClient.search(query, maxResults, backend, apiKey)
        return ok(call, results.formatForModel())
    }

    private suspend fun runFetch(
        call: ContentBlock.ToolCall,
        arguments: String,
    ): ContentBlock.ToolResult {
        val args = LocalTools.parseArguments(arguments)
        val url = args.string("url")?.trim().orEmpty()
        if (url.isBlank()) return error(call, "Missing required argument: url")
        val maxChars = (args.int("max_chars") ?: 12_000).coerceIn(1_000, 20_000)
        val page = fetchClient.fetch(url, maxChars)
        val content = buildString {
            if (page.title.isNotBlank()) append("Title: ").append(page.title).append('\n')
            append("URL: ").append(page.url).append('\n')
            append("Content:\n").append(page.content)
        }.take(24_000)
        return ok(call, content)
    }

    private fun ok(call: ContentBlock.ToolCall, content: String): ContentBlock.ToolResult =
        ContentBlock.ToolResult(
            toolCallId = call.id,
            name = call.name,
            content = content,
            isError = false,
        )

    private fun error(call: ContentBlock.ToolCall, message: String): ContentBlock.ToolResult =
        ContentBlock.ToolResult(
            toolCallId = call.id,
            name = call.name,
            content = message,
            isError = true,
        )

    private fun errorResult(call: ContentBlock.ToolCall, message: String): ContentBlock.ToolResult =
        ContentBlock.ToolResult(
            toolCallId = call.id,
            name = call.name,
            content = message,
            isError = true,
        )

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull
}
