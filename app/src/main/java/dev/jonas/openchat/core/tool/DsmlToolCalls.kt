package dev.jonas.openchat.core.tool

import dev.jonas.openchat.core.model.ContentBlock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Some OpenAI-compatible hosts return DeepSeek tool calls as raw DSML markup inside the
 * assistant's text instead of filling the structured `tool_calls` field. The markup is also
 * known to arrive in degraded forms (ASCII pipes, doubled pipes, spaces, or `DMSL`), so this
 * parser normalizes the delimiters before reading the calls.
 *
 * Canonical form:
 * ```
 * <｜DSML｜tool_calls>
 * <｜DSML｜invoke name="web_search">
 * <｜DSML｜parameter name="query" string="true">Kotlin</｜DSML｜parameter>
 * </｜DSML｜invoke>
 * </｜DSML｜tool_calls>
 * ```
 */
object DsmlToolCalls {
    private const val TOKEN = "｜DSML｜"
    private val MULTI_NEWLINE = Regex("""\n{3,}""")

    /**
     * Matches the opening delimiter of any tag variant, e.g. `<｜DSML｜`, `<|DSML|`,
     * `<||DSML||`, `< | | DMSL | |`. Only the delimiter and tag name are replaced so that
     * attributes after the tag name are preserved.
     */
    private val TAG_PREFIX = Regex(
        """<\s*(/)?\s*(?:[|｜]\s*)+d[ms]{2}l\s*(?:[|｜]\s*)+([A-Za-z_][A-Za-z0-9_]*)""",
        RegexOption.IGNORE_CASE,
    )

    private val WRAPPER_OPEN = Regex(
        """<$TOKEN(?:tool_?calls?|calls?|tool)\b[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val WRAPPER_CLOSE = Regex(
        """</$TOKEN(?:tool_?calls?|calls?|tool)\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val INVOKE = Regex(
        """<${TOKEN}invoke\b([^>]*)>(.*?)</${TOKEN}invoke>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val PARAMETER = Regex(
        """<${TOKEN}parameter\b([^>]*)>(.*?)</${TOKEN}parameter>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val ATTRIBUTE = Regex(
        """([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""",
    )

    data class Parsed(
        /** Assistant text with every recognized DSML tool-call block removed. */
        val text: String,
        /** Calls whose names are allowed by the caller. Unknown tools are stripped, not returned. */
        val calls: List<ContentBlock.ToolCall>,
    )

    /**
     * Removes DSML tool-call blocks from [text] and returns the calls they contain.
     *
     * When [allowedNames] is non-null only those tool names are returned. Unknown names are
     * still stripped from the visible text so raw markup never reaches the transcript.
     */
    fun extract(text: String, allowedNames: Set<String>? = null): Parsed {
        if (!text.contains('|') && !text.contains('｜')) return Parsed(text, emptyList())

        val normalized = normalize(text)
        val visible = StringBuilder()
        val calls = mutableListOf<ContentBlock.ToolCall>()
        var cursor = 0
        var removed = false

        while (cursor < normalized.length) {
            val open = WRAPPER_OPEN.find(normalized, cursor) ?: break
            val close = WRAPPER_CLOSE.find(normalized, open.range.last + 1) ?: break
            val invokes = parseInvokes(normalized.substring(open.range.last + 1, close.range.first))

            if (invokes.isEmpty()) {
                // Not a well-formed call block: keep it verbatim instead of eating user text.
                visible.append(normalized, cursor, open.range.last + 1)
                cursor = open.range.last + 1
                continue
            }

            visible.append(normalized, cursor, open.range.first)
            calls += invokes.filter { allowedNames == null || it.name in allowedNames }
            cursor = close.range.last + 1
            removed = true
        }
        visible.append(normalized, cursor, normalized.length)

        return Parsed(
            text = if (removed) {
                visible.toString().trim().replace(MULTI_NEWLINE, "\n\n")
            } else {
                text
            },
            calls = calls,
        )
    }

    private fun normalize(text: String): String = TAG_PREFIX.replace(text) { match ->
        val slash = match.groupValues[1]
        val tag = match.groupValues[2].lowercase()
        "<$slash$TOKEN$tag"
    }

    private fun parseInvokes(body: String): List<ContentBlock.ToolCall> =
        INVOKE.findAll(body).mapNotNull { match ->
            val name = attributes(match.groupValues[1])["name"]?.trim().orEmpty()
            if (name.isBlank()) return@mapNotNull null

            val arguments = buildJsonObject {
                PARAMETER.findAll(match.groupValues[2]).forEach { parameter ->
                    val parameterAttributes = attributes(parameter.groupValues[1])
                    val key = parameterAttributes["name"]?.trim().orEmpty()
                    if (key.isBlank()) return@forEach
                    val raw = parameter.groupValues[2]
                    // DSML defaults to a raw string; string="false" means the value is JSON.
                    val isString = !parameterAttributes["string"].equals("false", ignoreCase = true)
                    put(key, if (isString) JsonPrimitive(raw) else jsonValue(raw))
                }
            }
            ContentBlock.ToolCall(id = "", name = name, arguments = arguments.toString())
        }.toList()

    private fun attributes(raw: String): Map<String, String> =
        ATTRIBUTE.findAll(raw).associate { match ->
            val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
            match.groupValues[1].lowercase() to value
        }

    private fun jsonValue(raw: String): JsonElement =
        runCatching { Json.parseToJsonElement(raw.trim()) }
            .getOrElse { JsonPrimitive(raw) }
}
