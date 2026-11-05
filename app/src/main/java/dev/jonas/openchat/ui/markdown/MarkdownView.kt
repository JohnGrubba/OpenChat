package dev.jonas.openchat.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jonas.openchat.core.text.MarkdownParser
import dev.jonas.openchat.core.text.MathText
import dev.jonas.openchat.core.text.MdBlock
import dev.jonas.openchat.core.text.MdInline
import dev.jonas.openchat.ui.common.FileActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun MarkdownView(
    text: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
) {
    val latestText by rememberUpdatedState(text)
    var blocks by remember {
        mutableStateOf(if (streaming) emptyList() else MarkdownParser.parse(text))
    }
    LaunchedEffect(text, streaming) {
        if (!streaming) {
            blocks = withContext(Dispatchers.Default) { MarkdownParser.parse(text) }
        }
    }
    LaunchedEffect(streaming) {
        if (streaming) {
            while (true) {
                delay(120)
                blocks = withContext(Dispatchers.Default) { MarkdownParser.parse(latestText) }
            }
        }
    }
    Column(modifier = modifier) {
        if (blocks.isEmpty() && text.isNotBlank()) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            blocks.forEach { block -> MarkdownBlock(block) }
        }
    }
}

@Composable
private fun MarkdownBlock(block: MdBlock) {
    when (block) {
        is MdBlock.Heading -> {
            val style = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp)
                2 -> MaterialTheme.typography.titleLarge
                3 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
            }
            InlineText(
                inlines = block.inlines,
                style = style,
                modifier = Modifier.padding(top = if (block.level == 1) 12.dp else 10.dp, bottom = 4.dp),
            )
        }
        is MdBlock.Paragraph -> InlineText(
            inlines = block.inlines,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        is MdBlock.CodeBlock -> CodeBlockView(block)
        is MdBlock.Quote -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(20.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        RoundedCornerShape(2.dp),
                    ),
            )
            Column(modifier = Modifier.padding(start = 12.dp)) {
                block.blocks.forEach { MarkdownBlock(it) }
            }
        }
        is MdBlock.BulletList -> Column(
            modifier = Modifier.padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            block.items.forEach { item ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        item.forEach { MarkdownBlock(it) }
                    }
                }
            }
        }
        is MdBlock.OrderedList -> Column(
            modifier = Modifier.padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            block.items.forEachIndexed { index, item ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "${block.start + index}.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        item.forEach { MarkdownBlock(it) }
                    }
                }
            }
        }
        MdBlock.Divider -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        is MdBlock.Table -> TableView(block)
        is MdBlock.Math -> MathBlockView(block.tex)
    }
}

@Composable
private fun InlineText(
    inlines: List<MdInline>,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val annotated = remember(inlines, colors) {
        buildInline(inlines, colors.primary, colors.onSurfaceVariant)
    }
    Text(text = annotated, style = style, modifier = modifier)
}

private fun buildInline(
    inlines: List<MdInline>,
    linkColor: Color,
    codeColor: Color,
): AnnotatedString = buildAnnotatedString {
    inlines.forEach { inline ->
        when (inline) {
            is MdInline.Text -> append(inline.text)
            is MdInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(buildInline(inline.children, linkColor, codeColor))
            }
            is MdInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                append(buildInline(inline.children, linkColor, codeColor))
            }
            is MdInline.Strike -> withStyle(
                SpanStyle(textDecoration = TextDecoration.LineThrough),
            ) {
                append(buildInline(inline.children, linkColor, codeColor))
            }
            is MdInline.Code -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    color = codeColor,
                    background = codeColor.copy(alpha = 0.09f),
                ),
            ) {
                append(" ${inline.text} ")
            }
            is MdInline.Link -> withLink(
                LinkAnnotation.Url(
                    url = inline.url,
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ),
                ),
            ) {
                append(inline.label)
            }
            is MdInline.Math -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Serif,
                    letterSpacing = 0.2.sp,
                ),
            ) {
                append(MathText.toUnicode(inline.tex))
            }
        }
    }
}

