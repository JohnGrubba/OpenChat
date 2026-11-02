package dev.jonas.openchat.core.text

import dev.jonas.openchat.core.model.RequestMessage
import dev.jonas.openchat.core.model.Role

/**
 * Drops the oldest user turns until the estimated prompt fits the model's context window. At least
 * the newest user turn is always kept, even if it alone is too large.
 */
object ConversationTrimmer {
    fun trim(
        messages: List<RequestMessage>,
        systemPrompt: String,
        contextWindow: Int,
    ): List<RequestMessage> {
        if (messages.size <= 1) return messages
        val budget = (contextWindow * 0.85).toInt().coerceAtLeast(4_000)
        var result = messages
        while (result.size > 1 && TokenEstimator.request(result, systemPrompt) > budget) {
            val firstUser = result.indexOfFirst { it.role == Role.USER }
            if (firstUser < 0) break
            val nextUser = result.drop(firstUser + 1).indexOfFirst { it.role == Role.USER }
            if (nextUser < 0) break
            result = result.drop(firstUser + 1 + nextUser)
        }
        return result
    }
}
