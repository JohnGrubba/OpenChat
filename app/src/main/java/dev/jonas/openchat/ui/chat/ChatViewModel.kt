package dev.jonas.openchat.ui.chat

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.jonas.openchat.core.api.EndpointResolver
import dev.jonas.openchat.core.api.ModelCatalog
import dev.jonas.openchat.core.model.AppSettings
import dev.jonas.openchat.core.model.ChatMessage
import dev.jonas.openchat.core.model.ChatRequest
import dev.jonas.openchat.core.model.ContentBlock
import dev.jonas.openchat.core.model.Conversation
import dev.jonas.openchat.core.model.MessageStatus
import dev.jonas.openchat.core.model.ModelInfo
import dev.jonas.openchat.core.model.ProviderEvent
import dev.jonas.openchat.core.model.RequestBlock
import dev.jonas.openchat.core.model.RequestMessage
import dev.jonas.openchat.core.model.ResolvedEndpoint
import dev.jonas.openchat.core.model.Role
import dev.jonas.openchat.core.model.ThemeMode
import dev.jonas.openchat.core.model.ToolCallAccumulator
import dev.jonas.openchat.core.model.Usage
import dev.jonas.openchat.core.text.ConversationTrimmer
import dev.jonas.openchat.core.tool.DsmlToolCalls
import dev.jonas.openchat.core.tool.LocalTools
import dev.jonas.openchat.di.AppContainer
import dev.jonas.openchat.network.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.repository
    private val settingsStore = container.settingsStore
    private val inference = container.inferenceClient
    private val modelService = container.modelService
    private val attachments = container.attachmentStore
    private val toolExecutor = container.toolExecutor

    private val _ui = MutableStateFlow(ChatUiState())
    val uiState = _ui.asStateFlow()

    private val activeId = MutableStateFlow<String?>(null)
    private var generationJob: kotlinx.coroutines.Job? = null
    private var settingsLoaded = false
    private var initialAutoSelect = false

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { settings ->
                val firstLoad = !settingsLoaded
                settingsLoaded = true
                _ui.update { state ->
                    val endpointChanged = state.modelsEndpoint != settings.endpointUrl
                    val builtIn = if (EndpointResolver.isOpenCodeGo(settings.endpointUrl)) {
                        ModelCatalog.openCodeGo
                    } else {
                        ModelCatalog.fallback
                    }
                    state.copy(
                        settings = settings,
                        settingsLoaded = true,
                        models = if (endpointChanged || state.models.isEmpty()) builtIn else state.models,
                        modelsEndpoint = if (endpointChanged) settings.endpointUrl else state.modelsEndpoint,
                    )
                }
                if (firstLoad && settings.apiKey.isNotBlank()) {
                    refreshModels(silent = true)
                }
            }
        }

        viewModelScope.launch {
            repository.observeConversations().collect { conversations ->
                val id = activeId.value
                val active = conversations.firstOrNull { it.id == id }
                _ui.update { it.copy(conversations = conversations, active = active) }
                if (id == null && conversations.isNotEmpty() && !initialAutoSelect) {
                    initialAutoSelect = true
                    selectConversation(conversations.first().id)
                } else if (id != null && active == null) {
                    activeId.value = conversations.firstOrNull()?.id
                    if (conversations.isEmpty()) {
                        _ui.update { it.copy(active = null, messages = emptyList()) }
                    }
                }
            }
        }

        viewModelScope.launch {
            activeId.filterNotNull()
                .flatMapLatest { id -> repository.observeMessages(id) }
                .collect { messages -> _ui.update { it.copy(messages = messages) } }
        }
    }

    fun onInputChange(value: String) = _ui.update { it.copy(input = value) }

    fun clearError() = _ui.update { it.copy(error = null) }

    fun selectConversation(id: String) {
        if (id == activeId.value) return
        if (_ui.value.isGenerating) {
            _ui.update { it.copy(error = "Stop the current response before switching chats.") }
            return
        }
        val conversation = _ui.value.conversations.firstOrNull { it.id == id }
        _ui.update {
            it.copy(
                active = conversation,
                messages = emptyList(),
                streaming = null,
                toolActivities = emptyList(),
                error = null,
            )
        }
        activeId.value = id
    }

    fun newChat() {
        if (_ui.value.isGenerating) {
            _ui.update { it.copy(error = "Stop the current response before starting a new chat.") }
            return
        }
        val settings = _ui.value.settings
        viewModelScope.launch {
            val conversation = repository.createConversation(
                modelId = settings.modelId,
                systemPrompt = "",
                webToolsEnabled = settings.webToolsEnabled,
            )
            initialAutoSelect = true
            selectConversation(conversation.id)
        }
    }

    fun send() {
        if (_ui.value.isGenerating || _ui.value.isAttaching) return
        val state = _ui.value
        val text = state.input.trim()
        if (text.isEmpty() && state.pending.isEmpty()) return
        if (state.settings.apiKey.isBlank()) {
            _ui.update { it.copy(error = "Add your API key in Settings first.") }
            return
        }
        val endpoint = EndpointResolver.resolve(state.settings.endpointUrl, state.settings.modelId)
            .getOrElse { error ->
                _ui.update { it.copy(error = error.message ?: "Invalid endpoint") }
                return
            }

        val pending = state.pending
        _ui.update {
            it.copy(
                input = "",
                pending = emptyList(),
                error = null,
                toolActivities = emptyList(),
            )
        }

        viewModelScope.launch {
            val conversation = state.active ?: repository.createConversation(
                modelId = state.settings.modelId,
                systemPrompt = "",
                webToolsEnabled = state.settings.webToolsEnabled,
            ).also {
                initialAutoSelect = true
                activeId.value = it.id
            }

            val blocks = buildList<ContentBlock> {
                if (text.isNotBlank()) add(ContentBlock.Text(text))
                addAll(pending)
            }
            val userMessage = ChatMessage(
                id = repository.newMessageId(),
                conversationId = conversation.id,
                role = Role.USER,
                blocks = blocks,
                status = MessageStatus.COMPLETE,
                createdAt = repository.now(),
                modelId = conversation.modelId,
            )
            repository.saveMessage(userMessage)
            repository.autoTitle(
                conversation.id,
                text.ifBlank { pending.firstOrNull()?.attachmentName().orEmpty() },
            )
            val history = repository.messages(conversation.id)
            startGeneration(conversation, history, endpoint)
        }
    }

    fun stop() {
        generationJob?.cancel()
    }

    fun retryLast() {
        if (_ui.value.isGenerating) return
        val conversation = _ui.value.active ?: return
        val settings = _ui.value.settings
        if (settings.apiKey.isBlank()) {
            _ui.update { it.copy(error = "Add your API key in Settings first.") }
            return
        }
        val endpoint = EndpointResolver.resolve(settings.endpointUrl, settings.modelId)
            .getOrElse {
                _ui.update { state -> state.copy(error = it.message ?: "Invalid endpoint") }
                return
            }
        viewModelScope.launch {
            val messages = repository.messages(conversation.id)
            val lastUser = messages.lastOrNull { it.role == Role.USER } ?: return@launch
            repository.deleteMessagesAfter(conversation.id, lastUser.id)
            val history = repository.messages(conversation.id)
            startGeneration(conversation, history, endpoint)
        }
    }

    private fun startGeneration(
        conversation: Conversation,
        initialHistory: List<ChatMessage>,
        endpoint: ResolvedEndpoint,
    ) {
        val settings = _ui.value.settings
        generationJob = viewModelScope.launch {
            var history = initialHistory
            var rounds = 0
            val maxRounds = if (conversation.webToolsEnabled) {
                settings.maxToolRounds.coerceIn(0, 8)
            } else {
                0
            }
            val modelSupportsTools = _ui.value.models
                .firstOrNull { it.id == conversation.modelId }
                ?.supportsTools ?: true
            _ui.update {
                it.copy(
                    isGenerating = true,
                    streaming = null,
                    toolActivities = emptyList(),
                    error = null,
                )
            }
            try {
                while (true) {
                    val toolsEnabled = conversation.webToolsEnabled &&
                        modelSupportsTools && rounds < maxRounds
                    var round = runRound(conversation, history, endpoint, settings, toolsEnabled)
                    round = retryRoundIfNeeded(
                        conversation, history, endpoint, settings, toolsEnabled, round,
                    )
                    if (round.error != null || !round.hasOutput) {
                        val errorMessage = round.error ?: emptyResponseMessage(round)
                        val errorReply = ChatMessage(
                            id = round.messageId,
                            conversationId = conversation.id,
                            role = Role.ASSISTANT,
                            blocks = if (round.text.isBlank()) emptyList() else listOf(
                                ContentBlock.Text(round.text),
                            ),
                            status = MessageStatus.ERROR,
                            createdAt = repository.now(),
                            modelId = conversation.modelId,
                            usage = round.usage,
                            reasoning = round.reasoning,
                            error = errorMessage,
                        )
                        repository.saveMessage(errorReply)
                        _ui.update { it.copy(error = errorMessage) }
                        break
                    }

                    val assistantBlocks = buildList<ContentBlock> {
                        if (round.text.isNotBlank()) add(ContentBlock.Text(round.text))
                        addAll(round.toolCalls)
                    }
                    val assistant = ChatMessage(
                        id = round.messageId,
                        conversationId = conversation.id,
                        role = Role.ASSISTANT,
                        blocks = assistantBlocks,
                        status = MessageStatus.COMPLETE,
                        createdAt = repository.now(),
                        modelId = conversation.modelId,
                        usage = round.usage,
                        reasoning = round.reasoning,
                    )
                    repository.saveMessage(assistant)
                    history = history + assistant
                    _ui.update { it.copy(streaming = null) }

                    if (round.toolCalls.isEmpty() || !toolsEnabled) break

                    round.toolCalls.forEach { call ->
                        val activityId = call.id
                        _ui.update { state ->
                            state.copy(
                                toolActivities = state.toolActivities + ToolActivity(
                                    id = activityId,
                                    name = call.name,
                                    detail = call.activityLabel(),
                                    status = ToolActivityStatus.RUNNING,
                                ),
                            )
                        }
                        val result = toolExecutor.execute(
                            call,
                            settings.searchBackend,
                            settings.searchApiKey,
                        ).copy(toolCallId = call.id)
                        val toolMessage = ChatMessage(
                            id = repository.newMessageId(),
                            conversationId = conversation.id,
                            role = Role.TOOL,
                            blocks = listOf(result),
                            status = MessageStatus.COMPLETE,
                            createdAt = repository.now(),
                            modelId = conversation.modelId,
                        )
                        repository.saveMessage(toolMessage)
                        history = history + toolMessage
                        // The saved tool message renders as a result card in its timeline position,
                        // so drop the transient "running" step instead of leaving a duplicate.
                        _ui.update { state ->
                            state.copy(
                                toolActivities = state.toolActivities.filterNot {
                                    it.id == activityId
                                },
                            )
                        }
                    }

                    rounds++
                    if (rounds >= maxRounds) {
                        var finalRound = runRound(
                            conversation,
                            history,
                            endpoint,
                            settings,
                            toolsEnabled = false,
                        )
                        finalRound = retryRoundIfNeeded(
                            conversation,
                            history,
                            endpoint,
                            settings,
                            toolsEnabled = false,
                            round = finalRound,
                        )
                        val finalError = finalRound.error
                            ?: emptyResponseMessage(finalRound).takeIf { !finalRound.hasOutput }
                        repository.saveMessage(
                            ChatMessage(
                                id = finalRound.messageId,
                                conversationId = conversation.id,
                                role = Role.ASSISTANT,
                                blocks = if (finalRound.text.isBlank()) emptyList() else listOf(
                                    ContentBlock.Text(finalRound.text),
                                ),
                                status = if (finalError == null) {
                                    MessageStatus.COMPLETE
                                } else {
                                    MessageStatus.ERROR
                                },
                                createdAt = repository.now(),
                                modelId = conversation.modelId,
                                usage = finalRound.usage,
                                reasoning = finalRound.reasoning,
                                error = finalError,
                            ),
                        )
                        if (finalError != null) {
                            _ui.update { it.copy(error = finalError) }
                        }
                        break
                    }
                }
            } catch (_: CancellationException) {
                val streaming = _ui.value.streaming
                if (streaming != null &&
                    (streaming.text.isNotBlank() || streaming.reasoning.isNotBlank())
                ) {
                    val cancelledText = DsmlToolCalls.extract(streaming.text, emptySet()).text
                    withContext(NonCancellable) {
                        runCatching {
                            repository.saveMessage(
                                ChatMessage(
                                    id = streaming.messageId,
                                    conversationId = conversation.id,
                                    role = Role.ASSISTANT,
                                    blocks = if (cancelledText.isBlank()) emptyList() else listOf(
                                        ContentBlock.Text(cancelledText),
                                    ),
                                    status = MessageStatus.CANCELLED,
                                    createdAt = repository.now(),
                                    modelId = conversation.modelId,
                                    usage = streaming.usage,
                                    reasoning = streaming.reasoning,
                                ),
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _ui.update { it.copy(error = t.message ?: "Generation failed") }
            } finally {
                _ui.update {
                    it.copy(
                        isGenerating = false,
                        streaming = null,
                        toolActivities = emptyList(),
                    )
                }
                generationJob = null
            }
        }
    }

    private suspend fun runRound(
        conversation: Conversation,
        history: List<ChatMessage>,
        endpoint: ResolvedEndpoint,
        settings: AppSettings,
        toolsEnabled: Boolean,
    ): RoundResult {
        val messageId = repository.newMessageId()
        val text = StringBuilder()
        val reasoning = StringBuilder()
        var usage: Usage? = null
        var error: String? = null
        val toolCalls = ToolCallAccumulator()
        var lastEmit = 0L

        fun flush() {
            val now = System.nanoTime() / 1_000_000
            if (now - lastEmit < 40) return
            lastEmit = now
            val snapshotText = text.toString()
            val snapshotReasoning = reasoning.toString()
            _ui.update { state ->
                state.copy(
                    streaming = StreamingState(
                        messageId = messageId,
                        text = snapshotText,
                        reasoning = snapshotReasoning,
                        usage = usage,
                    ),
                )
            }
        }

        _ui.update {
            it.copy(
                streaming = StreamingState(messageId),
                isGenerating = true,
            )
        }

        val request = buildRequest(conversation, history, settings, toolsEnabled)
        val result = try {
            inference.stream(
                endpointUrl = endpoint.chatUrl,
                family = endpoint.family,
                model = conversation.modelId,
                apiKey = settings.apiKey,
                sessionId = conversation.sessionId,
                request = request,
            ) { event ->
                when (event) {
                    is ProviderEvent.TextDelta -> {
                        text.append(event.text)
                        flush()
                    }
                    is ProviderEvent.ReasoningDelta -> {
                        reasoning.append(event.text)
                        flush()
                    }
                    is ProviderEvent.ToolCallStart -> {
                        toolCalls.start(event.index, event.id, event.name)
                        flush()
                    }
                    is ProviderEvent.ToolCallArgumentsDelta -> {
                        toolCalls.arguments(event.index, event.delta)
                    }
                    is ProviderEvent.UsageEvent -> {
                        usage = event.usage
                        flush()
                    }
                    is ProviderEvent.RemoteError -> {
                        error = event.message
                        flush()
                    }
                    is ProviderEvent.Finished -> Unit
                }
            }
        } finally {
            val snapshotText = text.toString()
            val snapshotReasoning = reasoning.toString()
            _ui.update { state ->
                state.copy(
                    streaming = state.streaming?.copy(
                        text = snapshotText,
                        reasoning = snapshotReasoning,
                        usage = usage,
                    ),
                )
            }
        }

        val failure = result.exceptionOrNull()
        if (failure != null) {
            error = failure.message ?: "Generation failed"
        }
        val stream = result.getOrNull()
        // Some OpenAI-compatible hosts leak DeepSeek's DSML tool-call markup into the text
        // content instead of the structured tool_calls field. Recover it so the call still runs.
        val inline = DsmlToolCalls.extract(
            text.toString(),
            allowedNames = if (toolsEnabled) request.tools.map { it.name }.toSet() else emptySet(),
        )
        val calls = if (toolsEnabled) {
            toolCalls.build { index -> "call_${messageId}_$index" } +
                inline.calls.mapIndexed { index, call ->
                    call.copy(id = "call_${messageId}_dsml_$index")
                }
        } else {
            // Never persist a tool call that will not be executed: an assistant tool_call
            // without a matching tool result makes the next request invalid.
            emptyList()
        }
        val hasOutput = inline.text.isNotBlank() || calls.isNotEmpty()
        return RoundResult(
            messageId = messageId,
            text = inline.text,
            reasoning = reasoning.toString(),
            usage = usage,
            toolCalls = calls,
            error = error,
            stopReason = stream?.stopReason,
            completed = stream?.completed ?: false,
            // A response with no answer text and no tool call is almost always a premature stop.
            // Retry it, but only when the failure itself looks transient.
            retryable = !hasOutput && when {
                failure != null -> isRetryableFailure(failure)
                error != null -> stream?.completed == false
                else -> true
            },
        )
    }

    /**
     * Providers occasionally finish a turn after emitting only reasoning (for example
     * "Let me search for that…") or with a blank response. Retrying a fresh round turns those
     * dead ends into a normal answer instead of leaving the user with a stopped chat.
     */
    private suspend fun retryRoundIfNeeded(
        conversation: Conversation,
        history: List<ChatMessage>,
        endpoint: ResolvedEndpoint,
        settings: AppSettings,
        toolsEnabled: Boolean,
        round: RoundResult,
    ): RoundResult {
        var current = round
        var attempt = 0
        while (current.retryable && attempt < MAX_EMPTY_RESPONSE_RETRIES) {
            attempt++
            // Keep the "Thinking…" indicator alive between attempts instead of briefly
            // dropping back to the empty state while the fresh request starts.
            _ui.update { state ->
                state.copy(
                    streaming = StreamingState(
                        state.streaming?.messageId ?: repository.newMessageId(),
                    ),
                )
            }
            delay(EMPTY_RESPONSE_RETRY_DELAY_MS * attempt)
            current = runRound(conversation, history, endpoint, settings, toolsEnabled)
        }
        return current
    }

    private fun isRetryableFailure(failure: Throwable): Boolean {
        val api = failure as? ApiException ?: return true
        return api.statusCode == 0 ||
            api.statusCode == 408 ||
            api.statusCode == 425 ||
            api.statusCode == 429 ||
            api.statusCode in 500..599
    }

    private fun emptyResponseMessage(round: RoundResult): String {
        val reason = round.stopReason?.lowercase().orEmpty()
        return when {
            reason.contains("length") ||
                reason.contains("max_token") ||
                reason.contains("max_output") ->
                "The model used its entire output budget before writing an answer. Try again, " +
                    "or increase the max tokens setting."
            reason.contains("content_filter") ||
                reason.contains("safety") ||
                reason.contains("block") ->
                "The model stopped without returning an answer because of its content policy."
            round.reasoning.isNotBlank() ->
                "The model finished reasoning but did not return an answer. Try again."
            !round.completed ->
                "The provider closed the connection before sending a complete response. Try again."
            else -> "The model returned an empty response. Try again."
        }
    }

    private suspend fun buildRequest(
        conversation: Conversation,
        history: List<ChatMessage>,
        settings: AppSettings,
        toolsEnabled: Boolean,
    ): ChatRequest = withContext(Dispatchers.Default) {
        var recent = history.takeLast(80)
        while (recent.isNotEmpty() && recent.first().role != Role.USER) {
            recent = recent.drop(1)
        }
        var messages = recent.mapNotNull { message ->
            val blocks = message.blocks.mapNotNull { block ->
                when (block) {
                    is ContentBlock.Text -> block.text.takeIf { it.isNotBlank() }?.let {
                        RequestBlock.Text(it)
                    }
                    is ContentBlock.Image -> {
                        val base64 = attachments.readBase64(block.path)
                        if (base64 == null) {
                            RequestBlock.Text("[Image attachment unavailable: ${block.name ?: "image"}]")
                        } else {
                            RequestBlock.Image(base64, block.mimeType)
                        }
                    }
                    is ContentBlock.Document -> RequestBlock.Document(
                        block.name,
                        block.mimeType,
                        block.text,
                    )
                    is ContentBlock.ToolCall -> RequestBlock.ToolCall(
                        block.id,
                        block.name,
                        block.arguments,
                    )
                    is ContentBlock.ToolResult -> RequestBlock.ToolResult(
                        block.toolCallId,
                        block.name,
                        block.content,
                        block.isError,
                    )
                }
            }
            if (blocks.isEmpty()) null else RequestMessage(message.role, blocks)
        }
        val systemPrompt = settings.systemPrompt
        val contextWindow = _ui.value.models
            .firstOrNull { it.id == conversation.modelId }
            ?.contextWindow ?: 200_000
        messages = ConversationTrimmer.trim(messages, systemPrompt, contextWindow)
        ChatRequest(
            model = conversation.modelId,
            messages = messages,
            systemPrompt = systemPrompt,
            temperature = settings.temperature,
            maxTokens = settings.maxTokens,
            tools = if (toolsEnabled) LocalTools.all() else emptyList(),
        )
    }

    fun attachImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _ui.update { it.copy(isAttaching = true) }
            val added = mutableListOf<ContentBlock>()
            var failure: String? = null
            for (uri in uris) {
                if (_ui.value.pending.size + added.size >= MAX_ATTACHMENTS) {
                    failure = "You can attach up to $MAX_ATTACHMENTS items."
                    break
                }
                attachments.importImage(uri)
                    .onSuccess { added += it }
                    .onFailure { failure = it.message ?: "Could not add that image." }
            }
            _ui.update {
                it.copy(
                    pending = (it.pending + added).take(MAX_ATTACHMENTS),
                    isAttaching = false,
                    error = failure,
                )
            }
        }
    }

    fun attachBitmap(bitmap: Bitmap, name: String = "photo.jpg") {
        viewModelScope.launch {
            _ui.update { it.copy(isAttaching = true) }
            val result = attachments.importBitmap(bitmap, name)
            val image = result.getOrNull()
            if (image != null && _ui.value.pending.size >= MAX_ATTACHMENTS) {
                attachments.deleteAttachment(image)
            }
            _ui.update { state ->
                when {
                    image != null && state.pending.size < MAX_ATTACHMENTS ->
                        state.copy(
                            pending = state.pending + image,
                            isAttaching = false,
                            error = null,
                        )
                    image != null ->
                        state.copy(
                            isAttaching = false,
                            error = "You can attach up to $MAX_ATTACHMENTS items.",
                        )
                    else ->
                        state.copy(
                            isAttaching = false,
                            error = result.exceptionOrNull()?.message
                                ?: "Could not add that image.",
                        )
                }
            }
        }
    }

    fun reportAttachmentError(message: String) {
        _ui.update { it.copy(error = message) }
    }

    fun attachDocuments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _ui.update { it.copy(isAttaching = true) }
            val added = mutableListOf<ContentBlock>()
            var failure: String? = null
            for (uri in uris) {
                if (_ui.value.pending.count { it is ContentBlock.Document } +
                    added.count { it is ContentBlock.Document } >= MAX_DOCUMENTS
                ) {
                    failure = "You can attach up to $MAX_DOCUMENTS documents."
                    break
                }
                if (_ui.value.pending.size + added.size >= MAX_ATTACHMENTS) break
                attachments.importDocument(uri)
                    .onSuccess { added += it }
                    .onFailure { failure = it.message ?: "Could not read that document." }
            }
            _ui.update {
                it.copy(
                    pending = (it.pending + added).take(MAX_ATTACHMENTS),
                    isAttaching = false,
                    error = failure,
                )
            }
        }
    }

    fun removePending(block: ContentBlock) {
        _ui.update { state -> state.copy(pending = state.pending.filterNot { it == block }) }
        attachments.deleteAttachment(block)
    }

    fun newCameraFile(): File = attachments.newCameraFile()

    fun latestCameraFile(): File? = attachments.latestCameraFile()

    suspend fun awaitCameraCapture(file: File): Boolean = attachments.awaitCameraCapture(file)

    fun attachCapturedImage(file: File) {
        viewModelScope.launch {
            _ui.update { it.copy(isAttaching = true) }
            val result = attachments.importImage(Uri.fromFile(file))
            val image = result.getOrNull()
            if (image != null && _ui.value.pending.size >= MAX_ATTACHMENTS) {
                attachments.deleteAttachment(image)
            }
            _ui.update { state ->
                when {
                    image != null && state.pending.size < MAX_ATTACHMENTS ->
                        state.copy(
                            pending = state.pending + image,
                            isAttaching = false,
                            error = null,
                        )
                    image != null ->
                        state.copy(
                            isAttaching = false,
                            error = "You can attach up to $MAX_ATTACHMENTS items.",
                        )
                    else ->
                        state.copy(
                            isAttaching = false,
                            error = result.exceptionOrNull()?.message
                                ?: "Could not add that image.",
                        )
                }
            }
            // importImage copied the pixels into the attachments directory.
            runCatching { file.delete() }
        }
    }

    fun selectModel(model: ModelInfo) {
        viewModelScope.launch {
            settingsStore.setModel(model.id, model.name)
            _ui.value.active?.let { repository.setModel(it.id, model.id) }
        }
    }

    fun setCustomModel(id: String) {
        if (id.isBlank()) return
        viewModelScope.launch {
            settingsStore.setModel(id, "")
            _ui.value.active?.let { repository.setModel(it.id, id) }
        }
    }

    fun toggleWebTools() {
        val conversation = _ui.value.active
        val settings = _ui.value.settings
        viewModelScope.launch {
            if (conversation == null) {
                settingsStore.setWebTools(!settings.webToolsEnabled)
            } else {
                repository.setWebTools(conversation.id, !conversation.webToolsEnabled)
            }
        }
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { settingsStore.setTheme(mode) }
    }

    fun saveSettings(settings: AppSettings) {
        viewModelScope.launch {
            settingsStore.updateAll(settings)
            val conversation = _ui.value.active
            if (conversation != null && conversation.modelId != settings.modelId) {
                repository.setModel(conversation.id, settings.modelId)
            }
            refreshModelsFor(settings, silent = false, force = true)
        }
    }

    fun refreshModels(silent: Boolean = false, force: Boolean = false) {
        refreshModelsFor(_ui.value.settings, silent, force)
    }

    private fun refreshModelsFor(settings: AppSettings, silent: Boolean, force: Boolean) {
        if (_ui.value.modelsLoading) return
        val endpoint = EndpointResolver.resolve(settings.endpointUrl, settings.modelId)
            .getOrElse {
                if (!silent) _ui.update { state -> state.copy(modelError = it.message) }
                return
            }
        if (!force && _ui.value.modelsEndpoint == settings.endpointUrl &&
            _ui.value.models.isNotEmpty() && !silent
        ) {
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(modelsLoading = true, modelError = null) }
            val session = "ses_" + UUID.randomUUID().toString().replace("-", "")
            modelService.fetchModels(
                modelsUrl = endpoint.modelsUrl,
                apiKey = settings.apiKey,
                sessionId = session,
                family = endpoint.family,
            ).onSuccess { fetched ->
                val builtIn = if (EndpointResolver.isOpenCodeGo(settings.endpointUrl)) {
                    ModelCatalog.openCodeGo
                } else {
                    emptyList()
                }
                val builtInById = builtIn.associateBy { it.id }
                val merged = fetched.map { remote ->
                    val base = builtInById[remote.id]
                    if (base == null) {
                        remote
                    } else {
                        base.copy(
                            name = if (remote.name == remote.id) base.name else remote.name,
                            contextWindow = remote.contextWindow ?: base.contextWindow,
                            supportsVision = remote.supportsVision || base.supportsVision,
                            supportsTools = remote.supportsTools,
                        )
                    }
                }
                _ui.update {
                    it.copy(
                        models = merged.sortedBy { model -> model.name.lowercase() },
                        modelsLoading = false,
                        modelsEndpoint = settings.endpointUrl,
                        modelError = null,
                    )
                }
            }.onFailure { error ->
                _ui.update {
                    it.copy(
                        modelsLoading = false,
                        modelError = if (silent) null else error.message ?: "Could not load models.",
                    )
                }
            }
        }
    }

    fun renameConversation(id: String, title: String) {
        viewModelScope.launch { repository.rename(id, title) }
    }

    fun togglePin(id: String) {
        val conversation = _ui.value.conversations.firstOrNull { it.id == id } ?: return
        viewModelScope.launch { repository.setPinned(id, !conversation.pinned) }
    }

    fun deleteConversation(id: String) {
        if (activeId.value == id) stop()
        viewModelScope.launch {
            if (activeId.value == id) generationJob?.cancelAndJoin()
            val toDelete = repository.messages(id)
            repository.deleteConversation(id)
            toDelete.forEach { message ->
                message.blocks.forEach { attachments.deleteAttachment(it) }
            }
            if (activeId.value == id) {
                val next = repository.conversations().firstOrNull()
                activeId.value = next?.id
                _ui.update {
                    it.copy(
                        active = next,
                        messages = emptyList(),
                        streaming = null,
                        toolActivities = emptyList(),
                    )
                }
            }
        }
    }

    fun clearAll() {
        stop()
        viewModelScope.launch {
            generationJob?.cancelAndJoin()
            val toDelete = repository.conversations().flatMap { conversation ->
                repository.messages(conversation.id)
            }
            repository.clearAll()
            toDelete.forEach { message ->
                message.blocks.forEach { attachments.deleteAttachment(it) }
            }
            activeId.value = null
            _ui.update {
                it.copy(
                    conversations = emptyList(),
                    active = null,
                    messages = emptyList(),
                    streaming = null,
                    toolActivities = emptyList(),
                )
            }
        }
    }

    suspend fun exportJson(): String = withContext(Dispatchers.IO) { repository.exportJson() }

    fun removeAttachmentFile(block: ContentBlock) {
        attachments.deleteAttachment(block)
    }

    private fun ContentBlock.attachmentName(): String = when (this) {
        is ContentBlock.Image -> name ?: "image"
        is ContentBlock.Document -> name
        else -> ""
    }

    private fun ContentBlock.ToolCall.activityLabel(): String = when (name) {
        LocalTools.WEB_SEARCH -> "Searching the web"
        LocalTools.WEB_FETCH -> "Reading a page"
        else -> "Running $name"
    }

    private data class RoundResult(
        val messageId: String,
        val text: String,
        val reasoning: String,
        val usage: Usage?,
        val toolCalls: List<ContentBlock.ToolCall>,
        val error: String?,
        val stopReason: String? = null,
        val completed: Boolean = true,
        val retryable: Boolean = false,
    ) {
        val hasOutput: Boolean get() = text.isNotBlank() || toolCalls.isNotEmpty()
    }

    override fun onCleared() {
        generationJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val MAX_ATTACHMENTS = 10
        private const val MAX_DOCUMENTS = 4

        /** Extra attempts for reasoning-only or blank completions before reporting an error. */
        private const val MAX_EMPTY_RESPONSE_RETRIES = 2
        private const val EMPTY_RESPONSE_RETRY_DELAY_MS = 700L

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChatViewModel(container) as T
            }
    }
}
