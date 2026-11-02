package dev.jonas.openchat.data

import dev.jonas.openchat.core.model.ChatMessage
import dev.jonas.openchat.core.model.ContentBlock
import dev.jonas.openchat.core.model.Conversation
import dev.jonas.openchat.core.model.MessageStatus
import dev.jonas.openchat.core.model.Role
import dev.jonas.openchat.core.model.Usage
import dev.jonas.openchat.data.db.ChatDao
import dev.jonas.openchat.data.db.ConversationEntity
import dev.jonas.openchat.data.db.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

@Serializable
data class ExportBundle(
    val version: Int = 1,
    val exportedAt: Long,
    val conversations: List<Conversation>,
    val messages: List<ChatMessage>,
)

class ChatRepository(
    private val dao: ChatDao,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    },
) {
    private val lastTimestamp = AtomicLong(0)
    private val blockListSerializer = ListSerializer(ContentBlock.serializer())

    fun observeConversations(): Flow<List<Conversation>> =
        dao.observeConversations().map { list -> list.map { it.toDomain() } }

    fun observeMessages(conversationId: String): Flow<List<ChatMessage>> =
        dao.observeMessages(conversationId).map { list -> list.map { it.toDomain() } }

    suspend fun conversations(): List<Conversation> = dao.conversations().map { it.toDomain() }

    suspend fun conversation(id: String): Conversation? = dao.conversation(id)?.toDomain()

    suspend fun messages(conversationId: String): List<ChatMessage> =
        dao.messages(conversationId).map { it.toDomain() }

    suspend fun createConversation(
        modelId: String,
        systemPrompt: String,
        webToolsEnabled: Boolean,
    ): Conversation {
        val now = now()
        val conversation = Conversation(
            id = "conv_" + UUID.randomUUID().toString().replace("-", ""),
            title = "New chat",
            modelId = modelId,
            systemPrompt = systemPrompt,
            sessionId = "ses_" + UUID.randomUUID().toString().replace("-", ""),
            createdAt = now,
            updatedAt = now,
            webToolsEnabled = webToolsEnabled,
        )
        dao.upsertConversation(conversation.toEntity())
        return conversation
    }

    suspend fun saveConversation(conversation: Conversation) {
        dao.upsertConversation(conversation.toEntity())
    }

    suspend fun saveMessage(message: ChatMessage) {
        dao.upsertMessage(message.toEntity())
        dao.touchConversation(message.conversationId, now())
    }

    suspend fun saveMessages(messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        dao.upsertMessages(messages.map { it.toEntity() })
        dao.touchConversation(messages.first().conversationId, now())
    }

    suspend fun lastMessage(conversationId: String): ChatMessage? =
        dao.lastMessage(conversationId)?.toDomain()

    suspend fun rename(conversationId: String, title: String) {
        val clean = title.trim().ifBlank { "New chat" }
        dao.renameConversation(conversationId, clean, now())
    }

    suspend fun autoTitle(conversationId: String, text: String) {
        val conversation = dao.conversation(conversationId) ?: return
        if (conversation.title != "New chat") return
        val title = text.trim()
            .replace(Regex("\\s+"), " ")
            .take(56)
            .ifBlank { return }
        val suffix = if (text.trim().length > 56) "…" else ""
        dao.renameConversation(conversationId, title + suffix, now())
    }

    suspend fun setPinned(conversationId: String, pinned: Boolean) {
        dao.pinConversation(conversationId, pinned, now())
    }

    suspend fun setModel(conversationId: String, modelId: String) {
        dao.updateModel(conversationId, modelId, now())
    }

    suspend fun setWebTools(conversationId: String, enabled: Boolean) {
        dao.updateWebTools(conversationId, enabled, now())
    }

    suspend fun deleteConversation(conversationId: String) =
        dao.deleteConversation(conversationId)

    suspend fun clearAll() = dao.deleteAllConversations()

    suspend fun deleteMessagesAfter(conversationId: String, messageId: String) =
        dao.deleteMessagesAfter(conversationId, messageId)

    suspend fun markInterrupted() = dao.markInterrupted()

    fun now(): Long = lastTimestamp.updateAndGet { previous ->
        max(System.currentTimeMillis(), previous + 1)
    }

    fun newMessageId(): String = "msg_" + UUID.randomUUID().toString().replace("-", "")

    suspend fun exportJson(): String {
        val conversations = conversations()
        val messages = conversations.flatMap { messages(it.id) }
        return json.encodeToString(
            ExportBundle.serializer(),
            ExportBundle(
                exportedAt = System.currentTimeMillis(),
                conversations = conversations,
                messages = messages,
            ),
        )
    }

    private fun ConversationEntity.toDomain(): Conversation = Conversation(
        id = id,
        title = title,
        modelId = modelId,
        systemPrompt = systemPrompt,
        sessionId = sessionId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        pinned = pinned,
        webToolsEnabled = webToolsEnabled,
    )

    private fun Conversation.toEntity(): ConversationEntity = ConversationEntity(
        id = id,
        title = title,
        modelId = modelId,
        systemPrompt = systemPrompt,
        sessionId = sessionId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        pinned = pinned,
        webToolsEnabled = webToolsEnabled,
    )

    private fun MessageEntity.toDomain(): ChatMessage = ChatMessage(
        id = id,
        conversationId = conversationId,
        role = runCatching { Role.valueOf(role) }.getOrDefault(Role.USER),
        blocks = runCatching {
            json.decodeFromString(blockListSerializer, blocksJson)
        }.getOrDefault(emptyList()),
        status = runCatching { MessageStatus.valueOf(status) }
            .getOrDefault(MessageStatus.COMPLETE),
        createdAt = createdAt,
        modelId = modelId,
        usage = usageJson?.let {
            runCatching { json.decodeFromString(Usage.serializer(), it) }.getOrNull()
        },
        reasoning = reasoning,
        error = error,
    )

    private fun ChatMessage.toEntity(): MessageEntity = MessageEntity(
        id = id,
        conversationId = conversationId,
        role = role.name,
        blocksJson = json.encodeToString(blockListSerializer, blocks),
        status = status.name,
        createdAt = createdAt,
        modelId = modelId,
        usageJson = usage?.let { json.encodeToString(Usage.serializer(), it) },
        reasoning = reasoning,
        error = error,
    )
}
