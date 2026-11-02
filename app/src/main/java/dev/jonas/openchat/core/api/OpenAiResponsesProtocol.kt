package dev.jonas.openchat.core.api

import dev.jonas.openchat.core.model.ApiFamily
import dev.jonas.openchat.core.model.ChatRequest
import dev.jonas.openchat.core.model.ProviderEvent
import dev.jonas.openchat.core.model.RequestBlock
import dev.jonas.openchat.core.model.Role
import dev.jonas.openchat.core.net.SseEvent
import dev.jonas.openchat.core.net.isDoneMarker
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object OpenAiResponsesProtocol : ProtocolAdapter {
    override val family: ApiFamily = ApiFamily.OPENAI_RESPONSES

    override fun buildRequest(request: ChatRequest): String {
        val input = buildJsonArray {
            request.messages.forEach { message ->
                when (message.role) {
                    Role.SYSTEM -> add(buildJsonObject {
                        put("role", "system")
                        put("content", message.blocks.filterIsInstance<RequestBlock.Text>()
                            .joinToString("\n") { it.text })
                    })
                    Role.USER -> add(buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray {
                            message.blocks.forEach { block ->
                                when (block) {
                                    is RequestBlock.Text -> add(buildJsonObject {
                                        put("type", "input_text")
                                        put("text", block.text)
                                    })
                                    is RequestBlock.Image -> add(buildJsonObject {
                                        put("type", "input_image")
                                        put("image_url", P.dataUri(block.mimeType, block.base64))
                                    })
                                    is RequestBlock.Document -> add(buildJsonObject {
                                        put("type", "input_text")
                                        put("text", P.documentText(block))
                                    })
                                    else -> Unit
                                }
                            }
                        })
                    })
                    Role.ASSISTANT -> {
                        val text = message.blocks.filterIsInstance<RequestBlock.Text>()
                            .joinToString("\n\n") { it.text }
                        if (text.isNotBlank()) {
                            add(buildJsonObject {
                                put("role", "assistant")
                                put("content", text)
                            })
                        }
                        message.blocks.filterIsInstance<RequestBlock.ToolCall>().forEach { call ->
                            add(buildJsonObject {
                                put("type", "function_call")
                                put("call_id", call.id)
                                put("name", call.name)
                                put("arguments", call.arguments)
                            })
                        }
                    }
                    Role.TOOL -> message.blocks.filterIsInstance<RequestBlock.ToolResult>()
                        .forEach { result ->
                            add(buildJsonObject {
                                put("type", "function_call_output")
                                put("call_id", result.toolCallId)
                                put("output", result.content)
                            })
                        }
                }
            }
        }

        val body = buildJsonObject {
            put("model", request.model)
            put("input", input)
            put("stream", true)
            if (request.systemPrompt.isNotBlank()) put("instructions", request.systemPrompt)
            request.temperature?.let { put("temperature", it) }
            request.maxTokens?.let { put("max_output_tokens", it) }
            if (request.tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    request.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.parameters)
                        })
                    }
                })
            }
        }
        return body.toString()
    }

    override fun parse(event: SseEvent, state: ProtocolState): List<ProviderEvent> {
        if (event.isDoneMarker()) {
            state.done = true
            return listOf(ProviderEvent.Finished(state.stopReason))
        }
        val obj = P.parse(event.data) ?: return emptyList()
        val type = obj.str("type") ?: event.event
        val result = mutableListOf<ProviderEvent>()

        when (type) {
            "response.output_text.delta" -> obj.str("delta")?.takeIf { it.isNotEmpty() }?.let {
                result += ProviderEvent.TextDelta(it)
            }
            "response.reasoning_summary_text.delta",
            "response.reasoning_text.delta",
            -> obj.str("delta")?.takeIf { it.isNotEmpty() }?.let {
                result += ProviderEvent.ReasoningDelta(it)
            }
            "response.output_item.added" -> {
                val item = obj.obj("item")
                if (item?.str("type") == "function_call") {
                    val itemId = item.str("id") ?: item.str("call_id")
                    val callId = item.str("call_id") ?: itemId
                    val name = item.str("name")
                    val index = state.toolIndex(itemId ?: "call_${state.nextToolIndex}")
                    state.toolCalls.start(index, callId, name)
                    result += ProviderEvent.ToolCallStart(index, callId, name)
                    item.str("arguments")?.takeIf { it.isNotBlank() && it != "{}" }?.let {
                        state.toolCalls.arguments(index, it)
                        result += ProviderEvent.ToolCallArgumentsDelta(index, it)
                    }
                }
            }
            "response.function_call_arguments.delta" -> {
                val itemId = obj.str("item_id") ?: obj.str("id") ?: "call_${state.nextToolIndex}"
                val index = state.toolIndex(itemId)
                obj.str("delta")?.takeIf { it.isNotEmpty() }?.let {
                    state.toolCalls.arguments(index, it)
                    result += ProviderEvent.ToolCallArgumentsDelta(index, it)
                }
            }
            "response.completed" -> {
                val response = obj.obj("response")
                response?.obj("usage")?.let { usage ->
                    state.usage = state.usage.merge(P.responsesUsage(usage))
                    result += ProviderEvent.UsageEvent(state.usage)
                }
                state.stopReason = response?.str("status")
                state.done = true
                result += ProviderEvent.Finished(state.stopReason)
            }
            "response.incomplete" -> {
                val reason = obj.obj("response")?.obj("incomplete_details")?.str("reason")
                state.done = true
                result += ProviderEvent.RemoteError(
                    reason?.let { "Response incomplete: $it" } ?: "Response incomplete",
                )
            }
            "response.failed" -> {
                val message = obj.obj("response")?.obj("error")?.str("message")
                    ?: "The provider failed to complete the response"
                state.done = true
                result += ProviderEvent.RemoteError(message)
            }
            "error" -> {
                val message = obj.obj("error")?.str("message")
                    ?: obj.str("message")
                    ?: "The provider returned an error"
                result += ProviderEvent.RemoteError(message)
            }
        }
        if (result.isEmpty()) {
            P.errorMessage(obj)?.let { result += ProviderEvent.RemoteError(it) }
        }
        return result
    }

    @Suppress("unused")
    private fun JsonObject.asElement(): JsonElement = this
}
