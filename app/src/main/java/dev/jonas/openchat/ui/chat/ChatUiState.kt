package dev.jonas.openchat.ui.chat

import dev.jonas.openchat.core.model.AppSettings
import dev.jonas.openchat.core.model.ChatMessage
import dev.jonas.openchat.core.model.ContentBlock
import dev.jonas.openchat.core.model.Conversation
import dev.jonas.openchat.core.model.ModelInfo
import dev.jonas.openchat.core.model.Usage

enum class ToolActivityStatus { RUNNING, DONE, ERROR }

data class ToolActivity(
    val id: String,
    val name: String,
    val detail: String,
    val status: ToolActivityStatus,
)

data class StreamingState(
    val messageId: String,
    val text: String = "",
    val reasoning: String = "",
    val usage: Usage? = null,
)

data class ChatUiState(
    val settings: AppSettings = AppSettings(),
    val settingsLoaded: Boolean = false,
    val conversations: List<Conversation> = emptyList(),
    val active: Conversation? = null,
    val messages: List<ChatMessage> = emptyList(),
    val streaming: StreamingState? = null,
    val isGenerating: Boolean = false,
    val input: String = "",
    val pending: List<ContentBlock> = emptyList(),
    val isAttaching: Boolean = false,
    val models: List<ModelInfo> = emptyList(),
    val modelsLoading: Boolean = false,
    val modelsEndpoint: String? = null,
    val modelError: String? = null,
    val toolActivities: List<ToolActivity> = emptyList(),
    val error: String? = null,
)
