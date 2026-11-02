package dev.jonas.openchat.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RetryPolicyTest {
    @Test
    fun `server errors and rate limits are retryable`() {
        assertTrue(RetryPolicy.isRetryableStatus(429))
        assertTrue(RetryPolicy.isRetryableStatus(500))
        assertTrue(RetryPolicy.isRetryableStatus(503))
        assertTrue(RetryPolicy.isRetryableStatus(408))
        assertFalse(RetryPolicy.isRetryableStatus(400))
        assertFalse(RetryPolicy.isRetryableStatus(401))
    }

    @Test
    fun `exponential backoff grows and stops at max attempts`() {
        val first = RetryPolicy.decision(0, random = Random(1))
        val second = RetryPolicy.decision(1, random = Random(1))
        assertTrue(first.retry)
        assertTrue(second.retry)
        assertTrue(second.delayMs > first.delayMs)
        assertFalse(RetryPolicy.decision(2, maxAttempts = 3).retry)
    }

    @Test
    fun `non retryable status wins`() {
        assertFalse(RetryPolicy.decision(0, status = 401).retry)
    }

    @Test
    fun `retry after is honoured`() {
        val decision = RetryPolicy.decision(0, status = 429, retryAfterSeconds = 2)
        assertTrue(decision.retry)
        assertEquals(2_000, decision.delayMs)
    }

    @Test
    fun `retry after parsing`() {
        assertEquals(12L, RetryPolicy.parseRetryAfter("12"))
        assertNull(RetryPolicy.parseRetryAfter(null))
        assertNull(RetryPolicy.parseRetryAfter("not a value"))
    }
}
