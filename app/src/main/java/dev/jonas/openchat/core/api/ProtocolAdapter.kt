package dev.jonas.openchat.core.api

import dev.jonas.openchat.core.model.ApiFamily
import dev.jonas.openchat.core.model.ChatRequest
import dev.jonas.openchat.core.model.ProviderEvent
import dev.jonas.openchat.core.model.ToolCallAccumulator
import dev.jonas.openchat.core.model.Usage
import dev.jonas.openchat.core.net.SseEvent

interface ProtocolAdapter {
    val family: ApiFamily

    fun buildRequest(request: ChatRequest): String

    fun newState(): ProtocolState = ProtocolState()

    /**
     * Parses one SSE event into zero or more normalized events. Implementations must be
     * idempotent with respect to state mutation and tolerate unknown event types.
     */
    fun parse(event: SseEvent, state: ProtocolState): List<ProviderEvent>
}

class ProtocolState {
    var usage: Usage = Usage()
    var stopReason: String? = null
    var done: Boolean = false
    var nextToolIndex: Int = 0

    /** Maps provider-specific ids (Responses item ids, Gemini calls) to a stable tool index. */
    val toolIndices: MutableMap<String, Int> = mutableMapOf()

    /** Tracks partial tool calls for providers that stream them. */
    val toolCalls: ToolCallAccumulator = ToolCallAccumulator()

    fun toolIndex(key: String): Int = toolIndices.getOrPut(key) { nextToolIndex++ }
}

object Protocols {
    private val adapters: Map<ApiFamily, ProtocolAdapter> = listOf(
        OpenAiChatProtocol,
        OpenAiResponsesProtocol,
        AnthropicProtocol,
        GeminiProtocol,
    ).associateBy { it.family }

    fun adapter(family: ApiFamily): ProtocolAdapter =
        adapters[family] ?: OpenAiChatProtocol
}
