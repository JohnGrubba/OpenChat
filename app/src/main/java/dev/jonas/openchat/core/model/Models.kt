package dev.jonas.openchat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class Role {
    @SerialName("system") SYSTEM,
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,
    @SerialName("tool") TOOL,
}

@Serializable
enum class MessageStatus {
    COMPLETE,
    STREAMING,
    ERROR,
    INTERRUPTED,
    CANCELLED,
}

@Serializable
sealed interface ContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ContentBlock

    /** [path] points at a downscaled copy inside the app's private storage. */
    @Serializable
    @SerialName("image")
    data class Image(
        val path: String,
        val mimeType: String,
        val name: String? = null,
    ) : ContentBlock

    @Serializable
    @SerialName("document")
    data class Document(
        val name: String,
        val mimeType: String,
        val text: String,
        val truncated: Boolean = false,
    ) : ContentBlock

    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: String,
    ) : ContentBlock

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        val toolCallId: String,
        val name: String,
        val content: String,
        val isError: Boolean = false,
    ) : ContentBlock
}

@Serializable
data class Usage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val costUsd: Double = 0.0,
    val allowanceUsd: Double = 0.0,
    val billingSource: String? = null,
) {
    val totalTokens: Long get() = inputTokens + outputTokens

    fun merge(other: Usage): Usage = Usage(
        inputTokens = if (other.inputTokens > 0) other.inputTokens else inputTokens,
        outputTokens = if (other.outputTokens > 0) other.outputTokens else outputTokens,
        reasoningTokens = if (other.reasoningTokens > 0) other.reasoningTokens else reasoningTokens,
        cacheReadTokens = if (other.cacheReadTokens > 0) other.cacheReadTokens else cacheReadTokens,
        cacheWriteTokens = if (other.cacheWriteTokens > 0) other.cacheWriteTokens else cacheWriteTokens,
        costUsd = if (other.costUsd > 0) other.costUsd else costUsd,
        allowanceUsd = if (other.allowanceUsd > 0) other.allowanceUsd else allowanceUsd,
        billingSource = other.billingSource ?: billingSource,
    )
}

@Serializable
data class ChatMessage(
    val id: String,
    val conversationId: String,
    val role: Role,
    val blocks: List<ContentBlock> = emptyList(),
    val status: MessageStatus = MessageStatus.COMPLETE,
    val createdAt: Long = System.currentTimeMillis(),
    val modelId: String? = null,
    val usage: Usage? = null,
    val reasoning: String = "",
    val error: String? = null,
) {
    val text: String
        get() = blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }

    val toolCalls: List<ContentBlock.ToolCall>
        get() = blocks.filterIsInstance<ContentBlock.ToolCall>()

    val images: List<ContentBlock.Image>
        get() = blocks.filterIsInstance<ContentBlock.Image>()

    val documents: List<ContentBlock.Document>
        get() = blocks.filterIsInstance<ContentBlock.Document>()
}

@Serializable
data class Conversation(
    val id: String,
    val title: String = "New chat",
    val modelId: String,
    val systemPrompt: String = "",
    val sessionId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
    val webToolsEnabled: Boolean = false,
)

enum class ApiFamily(val label: String) {
    OPENAI_CHAT("OpenAI Chat"),
    OPENAI_RESPONSES("OpenAI Responses"),
    ANTHROPIC("Anthropic Messages"),
    GEMINI("Gemini"),
}

data class ResolvedEndpoint(
    val family: ApiFamily,
    val chatUrl: String,
    val modelsUrl: String,
    val normalizedInput: String,
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class SearchBackend(val label: String) {
    DUCKDUCKGO("DuckDuckGo"),
    BRAVE("Brave Search"),
}

data class AppSettings(
    val endpointUrl: String = "https://opencode.ai/inference/go",
    val apiKey: String = "",
    val modelId: String = "deepseek-v4.1-flash",
    val modelName: String = "",
    val systemPrompt: String = "",
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val webToolsEnabled: Boolean = false,
    val searchBackend: SearchBackend = SearchBackend.DUCKDUCKGO,
    val searchApiKey: String = "",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val sendOnEnter: Boolean = true,
    val maxToolRounds: Int = 4,
) {
    val resolvedModelName: String get() = modelName.ifBlank { modelId }
}

data class ModelInfo(
    val id: String,
    val name: String = id,
    val family: ApiFamily? = null,
    val contextWindow: Int? = null,
    val supportsVision: Boolean = false,
    val supportsTools: Boolean = true,
    val builtIn: Boolean = false,
)

@Serializable
sealed interface RequestBlock {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : RequestBlock

    @Serializable
    @SerialName("image")
    data class Image(val base64: String, val mimeType: String) : RequestBlock

    @Serializable
    @SerialName("document")
    data class Document(val name: String, val mimeType: String, val text: String) : RequestBlock

    @Serializable
    @SerialName("tool_call")
    data class ToolCall(val id: String, val name: String, val arguments: String) : RequestBlock

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        val toolCallId: String,
        val name: String,
        val content: String,
        val isError: Boolean = false,
    ) : RequestBlock
}

data class RequestMessage(
    val role: Role,
    val blocks: List<RequestBlock>,
)

data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

data class ChatRequest(
    val model: String,
    val messages: List<RequestMessage>,
    val systemPrompt: String = "",
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val tools: List<ToolSpec> = emptyList(),
    val stream: Boolean = true,
)

sealed interface ProviderEvent {
    data class TextDelta(val text: String) : ProviderEvent
    data class ReasoningDelta(val text: String) : ProviderEvent
    data class ToolCallStart(val index: Int, val id: String?, val name: String?) : ProviderEvent
    data class ToolCallArgumentsDelta(val index: Int, val delta: String) : ProviderEvent
    data class UsageEvent(val usage: Usage) : ProviderEvent
    data class Finished(val stopReason: String?) : ProviderEvent
    data class RemoteError(val message: String, val retryable: Boolean = false) : ProviderEvent
}

class ToolCallAccumulator {
    private data class Partial(
        var id: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder(),
    )

    private val calls = linkedMapOf<Int, Partial>()

    fun start(index: Int, id: String?, name: String?) {
        val call = calls.getOrPut(index) { Partial() }
        if (id != null) call.id = id
        if (name != null) call.name = name
    }

    fun arguments(index: Int, delta: String) {
        calls.getOrPut(index) { Partial() }.arguments.append(delta)
    }

    fun isEmpty(): Boolean = calls.isEmpty()

    fun build(fallbackId: (Int) -> String = { "call_$it" }): List<ContentBlock.ToolCall> =
        calls.map { (index, call) ->
            ContentBlock.ToolCall(
                id = call.id ?: fallbackId(index),
                name = call.name.orEmpty(),
                arguments = call.arguments.toString().ifBlank { "{}" },
            )
        }.filter { it.name.isNotBlank() }
}
