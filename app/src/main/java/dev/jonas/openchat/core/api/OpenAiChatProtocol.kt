package dev.jonas.openchat.core.api

import dev.jonas.openchat.core.model.ApiFamily
import dev.jonas.openchat.core.model.ChatRequest
import dev.jonas.openchat.core.model.ProviderEvent
import dev.jonas.openchat.core.model.RequestBlock
import dev.jonas.openchat.core.model.RequestMessage
import dev.jonas.openchat.core.model.Role
import dev.jonas.openchat.core.net.SseEvent
import dev.jonas.openchat.core.net.isDoneMarker
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object OpenAiChatProtocol : ProtocolAdapter {
    override val family: ApiFamily = ApiFamily.OPENAI_CHAT

    override fun buildRequest(request: ChatRequest): String {
        val body = buildJsonObject {
            put("model", request.model)
            put("stream", true)
            put("stream_options", buildJsonObject { put("include_usage", true) })

            put("messages", buildJsonArray {
                if (request.systemPrompt.isNotBlank()) {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", request.systemPrompt)
                    })
                }
                request.messages.forEach { message ->
                    when (message.role) {
                        Role.SYSTEM -> add(buildJsonObject {
                            put("role", "system")
                            put("content", message.plainText())
                        })
                        Role.USER -> add(buildJsonObject {
                            put("role", "user")
                            put("content", message.contentPayload())
                        })
                        Role.ASSISTANT -> add(buildJsonObject {
                            put("role", "assistant")
                            val text = message.plainText()
                            if (text.isNotBlank()) put("content", text)
                            val calls = message.blocks.filterIsInstance<RequestBlock.ToolCall>()
                            if (calls.isNotEmpty()) {
                                put("tool_calls", buildJsonArray {
                                    calls.forEach { call ->
                                        add(buildJsonObject {
                                            put("id", call.id)
                                            put("type", "function")
                                            put("function", buildJsonObject {
                                                put("name", call.name)
                                                put("arguments", call.arguments)
                                            })
                                        })
                                    }
                                })
                            }
                        })
                        Role.TOOL -> message.blocks.filterIsInstance<RequestBlock.ToolResult>()
                            .forEach { result ->
                                add(buildJsonObject {
                                    put("role", "tool")
                                    put("tool_call_id", result.toolCallId)
                                    put("content", result.content)
                                })
                            }
                    }
                }
            })

            if (request.tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    request.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", tool.parameters)
                            })
                        })
                    }
                })
            }
            request.temperature?.let { put("temperature", it) }
            request.maxTokens?.let { put("max_tokens", it) }
        }
        return body.toString()
    }

    private fun RequestMessage.plainText(): String = blocks.joinToString("\n\n") { block ->
        when (block) {
            is RequestBlock.Text -> block.text
            is RequestBlock.Document -> P.documentText(block)
            is RequestBlock.ToolResult -> block.content
            else -> ""
        }
    }.trim()

    private fun RequestMessage.contentPayload(): JsonElement {
        val hasImage = blocks.any { it is RequestBlock.Image }
        if (!hasImage) return JsonPrimitive(plainText())
        return buildJsonArray {
            blocks.forEach { block ->
                when (block) {
                    is RequestBlock.Text -> add(buildJsonObject {
                        put("type", "text")
                        put("text", block.text)
                    })
                    is RequestBlock.Image -> add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject {
                            put("url", P.dataUri(block.mimeType, block.base64))
                            put("detail", "auto")
                        })
                    })
                    is RequestBlock.Document -> add(buildJsonObject {
                        put("type", "text")
                        put("text", P.documentText(block))
                    })
                    else -> Unit
                }
            }
        }
    }

    override fun parse(event: SseEvent, state: ProtocolState): List<ProviderEvent> {
        if (event.isDoneMarker()) {
            state.done = true
            return listOf(ProviderEvent.Finished(state.stopReason))
        }
        val obj = P.parse(event.data) ?: return emptyList()
        P.errorMessage(obj)?.let { return listOf(ProviderEvent.RemoteError(it)) }

        val result = mutableListOf<ProviderEvent>()
        val choice = obj.arr("choices")?.firstOrNull() as? JsonObject
        val delta = choice?.obj("delta") ?: choice?.obj("message")
        if (delta != null) {
            delta["content"].asText()?.takeIf { it.isNotEmpty() }?.let {
                result += ProviderEvent.TextDelta(it)
            }
            (delta.str("reasoning_content") ?: delta.str("reasoning"))
                ?.takeIf { it.isNotEmpty() }
                ?.let { result += ProviderEvent.ReasoningDelta(it) }

            delta.arr("tool_calls")?.forEach { element ->
                val call = element as? JsonObject ?: return@forEach
                val rawIndex = call.int("index")
                val id = call.str("id")
                val function = call.obj("function")
                val name = function?.str("name")
                val index = rawIndex ?: state.toolIndex(id ?: name ?: "tc_${state.nextToolIndex}")
                state.toolCalls.start(index, id, name)
                result += ProviderEvent.ToolCallStart(index, id, name)
                function?.str("arguments")?.takeIf { it.isNotEmpty() }?.let { args ->
                    state.toolCalls.arguments(index, args)
                    result += ProviderEvent.ToolCallArgumentsDelta(index, args)
                }
            }
        }

        choice?.str("finish_reason")?.let { state.stopReason = it }

        obj.obj("usage")?.let { usage ->
            state.usage = state.usage.merge(P.openAiUsage(usage))
            result += ProviderEvent.UsageEvent(state.usage)
        }

        return result
    }

    private fun JsonElement?.asText(): String? = when (this) {
        is JsonPrimitive -> if (this is JsonNull) null else content
        is JsonArray -> joinToString("") { element ->
            val part = element as? JsonObject ?: return@joinToString ""
            part.str("text") ?: part.str("content") ?: ""
        }.takeIf { it.isNotEmpty() }
        else -> null
    }
}
