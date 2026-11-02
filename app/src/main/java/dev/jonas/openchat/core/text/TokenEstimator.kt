package dev.jonas.openchat.core.text

import dev.jonas.openchat.core.model.ChatMessage
import dev.jonas.openchat.core.model.ContentBlock
import dev.jonas.openchat.core.model.RequestBlock
import dev.jonas.openchat.core.model.RequestMessage
import kotlin.math.ceil

/**
 * Deliberately conservative token estimate used only for context warnings. Providers report the
 * real usage in their stream, which is what the UI displays after a response.
 */
object TokenEstimator {
    private const val CHARS_PER_TOKEN = 3.6
    private const val IMAGE_TOKENS = 850

    fun text(text: String): Int = if (text.isBlank()) 0 else ceil(text.length / CHARS_PER_TOKEN).toInt() + 2

    fun message(message: ChatMessage): Int {
        var total = 4
        message.blocks.forEach { block ->
            total += when (block) {
                is ContentBlock.Text -> text(block.text)
                is ContentBlock.Image -> IMAGE_TOKENS
                is ContentBlock.Document -> text(block.text) + 20
                is ContentBlock.ToolCall -> text(block.arguments) + 12
                is ContentBlock.ToolResult -> text(block.content) + 8
            }
        }
        if (message.reasoning.isNotBlank()) total += text(message.reasoning)
        return total
    }

    fun request(messages: List<RequestMessage>, systemPrompt: String = ""): Int {
        var total = text(systemPrompt) + 3
        messages.forEach { message ->
            total += 4
            message.blocks.forEach { block ->
                total += when (block) {
                    is RequestBlock.Text -> text(block.text)
                    is RequestBlock.Image -> IMAGE_TOKENS
                    is RequestBlock.Document -> text(block.text) + 20
                    is RequestBlock.ToolCall -> text(block.arguments) + 12
                    is RequestBlock.ToolResult -> text(block.content) + 8
                }
            }
        }
        return total
    }

    fun format(count: Int): String = when {
        count >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", count / 1_000.0)
        else -> count.toString()
    }
}
