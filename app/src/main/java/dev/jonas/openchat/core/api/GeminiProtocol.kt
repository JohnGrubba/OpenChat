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

object GeminiProtocol : ProtocolAdapter {
    override val family: ApiFamily = ApiFamily.GEMINI

    private data class PendingContent(
        val role: String,
        val parts: MutableList<JsonElement> = mutableListOf(),
    )

    override fun buildRequest(request: ChatRequest): String {
        val contents = mutableListOf<PendingContent>()
        fun addPart(role: String, part: JsonElement) {
            if (contents.lastOrNull()?.role == role) {
                contents.last().parts += part
            } else {
                contents += PendingContent(role, mutableListOf(part))
            }
        }

        request.messages.forEach { message ->
            when (message.role) {
                Role.SYSTEM -> Unit
                Role.USER -> message.blocks.forEach { block ->
                    when (block) {
                        is RequestBlock.Text -> addPart("user", buildJsonObject {
                            put("text", block.text)
                        })
                        is RequestBlock.Image -> addPart("user", buildJsonObject {
                            put("inlineData", buildJsonObject {
                                put("mimeType", block.mimeType)
                                put("data", block.base64)
                            })
                        })
                        is RequestBlock.Document -> addPart("user", buildJsonObject {
                            put("text", P.documentText(block))
                        })
                        else -> Unit
                    }
                }
                Role.ASSISTANT -> message.blocks.forEach { block ->
                    when (block) {
                        is RequestBlock.Text -> if (block.text.isNotBlank()) addPart(
                            "model",
                            buildJsonObject { put("text", block.text) },
                        )
                        is RequestBlock.ToolCall -> {
                            val args = runCatching {
                                P.json.parseToJsonElement(block.arguments)
                            }.getOrNull() ?: buildJsonObject { }
                            addPart("model", buildJsonObject {
                                put("functionCall", buildJsonObject {
                                    put("name", block.name)
                                    put("args", args)
                                })
                            })
                        }
                        else -> Unit
                    }
                }
                Role.TOOL -> message.blocks.filterIsInstance<RequestBlock.ToolResult>()
                    .forEach { result ->
                        addPart("user", buildJsonObject {
                            put("functionResponse", buildJsonObject {
                                put("name", result.name)
                                put("response", buildJsonObject {
                                    put("result", result.content)
                                })
                            })
                        })
                    }
            }
        }

        val body = buildJsonObject {
            put("contents", buildJsonArray {
                contents.forEach { content ->
                    add(buildJsonObject {
                        put("role", content.role)
                        put("parts", buildJsonArray { content.parts.forEach { add(it) } })
                    })
                }
            })
            if (request.systemPrompt.isNotBlank()) {
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", request.systemPrompt) })
                    })
                })
            }
            val generation = buildJsonObject {
                request.temperature?.let { put("temperature", it) }
                request.maxTokens?.let { put("maxOutputTokens", it) }
            }
            if (generation.isNotEmpty()) put("generationConfig", generation)
            if (request.tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    add(buildJsonObject {
                        put("functionDeclarations", buildJsonArray {
                            request.tools.forEach { tool ->
                                add(buildJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parameters", sanitizeSchema(tool.parameters))
                                })
                            }
                        })
                    })
                })
            }
        }
        return body.toString()
    }

    private fun sanitizeSchema(element: JsonElement): JsonElement = when (element) {
        is kotlinx.serialization.json.JsonObject -> buildJsonObject {
            element.forEach { (key, value) ->
                if (key != "additionalProperties" && key != "\$schema") {
                    put(key, sanitizeSchema(value))
                }
            }
        }
        is kotlinx.serialization.json.JsonArray -> buildJsonArray {
            element.forEach { add(sanitizeSchema(it)) }
        }
        else -> element
    }

    override fun parse(event: SseEvent, state: ProtocolState): List<ProviderEvent> {
        if (event.isDoneMarker()) {
            state.done = true
            return listOf(ProviderEvent.Finished(state.stopReason))
        }
        val obj = P.parse(event.data) ?: return emptyList()
        P.errorMessage(obj)?.let { return listOf(ProviderEvent.RemoteError(it)) }

        val result = mutableListOf<ProviderEvent>()
        val candidate = (obj.arr("candidates")?.firstOrNull()) as? kotlinx.serialization.json.JsonObject
        candidate?.obj("content")?.arr("parts")?.forEach { partElement ->
            val part = partElement as? kotlinx.serialization.json.JsonObject ?: return@forEach
            part.str("text")?.takeIf { it.isNotEmpty() }?.let { text ->
                if (part.bool("thought") == true) {
                    result += ProviderEvent.ReasoningDelta(text)
                } else {
                    result += ProviderEvent.TextDelta(text)
                }
            }
            part.obj("functionCall")?.let { call ->
                val name = call.str("name") ?: return@let
                val index = state.toolIndex("gemini_${state.nextToolIndex}")
                val callId = call.str("id") ?: "gemini_$index"
                state.toolCalls.start(index, callId, name)
                result += ProviderEvent.ToolCallStart(index, callId, name)
                val args = call["args"]?.toString()?.takeIf { it.isNotBlank() && it != "{}" }
                if (args != null) {
                    state.toolCalls.arguments(index, args)
                    result += ProviderEvent.ToolCallArgumentsDelta(index, args)
                }
            }
        }

        obj.obj("usageMetadata")?.let { usage ->
            state.usage = state.usage.merge(P.geminiUsage(usage))
            result += ProviderEvent.UsageEvent(state.usage)
        }

        candidate?.str("finishReason")?.let { reason ->
            state.stopReason = reason
            if (!state.done) {
                state.done = true
                result += ProviderEvent.Finished(reason)
            }
        }
        return result
    }
}
