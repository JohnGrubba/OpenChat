package dev.jonas.openchat.core.net

import kotlin.math.min
import kotlin.random.Random

data class RetryDecision(
    val retry: Boolean,
    val delayMs: Long,
)

object RetryPolicy {
    const val DEFAULT_MAX_ATTEMPTS = 3

    fun isRetryableStatus(status: Int): Boolean =
        status == 408 || status == 425 || status == 429 || status in 500..599

    /**
     * Exponential backoff with full jitter. [attempt] is zero-based.
     * Jitter is injected for deterministic tests.
     */
    fun decision(
        attempt: Int,
        status: Int? = null,
        retryAfterSeconds: Long? = null,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        random: Random = Random.Default,
    ): RetryDecision {
        if (attempt >= maxAttempts - 1) return RetryDecision(false, 0)
        if (status != null && !isRetryableStatus(status)) return RetryDecision(false, 0)
        val base = retryAfterSeconds?.let { it.coerceIn(1, 60) * 1_000 }
            ?: min(12_000L, 600L * (1L shl attempt.coerceIn(0, 5)))
        val jittered = if (retryAfterSeconds != null) base else (base / 2) + random.nextLong(base / 2 + 1)
        return RetryDecision(true, jittered)
    }

    fun parseRetryAfter(value: String?, nowSeconds: Long = System.currentTimeMillis() / 1000): Long? {
        if (value.isNullOrBlank()) return null
        value.trim().toLongOrNull()?.let { return it.coerceAtLeast(0) }
        val date = runCatching {
            java.time.ZonedDateTime.parse(
                value,
                java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME,
            ).toEpochSecond()
        }.getOrNull()
        return date?.minus(nowSeconds)?.coerceAtLeast(0)
    }
}
