package dev.jonas.openchat.core.net

/**
 * Incremental Server-Sent Events parser. Feed it one line at a time (without the line terminator)
 * and it returns complete events when a blank line is reached.
 */
class SseAccumulator {
    private val data = StringBuilder()
    private var eventName: String? = null
    private var eventId: String? = null

    fun feed(line: String): SseEvent? {
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(":")) return null

        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)

        when (field) {
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
            "event" -> eventName = value
            "id" -> eventId = value
            // "retry" is intentionally ignored; our own retry policy is used.
        }
        return null
    }

    fun end(): SseEvent? = dispatch()

    private fun dispatch(): SseEvent? {
        if (data.isEmpty()) {
            eventName = null
            return null
        }
        val event = SseEvent(id = eventId, event = eventName, data = data.toString())
        data.clear()
        eventName = null
        return event
    }
}

data class SseEvent(
    val id: String?,
    val event: String?,
    val data: String,
)

/** Returns true when the payload is the conventional end marker. */
fun SseEvent.isDoneMarker(): Boolean =
    data.trim() == "[DONE]" || event == "done"

fun SseEvent.eventNameOrData(): String? = event?.takeIf { it.isNotBlank() }
