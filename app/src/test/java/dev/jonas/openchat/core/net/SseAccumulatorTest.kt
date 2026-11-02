package dev.jonas.openchat.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseAccumulatorTest {
    @Test
    fun `multi line data is joined with newlines`() {
        val accumulator = SseAccumulator()
        assertNull(accumulator.feed("data: first"))
        assertNull(accumulator.feed("data: second"))
        val event = accumulator.feed("")
        assertEquals("first\nsecond", event!!.data)
    }

    @Test
    fun `comments and unknown fields are ignored`() {
        val accumulator = SseAccumulator()
        assertNull(accumulator.feed(": keep-alive"))
        assertNull(accumulator.feed("retry: 1000"))
        assertNull(accumulator.feed("data: hello"))
        val event = accumulator.feed("")
        assertEquals("hello", event!!.data)
    }

    @Test
    fun `event name and id are captured`() {
        val accumulator = SseAccumulator()
        accumulator.feed("id: 42")
        accumulator.feed("event: message_delta")
        accumulator.feed("data: {}")
        val event = accumulator.feed("")!!
        assertEquals("42", event.id)
        assertEquals("message_delta", event.event)
        assertEquals("{}", event.data)
    }

    @Test
    fun `end flushes a pending event without trailing blank line`() {
        val accumulator = SseAccumulator()
        accumulator.feed("data: tail")
        assertEquals("tail", accumulator.end()!!.data)
    }

    @Test
    fun `done marker is recognised`() {
        val event = SseEvent(null, null, "[DONE]")
        assertEquals(true, event.isDoneMarker())
    }
}