@Composable
private fun CodeBlockView(block: MdBlock.CodeBlock) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val language = block.language.ifBlank { "code" }
    val fileName = remember(block.language) { codeFileName(block.language) }
    val highlighted = remember(block.code, block.language, colors) {
        highlightCode(block.code, block.language, colors.primary, colors.secondary, colors.onSurfaceVariant)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = colors.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = language.lowercase(Locale.ROOT),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { FileActions.copy(context, block.code) },
                    modifier = Modifier.width(34.dp),
                ) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = "Copy code",
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.width(17.dp),
                    )
                }
                IconButton(
                    onClick = {
                        FileActions.saveText(context, fileName, block.code, mimeForName(fileName))
                    },
                    modifier = Modifier.width(34.dp),
                ) {
                    Icon(
                        Icons.Outlined.Download,
                        contentDescription = "Save code",
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.width(17.dp),
                    )
                }
                IconButton(
                    onClick = { FileActions.share(context, block.code, language) },
                    modifier = Modifier.width(34.dp),
                ) {
                    Icon(
                        Icons.Outlined.Share,
                        contentDescription = "Share code",
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.width(17.dp),
                    )
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(
                    text = highlighted,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = colors.onSurface,
                    ),
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun TableView(block: MdBlock.Table) {
    val colors = MaterialTheme.colorScheme
    val columns = maxOf(
        block.headers.size,
        block.rows.maxOfOrNull { it.size } ?: 0,
    )
    if (columns == 0) return

    val weights = remember(block, columns) { columnWeights(block, columns) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        val minColumnWidth = 112.dp
        val viewportWidth = maxWidth
        val minimumWidth = minColumnWidth * columns
        val tableWidth = maxOf(viewportWidth, minimumWidth)
        val extraWidth = tableWidth - minimumWidth
        val totalWeight = weights.sum().takeIf { it > 0f } ?: 1f
        // All columns share explicit widths, so every row lines up. Values are always start-aligned.
        val widths = weights.map { weight ->
            minColumnWidth + extraWidth * (weight / totalWeight)
        }
        val scrollState = rememberScrollState()

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = colors.surfaceVariant.copy(alpha = 0.55f),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                colors.outlineVariant.copy(alpha = 0.8f),
            ),
        ) {
            Column(modifier = Modifier.horizontalScroll(scrollState)) {
                Column(modifier = Modifier.width(tableWidth)) {
                    Row(modifier = Modifier.background(colors.primary.copy(alpha = 0.07f))) {
                        repeat(columns) { column ->
                            TableCell(
                                inlines = block.headers.getOrNull(column),
                                width = widths[column],
                                header = true,
                            )
                        }
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                    block.rows.forEachIndexed { index, row ->
                        Row(
                            modifier = Modifier.background(
                                if (index % 2 == 1) {
                                    colors.surface.copy(alpha = 0.28f)
                                } else {
                                    Color.Transparent
                                },
                            ),
                        ) {
                            repeat(columns) { column ->
                                TableCell(
                                    inlines = row.getOrNull(column),
                                    width = widths[column],
                                    header = false,
                                )
                            }
                        }
                        if (index != block.rows.lastIndex) {
                            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.55f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TableCell(
    inlines: List<MdInline>?,
    width: Dp,
    header: Boolean,
) {
    Box(
        modifier = Modifier
            .width(width)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        if (inlines != null) {
            InlineText(
                inlines = inlines,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Start,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun columnWeights(table: MdBlock.Table, columns: Int): List<Float> =
    (0 until columns).map { column ->
        var longest = inlineLength(table.headers.getOrNull(column).orEmpty())
        table.rows.forEach { row ->
            longest = maxOf(longest, inlineLength(row.getOrNull(column).orEmpty()))
        }
        longest.coerceIn(4, 36).toFloat()
    }

private fun inlineLength(inlines: List<MdInline>): Int = inlines.sumOf { inline ->
    when (inline) {
        is MdInline.Text -> inline.text.length
        is MdInline.Code -> inline.text.length
        is MdInline.Link -> inline.label.length
        is MdInline.Math -> inline.tex.length
        is MdInline.Bold -> inlineLength(inline.children)
        is MdInline.Italic -> inlineLength(inline.children)
        is MdInline.Strike -> inlineLength(inline.children)
    }
}

private fun highlightCode(
    code: String,
    language: String,
    keywordColor: Color,
    stringColor: Color,
    commentColor: Color,
): AnnotatedString {
    val keywords = keywordsFor(language)
    val dashComments = language.lowercase(Locale.ROOT) in setOf("sql", "lua", "haskell")
    return buildAnnotatedString {
        var i = 0
        while (i < code.length) {
            val ch = code[i]
            when {
                ch == '/' && i + 1 < code.length && code[i + 1] == '/' -> {
                    val end = code.indexOf('\n', i).let { if (it == -1) code.length else it }
                    withStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic)) {
                        append(code.substring(i, end))
                    }
                    i = end
                }
                ch == '#' && language in hashCommentLanguages -> {
                    val end = code.indexOf('\n', i).let { if (it == -1) code.length else it }
                    withStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic)) {
                        append(code.substring(i, end))
                    }
                    i = end
                }
                ch == '-' && i + 1 < code.length && code[i + 1] == '-' && dashComments -> {
                    val end = code.indexOf('\n', i).let { if (it == -1) code.length else it }
                    withStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic)) {
                        append(code.substring(i, end))
                    }
                    i = end
                }
                ch == '/' && i + 1 < code.length && code[i + 1] == '*' -> {
                    val end = code.indexOf("*/", i + 2).let {
                        if (it == -1) code.length else it + 2
                    }
                    withStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic)) {
                        append(code.substring(i, end))
                    }
                    i = end
                }
                ch == '"' || ch == '\'' || ch == '`' -> {
                    var end = i + 1
                    while (end < code.length) {
                        if (code[end] == '\\') {
                            end += 2
                            continue
                        }
                        if (code[end] == ch) {
                            end++
                            break
                        }
                        end++
                    }
                    withStyle(SpanStyle(color = stringColor)) {
                        append(code.substring(i, minOf(end, code.length)))
                    }
                    i = minOf(end, code.length)
                }
                ch.isDigit() -> {
                    var end = i + 1
                    while (end < code.length &&
                        (code[end].isLetterOrDigit() || code[end] == '.' || code[end] == '_')
                    ) {
                        end++
                    }
                    withStyle(SpanStyle(color = keywordColor.copy(alpha = 0.85f))) {
                        append(code.substring(i, end))
                    }
                    i = end
                }
                ch.isLetter() || ch == '_' -> {
                    var end = i + 1
                    while (end < code.length && (code[end].isLetterOrDigit() || code[end] == '_')) {
                        end++
                    }
                    val word = code.substring(i, end)
                    if (word in keywords) {
                        withStyle(SpanStyle(color = keywordColor, fontWeight = FontWeight.Medium)) {
                            append(word)
                        }
                    } else {
                        append(word)
                    }
                    i = end
                }
                else -> {
                    append(ch)
                    i++
                }
            }
        }
    }
}

private val hashCommentLanguages = setOf(
    "python", "py", "bash", "sh", "shell", "zsh", "yaml", "yml", "toml", "ruby", "rb",
    "perl", "r", "makefile", "dockerfile", "properties",
)

private val commonKeywords = setOf(
    "fun", "val", "var", "if", "else", "for", "while", "return", "class", "object",
    "interface", "import", "package", "when", "try", "catch", "finally", "throw", "throws",
    "null", "true", "false", "new", "public", "private", "protected", "static", "final",
    "void", "int", "long", "short", "double", "float", "char", "boolean", "byte", "String",
    "const", "let", "async", "await", "def", "from", "in", "is", "as", "type", "struct",
    "func", "go", "defer", "select", "case", "switch", "break", "continue", "do", "this",
    "self", "super", "extends", "implements", "override", "suspend", "data", "sealed",
    "enum", "companion", "init", "by", "where", "match", "with", "yield", "lambda", "raise",
    "except", "pass", "and", "or", "not", "None", "True", "False", "fn", "mut", "use", "mod",
    "pub", "impl", "trait", "crate", "unsafe", "extern", "loop", "move", "ref", "sizeof",
    "typedef", "unsigned", "signed", "namespace", "template", "virtual", "inline", "auto",
    "instanceof", "typeof", "delete", "export", "default", "extends", "readonly", "abstract",
    "declare", "keyof", "infer", "satisfies", "require", "module", "end", "then", "begin",
    "rescue", "ensure", "elif", "fi", "esac", "done", "local", "function",
)

private fun keywordsFor(language: String): Set<String> {
    return when (language.lowercase(Locale.ROOT)) {
        "sql" -> setOf(
            "select", "from", "where", "insert", "into", "update", "delete", "create", "table",
            "alter", "drop", "join", "left", "right", "inner", "outer", "on", "group", "by",
            "order", "having", "limit", "offset", "and", "or", "not", "null", "as", "distinct",
            "union", "values", "set", "primary", "key", "foreign", "references", "index",
        )
        else -> commonKeywords
    }
}

private fun codeFileName(language: String): String {
    val ext = when (language.lowercase(Locale.ROOT)) {
        "kotlin", "kt" -> "kt"
        "java" -> "java"
        "javascript", "js" -> "js"
        "typescript", "ts" -> "ts"
        "tsx" -> "tsx"
        "jsx" -> "jsx"
        "python", "py" -> "py"
        "json" -> "json"
        "xml" -> "xml"
        "html" -> "html"
        "css" -> "css"
        "scss" -> "scss"
        "bash", "sh", "shell", "zsh" -> "sh"
        "sql" -> "sql"
        "yaml", "yml" -> "yaml"
        "toml" -> "toml"
        "rust", "rs" -> "rs"
        "go" -> "go"
        "swift" -> "swift"
        "c" -> "c"
        "cpp", "c++" -> "cpp"
        "csharp", "cs" -> "cs"
        "markdown", "md" -> "md"
        "dockerfile" -> "dockerfile"
        else -> "txt"
    }
    return "openchat-code.$ext"
}

private fun mimeForName(name: String): String = when (name.substringAfterLast('.', "")) {
    "json" -> "application/json"
    "xml" -> "application/xml"
    "html" -> "text/html"
    "md" -> "text/markdown"
    "js" -> "text/javascript"
    "css" -> "text/css"
    else -> "text/plain"
}
