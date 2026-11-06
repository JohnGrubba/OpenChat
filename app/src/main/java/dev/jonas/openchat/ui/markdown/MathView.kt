package dev.jonas.openchat.ui.markdown

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jonas.openchat.core.text.MathText

private sealed interface MathNode {
    data class Text(val text: String) : MathNode
    data class Fraction(
        val numerator: List<MathNode>,
        val denominator: List<MathNode>,
    ) : MathNode

    data class Radical(
        val index: String?,
        val content: List<MathNode>,
    ) : MathNode

    data class Boxed(val content: List<MathNode>) : MathNode
}

/**
 * Renders a display (block) formula with the built-in layout engine.
 *
 * Fractions are stacked with a horizontal bar (the continental/European textbook style), roots
 * are drawn with an overline, and long formulas scroll horizontally. This is intentionally
 * implemented in Compose rather than a native TeX library: formulas are untrusted model output,
 * and a native crash would kill the whole app and repeat on every launch.
 */
@Composable
internal fun MathBlockView(tex: String) {
    val lines = remember(tex) { displayMathLines(tex) }
    val fallback = remember(tex) { MathText.toDisplay(tex).ifBlank { tex } }
    val scrollState = rememberScrollState()
    val color = MaterialTheme.colorScheme.onSurface

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f),
        ),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            val viewport = maxWidth
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState),
            ) {
                Box(
                    modifier = Modifier.widthIn(min = viewport),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (lines.isEmpty()) {
                            Text(
                                text = fallback,
                                fontFamily = FontFamily.Serif,
                                fontSize = 18.sp,
                                lineHeight = 28.sp,
                                textAlign = TextAlign.Center,
                                color = color,
                            )
                        } else {
                            lines.forEach { line ->
                                val nodes = remember(line) {
                                    runCatching { DisplayMathParser(line).parse() }
                                        .getOrElse { emptyList() }
                                }
                                if (nodes.isEmpty()) {
                                    Text(
                                        text = MathText.toUnicode(line).ifBlank { line },
                                        fontFamily = FontFamily.Serif,
                                        fontSize = 18.sp,
                                        lineHeight = 28.sp,
                                        textAlign = TextAlign.Center,
                                        color = color,
                                    )
                                } else {
                                    MathNodes(nodes, color = color)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MathNodes(
    nodes: List<MathNode>,
    scale: Float = 1f,
    color: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        nodes.forEach { node ->
            when (node) {
                is MathNode.Text -> Text(
                    text = node.text,
                    fontFamily = FontFamily.Serif,
                    fontSize = (18f * scale).sp,
                    lineHeight = (24f * scale).sp,
                    color = color,
                )
                is MathNode.Fraction -> FractionView(node, scale, color)
                is MathNode.Radical -> RadicalView(node, scale, color)
                is MathNode.Boxed -> BoxedView(node, scale, color)
            }
        }
    }
}

@Composable
private fun FractionView(
    fraction: MathNode.Fraction,
    scale: Float,
    color: Color,
) {
    Column(
        modifier = Modifier.width(IntrinsicSize.Max),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MathNodes(fraction.numerator, scale = scale * 0.82f, color = color)
        HorizontalDivider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            thickness = (1f * scale).dp,
            color = color.copy(alpha = 0.8f),
        )
        MathNodes(fraction.denominator, scale = scale * 0.82f, color = color)
    }
}

@Composable
private fun RadicalView(
    radical: MathNode.Radical,
    scale: Float,
    color: Color,
) {
    val radicalWidth = (11f * scale).dp
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!radical.index.isNullOrBlank()) {
            Text(
                text = radical.index,
                fontFamily = FontFamily.Serif,
                fontSize = (10f * scale).sp,
                color = color,
                modifier = Modifier.padding(top = (1f * scale).dp, end = 1.dp),
            )
        }
        Box(
            modifier = Modifier
                .drawBehind {
                    val width = size.width
                    val height = size.height
                    val hook = radicalWidth.toPx()
                    val strokeWidth = (1.2f * scale).dp.toPx().coerceAtLeast(1f)
                    val path = Path().apply {
                        moveTo(0f, height * 0.48f)
                        lineTo(hook * 0.32f, height * 0.82f)
                        lineTo(hook * 0.82f, 0f)
                        lineTo(width, 0f)
                    }
                    drawPath(
                        path = path,
                        color = color,
                        style = Stroke(
                            width = strokeWidth,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }
                .padding(start = radicalWidth, top = (1f * scale).dp),
        ) {
            MathNodes(radical.content, scale = scale, color = color)
        }
    }
}

@Composable
private fun BoxedView(
    boxed: MathNode.Boxed,
    scale: Float,
    color: Color,
) {
    Box(
        modifier = Modifier
            .border(
                width = (1f * scale).dp,
                color = color.copy(alpha = 0.75f),
                shape = RoundedCornerShape((5f * scale).dp),
            )
            .padding(horizontal = (7f * scale).dp, vertical = (4f * scale).dp),
    ) {
        MathNodes(boxed.content, scale = scale, color = color)
    }
}

/**
 * Splits aligned/matrix-like environments into display lines. A fraction is then stacked on each
 * line instead of being flattened into a slash.
 */
private fun displayMathLines(tex: String): List<String> {
    if (!tex.contains("\\\\") && !tex.contains("\\begin{")) {
        return listOf(tex.trim()).filter { it.isNotEmpty() }
    }
    val cleaned = tex
        .replace(Regex("\\\\begin\\{[^}]+\\}"), "")
        .replace(Regex("\\\\end\\{[^}]+\\}"), "")
    return cleaned
        .split("\\\\")
        .map { it.replace('&', ' ').trim() }
        .filter { it.isNotEmpty() }
}

/**
 * Small LaTeX parser for display math. It extracts the structures that benefit from real layout
 * (fractions and roots) and hands everything else to [MathText] for symbol/script conversion.
 */
private class DisplayMathParser(private val source: String) {
    private var i = 0

    fun parse(): List<MathNode> = parseNodes(null)

    private fun parseNodes(stop: Char?): List<MathNode> {
        val nodes = mutableListOf<MathNode>()
        val buffer = StringBuilder()

        fun flush() {
            if (buffer.isEmpty()) return
            val text = MathText.toUnicode(buffer.toString()).trim()
            if (text.isNotEmpty()) nodes += MathNode.Text(text)
            buffer.clear()
        }

        while (i < source.length) {
            val ch = source[i]
            if (stop != null && ch == stop) {
                i++
                break
            }
            when {
                commandAt("frac") || commandAt("dfrac") || commandAt("tfrac") -> {
                    flush()
                    readCommandName()
                    nodes += MathNode.Fraction(parseArgument(), parseArgument())
                }

                commandAt("sqrt") -> {
                    flush()
                    readCommandName()
                    val index = parseOptionalIndex()
                    nodes += MathNode.Radical(index, parseArgument())
                }

                commandAt("boxed") || commandAt("fbox") -> {
                    flush()
                    readCommandName()
                    nodes += MathNode.Boxed(parseArgument())
                }

                commandAt("text") || commandAt("textrm") || commandAt("textnormal") ||
                    commandAt("mathrm") || commandAt("mathbf") || commandAt("mathit") ||
                    commandAt("mathsf") || commandAt("mathtt") || commandAt("mathcal") ||
                    commandAt("mathbb") || commandAt("mathfrak") || commandAt("operatorname") -> {
                    flush()
                    readCommandName()
                    val raw = readRawArgument()
                    if (raw.isNotEmpty()) nodes += MathNode.Text(raw)
                }

                ch == '{' -> {
                    flush()
                    i++
                    nodes += parseNodes('}')
                }

                ch == '^' || ch == '_' -> {
                    // Keep scripts in the text stream; MathText converts them to Unicode
                    // super/subscripts, which keeps the layout stable.
                    buffer.append(ch)
                    i++
                    buffer.append(readScriptArgument())
                }

                ch == '\\' -> buffer.append(readCommand())
                else -> {
                    buffer.append(ch)
                    i++
                }
            }
        }
        flush()
        return nodes
    }

    private fun commandAt(name: String): Boolean {
        if (!source.startsWith("\\$name", i)) return false
        val after = i + name.length + 1
        return after >= source.length || !source[after].isLetter()
    }

    private fun readCommandName(): String {
        if (i < source.length && source[i] == '\\') i++
        val start = i
        while (i < source.length && source[i].isLetter()) i++
        return source.substring(start, i)
    }

    private fun readCommand(): String {
        val start = i
        if (i < source.length && source[i] == '\\') {
            i++
            if (i < source.length && source[i].isLetter()) {
                while (i < source.length && source[i].isLetter()) i++
            } else if (i < source.length) {
                i++
            }
        }
        return source.substring(start, i)
    }

    private fun parseArgument(): List<MathNode> {
        skipSpaces()
        if (i >= source.length) return emptyList()
        return when (source[i]) {
            '{' -> {
                i++
                parseNodes('}')
            }
            '\\' -> {
                val command = readCommand()
                val value = MathText.toUnicode(command).trim()
                if (value.isEmpty()) emptyList() else listOf(MathNode.Text(value))
            }
            else -> {
                val ch = source[i]
                i++
                listOf(MathNode.Text(ch.toString()))
            }
        }
    }

    private fun parseOptionalIndex(): String? {
        skipSpaces()
        if (i >= source.length || source[i] != '[') return null
        i++
        val start = i
        while (i < source.length && source[i] != ']') i++
        val raw = source.substring(start, i)
        if (i < source.length) i++
        return MathText.toUnicode(raw).trim().ifBlank { null }
    }

    private fun readRawArgument(): String {
        skipSpaces()
        if (i >= source.length || source[i] != '{') {
            return if (i < source.length) {
                val ch = source[i]
                i++
                ch.toString()
            } else {
                ""
            }
        }
        i++
        val start = i
        var depth = 1
        while (i < source.length && depth > 0) {
            when (source[i]) {
                '\\' -> i += 2
                '{' -> {
                    depth++
                    i++
                }
                '}' -> {
                    depth--
                    if (depth > 0) i++ else i++
                }
                else -> i++
            }
        }
        return source.substring(start, (i - 1).coerceAtLeast(start))
    }

    private fun readScriptArgument(): String {
        skipSpaces()
        if (i >= source.length) return ""
        if (source[i] == '\\') return readCommand()
        if (source[i] != '{') {
            val ch = source[i]
            i++
            return ch.toString()
        }
        val start = i
        var depth = 0
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        i++
                        break
                    }
                }
            }
            i++
        }
        return source.substring(start, i.coerceAtMost(source.length))
    }

    private fun skipSpaces() {
        while (i < source.length && source[i] == ' ') i++
    }
}
