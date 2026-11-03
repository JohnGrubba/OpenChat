package dev.jonas.openchat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.jonas.openchat.core.model.ChatMessage
import dev.jonas.openchat.core.model.ContentBlock
import dev.jonas.openchat.core.model.MessageStatus
import dev.jonas.openchat.core.model.Role
import dev.jonas.openchat.core.model.Usage
import dev.jonas.openchat.core.tool.LocalTools
import dev.jonas.openchat.ui.common.FileActions
import dev.jonas.openchat.ui.markdown.MarkdownView
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@Composable
fun MessageList(
    state: ChatUiState,
    listState: LazyListState,
    onRetry: () -> Unit,
    onOpenImage: (String) -> Unit,
    onSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val streaming = state.streaming
    val messages = remember(state.messages, streaming?.messageId) {
        state.messages.filterNot { message ->
            streaming != null && message.id == streaming.messageId
        }
    }

    // Follow mode keeps the newest content pinned above the composer. Content growth must never
    // switch it off by itself: doing that made the list stop following as soon as the reply grew
    // taller than the viewport, so new text piled up below the fold. Only an explicit drag away
    // from the bottom pauses following (and returns it once the reader comes back down).
    var followBottom by remember { mutableStateOf(true) }
    val following by rememberUpdatedState(followBottom)
    val isDragged by listState.interactionSource.collectIsDraggedAsState()

    LaunchedEffect(isDragged) {
        if (isDragged) {
            followBottom = false
        } else if (!listState.canScrollForward) {
            // A drag that never moved away from the bottom should not park the jump button.
            followBottom = true
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canScrollForward) ->
                // Settling at the bottom (drag, fling or tap) resumes following.
                if (!scrolling && !canScrollForward) followBottom = true
            }
    }

    // Reasoning expansion is stored per message id so it survives scrolling out of view and the
    // hand-off from the streaming bubble to the saved message (same id, but a different item key).
    val reasoningChoices = remember { mutableStateMapOf<String, Boolean>() }
    val streamingNow = streaming != null
    val answerStarted = streaming?.text?.isNotBlank() == true

    fun reasoningExpanded(id: String): Boolean =
        reasoningChoices[id] ?: (streamingNow && id == streaming.messageId && !answerStarted)

    fun toggleReasoning(id: String) {
        val next = !reasoningExpanded(id)
        reasoningChoices[id] = next
        // Reading the reasoning should not be interrupted by auto-following the answer.
        if (next) followBottom = false
    }

    val scope = rememberCoroutineScope()
    val activeModelId = state.active?.modelId ?: state.settings.modelId
    val activeModelName = state.models.firstOrNull { it.id == activeModelId }?.name
        ?: state.settings.modelName.ifBlank { activeModelId }
    val scrollTrigger = listOf(
        state.active?.id,
        messages.size,
        streaming?.messageId,
        streaming?.text?.length,
        streaming?.reasoning?.length,
        state.toolActivities.size,
    )

    LaunchedEffect(scrollTrigger) {
        if (!following) return@LaunchedEffect
        // Wait for the frame that lays out the new content before scrolling to its end.
        withFrameNanos { }
        listState.snapToBottom()
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            if (messages.isEmpty() && streaming == null) {
                item(key = "empty") {
                    EmptyState(
                        onSuggestion = onSuggestion,
                        modelName = activeModelName,
                    )
                }
            }
            items(messages, key = { it.id }) { message ->
                val expanded = reasoningExpanded(message.id)
                MessageItem(
                    message = message,
                    models = state.models,
                    reasoningExpanded = expanded,
                    onToggleReasoning = { toggleReasoning(message.id) },
                    onRetry = onRetry,
                    onOpenImage = onOpenImage,
                )
            }
            // Running tool steps are transient list items so they show up in order, between the
            // assistant turn that requested them and whatever comes next.
            items(state.toolActivities, key = { "tool-${it.id}" }) { activity ->
                ToolActivityRow(activity)
            }
            if (streaming != null) {
                item(key = "streaming") {
                    StreamingMessage(
                        streaming = streaming,
                        modelName = activeModelName,
                        reasoningExpanded = reasoningExpanded(streaming.messageId),
                        onToggleReasoning = { toggleReasoning(streaming.messageId) },
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = !followBottom && listState.canScrollForward,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ScrollToLatest {
                followBottom = true
                scope.launch {
                    withFrameNanos { }
                    listState.snapToBottom()
                }
            }
        }
    }
}

/** Scrolls so the end of the last item sits on the bottom edge of the viewport. */
private suspend fun LazyListState.snapToBottom() {
    val layout = layoutInfo
    if (layout.totalItemsCount == 0) return
    val lastIndex = layout.totalItemsCount - 1
    val lastVisible = layout.visibleItemsInfo.lastOrNull()
    if (lastVisible == null || lastVisible.index < lastIndex) {
        scrollToItem(lastIndex)
    }
    val info = layoutInfo
    val tail = info.visibleItemsInfo.lastOrNull() ?: return
    val contentBottom = info.viewportEndOffset - info.afterContentPadding
    val overflow = tail.offset + tail.size - contentBottom
    if (overflow > 0) scrollBy(overflow.toFloat())
}

@Composable
private fun ScrollToLatest(onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
        shadowElevation = 4.dp,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Outlined.ArrowDownward,
                contentDescription = "Scroll to latest",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun EmptyState(onSuggestion: (String) -> Unit, modelName: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "How can I help?",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Using $modelName",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(28.dp))
        val suggestions = listOf(
            "Explain a tricky concept simply",
            "Write and refactor some code",
            "Summarize an attached document",
            "Search the web for the latest news",
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            suggestions.forEach { suggestion ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSuggestion(suggestion) },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                    Text(
                        text = suggestion,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageItem(
    message: ChatMessage,
    models: List<dev.jonas.openchat.core.model.ModelInfo>,
    reasoningExpanded: Boolean,
    onToggleReasoning: () -> Unit,
    onRetry: () -> Unit,
    onOpenImage: (String) -> Unit,
) {
    when (message.role) {
        Role.USER -> UserMessage(message, onOpenImage)
        Role.ASSISTANT -> AssistantMessage(
            message = message,
            models = models,
            reasoningExpanded = reasoningExpanded,
            onToggleReasoning = onToggleReasoning,
            onRetry = onRetry,
            onOpenImage = onOpenImage,
        )
        Role.TOOL -> ToolResultCard(message)
        Role.SYSTEM -> Unit
    }
}

@Composable
private fun UserMessage(message: ChatMessage, onOpenImage: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                val images = message.blocks.filterIsInstance<ContentBlock.Image>()
                if (images.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = if (message.text.isNotBlank()) 8.dp else 0.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        images.forEach { image ->
                            AsyncImage(
                                model = File(image.path),
                                contentDescription = image.name ?: "Attached image",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(168.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { onOpenImage(image.path) },
                            )
                        }
                    }
                }
                message.blocks.filterIsInstance<ContentBlock.Document>().forEach { document ->
                    Row(
                        modifier = Modifier
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = document.name,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = buildString {
                                    append(formatChars(document.text.length))
                                    if (document.truncated) append(" · truncated")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (message.text.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "You",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AssistantMessage(
    message: ChatMessage,
    models: List<dev.jonas.openchat.core.model.ModelInfo>,
    reasoningExpanded: Boolean,
    onToggleReasoning: () -> Unit,
    onRetry: () -> Unit,
    onOpenImage: (String) -> Unit,
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth()) {
        AssistantHeader(modelName = modelLabel(message.modelId, models))
        val reasoning = message.reasoning
        if (reasoning.isNotBlank()) {
            ReasoningSection(
                reasoning = reasoning,
                expanded = reasoningExpanded,
                streaming = false,
                onToggle = onToggleReasoning,
            )
        }
        if (message.text.isNotBlank()) {
            SelectionContainer {
                MarkdownView(
                    text = message.text,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        val calls = message.toolCalls
        if (calls.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                calls.forEach { call ->
                    ToolPill(call.name)
                }
            }
        }
        message.usage?.let { UsageRow(it) }

        if (message.status == MessageStatus.ERROR) {
            Spacer(modifier = Modifier.height(8.dp))
            ErrorCard(message.error ?: "Generation failed", onRetry)
        } else if (message.status == MessageStatus.CANCELLED ||
            message.status == MessageStatus.INTERRUPTED
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (message.status == MessageStatus.CANCELLED) "Stopped" else "Interrupted",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRetry) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Retry")
                }
            }
        }

        if (message.text.isNotBlank() && message.status == MessageStatus.COMPLETE) {
            Row(
                modifier = Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { FileActions.copy(context, message.text) }) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = "Copy message",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = { FileActions.share(context, message.text, "OpenChat") }) {
                    Icon(
                        Icons.Outlined.Share,
                        contentDescription = "Share message",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        if (message.images.isNotEmpty()) {
            // Assistant images are currently not produced by text models; this keeps forward compat.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                message.images.forEach { image ->
                    AsyncImage(
                        model = File(image.path),
                        contentDescription = null,
                        modifier = Modifier
                            .size(140.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onOpenImage(image.path) },
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantHeader(modelName: String) {
    Row(
        modifier = Modifier.padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(13.dp),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "OpenChat",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (modelName.isNotBlank()) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = modelName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ReasoningSection(
    reasoning: String,
    expanded: Boolean,
    streaming: Boolean,
    onToggle: () -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onToggle)
                .padding(horizontal = 2.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (streaming && !expanded) "Thinking…" else "Reasoning",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // No height animation here: animating item heights inside a streaming lazy list makes the
        // list jump around while the reader opens and closes the panel.
        if (expanded) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 260.dp)
                            .verticalScroll(scrollState)
                            .padding(12.dp),
                    ) {
                        MarkdownView(
                            text = reasoning,
                            modifier = Modifier.fillMaxWidth(),
                            streaming = streaming,
                        )
                    }
                }
            }
        }
    }
    if (streaming && expanded) {
        LaunchedEffect(reasoning.length) {
            withFrameNanos { }
            scrollState.scrollTo(scrollState.maxValue)
        }
    }
}

@Composable
private fun ToolPill(name: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (name == LocalTools.WEB_FETCH) Icons.Outlined.Public else Icons.Outlined.TravelExplore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp),
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = when (name) {
                    LocalTools.WEB_SEARCH -> "Web search"
                    LocalTools.WEB_FETCH -> "Read page"
                    else -> name
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToolResultCard(message: ChatMessage) {
    val result = message.blocks.filterIsInstance<ContentBlock.ToolResult>().firstOrNull() ?: return
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (result.name == LocalTools.WEB_SEARCH) {
                        Icons.Outlined.TravelExplore
                    } else {
                        Icons.Outlined.Public
                    },
                    contentDescription = null,
                    tint = if (result.isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        result.isError -> "Tool failed"
                        result.name == LocalTools.WEB_SEARCH -> "Web search results"
                        result.name == LocalTools.WEB_FETCH -> "Page content"
                        else -> result.name
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                SelectionContainer {
                    Text(
                        text = result.content,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = result.content.lineSequence().firstOrNull().orEmpty().take(160),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun StreamingMessage(
    streaming: StreamingState,
    modelName: String,
    reasoningExpanded: Boolean,
    onToggleReasoning: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        AssistantHeader(modelName = modelName)
        if (streaming.reasoning.isNotBlank()) {
            ReasoningSection(
                reasoning = streaming.reasoning,
                expanded = reasoningExpanded,
                streaming = true,
                onToggle = onToggleReasoning,
            )
        }
        if (streaming.text.isNotBlank()) {
            SelectionContainer {
                MarkdownView(
                    text = streaming.text,
                    modifier = Modifier.fillMaxWidth(),
                    streaming = true,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            StreamingCursor()
        } else if (streaming.reasoning.isBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Thinking…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ToolActivityRow(activity: ToolActivity) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (activity.status) {
                ToolActivityStatus.RUNNING -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                ToolActivityStatus.DONE -> Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                ToolActivityStatus.ERROR -> Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = activity.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StreamingCursor() {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "cursorAlpha",
    )
    Box(
        modifier = Modifier
            .size(width = 8.dp, height = 16.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
    )
}

@Composable
private fun UsageRow(usage: Usage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (usage.billingSource?.startsWith("go") == true) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    text = "Go",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            text = "${formatCount(usage.inputTokens)} in · ${formatCount(usage.outputTokens)} out",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (usage.reasoningTokens > 0) {
            Text(
                text = "${formatCount(usage.reasoningTokens)} reasoning",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (usage.cacheReadTokens > 0) {
            Text(
                text = "${formatCount(usage.cacheReadTokens)} cached",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (usage.costUsd > 0) {
            Text(
                text = "$" + String.format(Locale.US, "%.4f", usage.costUsd),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (usage.allowanceUsd > 0) {
            Text(
                text = "counted $" + String.format(Locale.US, "%.4f", usage.allowanceUsd) + " toward Go",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Something went wrong",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onRetry) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Try again")
            }
        }
    }
}

private fun modelLabel(modelId: String?, models: List<dev.jonas.openchat.core.model.ModelInfo>): String {
    if (modelId.isNullOrBlank()) return "assistant"
    return models.firstOrNull { it.id == modelId }?.name ?: modelId
}

private fun formatCount(value: Long): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.US, "%.1fk", value / 1_000.0)
    else -> value.toString()
}

private fun formatChars(value: Int): String = when {
    value >= 100_000 -> String.format(Locale.US, "%.0fk chars", value / 1_000.0)
    value >= 1_000 -> String.format(Locale.US, "%.1fk chars", value / 1_000.0)
    else -> "$value chars"
}
