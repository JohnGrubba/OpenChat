package dev.jonas.openchat.core.api

import dev.jonas.openchat.core.model.ApiFamily
import dev.jonas.openchat.core.model.ChatRequest
import dev.jonas.openchat.core.model.ProviderEvent
import dev.jonas.openchat.core.model.RequestBlock
import dev.jonas.openchat.core.model.Role
import dev.jonas.openchat.core.net.SseEvent
import dev.jonas.openchat.core.net.isDoneMarker
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object AnthropicProtocol : ProtocolAdapter {
    override val family: ApiFamily = ApiFamily.ANTHROPIC

    private data class PendingMessage(
        val role: String,
        val parts: MutableList<JsonElement> = mutableListOf(),
    )

    override fun buildRequest(request: ChatRequest): String {
        val messages = mutableListOf<PendingMessage>()
        fun addPart(role: String, part: JsonElement) {
            if (messages.lastOrNull()?.role == role) {
                messages.last().parts += part
            } else {
                messages += PendingMessage(role, mutableListOf(part))
            }
        }

        request.messages.forEach { message ->
            when (message.role) {
                Role.SYSTEM -> Unit
                Role.ASSISTANT -> {
                    message.blocks.forEach { block ->
                        when (block) {
                            is RequestBlock.Text -> if (block.text.isNotBlank()) addPart(
                                "assistant",
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", block.text)
                                },
                            )
                            is RequestBlock.ToolCall -> {
                                val input = runCatching {
                                    P.json.parseToJsonElement(block.arguments)
                                }.getOrNull().asObject() ?: buildJsonObject { }
                                addPart("assistant", buildJsonObject {
                                    put("type", "tool_use")
                                    put("id", block.id)
                                    put("name", block.name)
                                    put("input", input)
                                })
                            }
                            else -> Unit
                        }
                    }
                }
                Role.TOOL -> message.blocks.filterIsInstance<RequestBlock.ToolResult>()
                    .forEach { result ->
                        addPart("user", buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", result.toolCallId)
                            put("content", result.content)
                            if (result.isError) put("is_error", true)
                        })
                    }
                Role.USER -> message.blocks.forEach { block ->
                    when (block) {
                        is RequestBlock.Text -> addPart("user", buildJsonObject {
                            put("type", "text")
                            put("text", block.text)
                        })
                        is RequestBlock.Image -> addPart("user", buildJsonObject {
                            put("type", "image")
                            put("source", buildJsonObject {
                                put("type", "base64")
                                put("media_type", block.mimeType)
                                put("data", block.base64)
                            })
                        })
                        is RequestBlock.Document -> addPart("user", buildJsonObject {
                            put("type", "text")
                            put("text", P.documentText(block))
                        })
                        else -> Unit
                    }
                }
            }
        }

        val body = buildJsonObject {
            put("model", request.model)
            put("max_tokens", request.maxTokens ?: 4096)
            put("stream", true)
            if (request.systemPrompt.isNotBlank()) put("system", request.systemPrompt)
            put("messages", buildJsonArray {
                messages.forEach { message ->
                    add(buildJsonObject {
                        put("role", message.role)
                        put("content", buildJsonArray { message.parts.forEach { add(it) } })
                    })
                }
            })
            if (request.tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    request.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", tool.parameters)
                        })
                    }
                })
            }
            request.temperature?.let { put("temperature", it) }
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
            "message_start" -> {
                obj.obj("message")?.obj("usage")?.let { usage ->
                    state.usage = state.usage.merge(P.anthropicUsage(usage))
                    result += ProviderEvent.UsageEvent(state.usage)
                }
            }
            "content_block_start" -> {
                val index = obj.int("index") ?: state.toolIndex("block_${state.nextToolIndex}")
                val block = obj.obj("content_block")
                when (block?.str("type")) {
                    "tool_use" -> {
                        val id = block.str("id")
                        val name = block.str("name")
                        state.toolCalls.start(index, id, name)
                        result += ProviderEvent.ToolCallStart(index, id, name)
                        val input = block["input"]
                        if (input != null && input !is kotlinx.serialization.json.JsonNull &&
                            input.toString() != "{}"
                        ) {
                            state.toolCalls.arguments(index, input.toString())
                            result += ProviderEvent.ToolCallArgumentsDelta(index, input.toString())
                        }
                    }
                    "text" -> block.str("text")?.takeIf { it.isNotEmpty() }?.let {
                        result += ProviderEvent.TextDelta(it)
                    }
                    "thinking" -> block.str("thinking")?.takeIf { it.isNotEmpty() }?.let {
                        result += ProviderEvent.ReasoningDelta(it)
                    }
                }
            }
            "content_block_delta" -> {
                val index = obj.int("index") ?: 0
                val delta = obj.obj("delta")
                when (delta?.str("type")) {
                    "text_delta" -> delta.str("text")?.takeIf { it.isNotEmpty() }?.let {
                        result += ProviderEvent.TextDelta(it)
                    }
                    "thinking_delta" -> delta.str("thinking")?.takeIf { it.isNotEmpty() }?.let {
                        result += ProviderEvent.ReasoningDelta(it)
                    }
                    "input_json_delta" -> delta.str("partial_json")
                        ?.takeIf { it.isNotEmpty() }?.let {
                            state.toolCalls.arguments(index, it)
                            result += ProviderEvent.ToolCallArgumentsDelta(index, it)
                        }
                }
            }
            "message_delta" -> {
                obj.obj("delta")?.str("stop_reason")?.let { state.stopReason = it }
                obj.obj("usage")?.let { usage ->
                    state.usage = state.usage.merge(P.anthropicUsage(usage))
                    result += ProviderEvent.UsageEvent(state.usage)
                }
            }
            "message_stop", "done" -> {
                state.done = true
                result += ProviderEvent.Finished(state.stopReason)
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
}
