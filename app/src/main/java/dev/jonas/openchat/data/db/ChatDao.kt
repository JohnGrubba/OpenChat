package dev.jonas.openchat.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations ORDER BY pinned DESC, updatedAt DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY pinned DESC, updatedAt DESC")
    suspend fun conversations(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun conversation(id: String): ConversationEntity?

    @Upsert
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Query("UPDATE conversations SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun renameConversation(id: String, title: String, now: Long)

    @Query("UPDATE conversations SET pinned = :pinned, updatedAt = :now WHERE id = :id")
    suspend fun pinConversation(id: String, pinned: Boolean, now: Long)

    @Query("UPDATE conversations SET modelId = :modelId, updatedAt = :now WHERE id = :id")
    suspend fun updateModel(id: String, modelId: String, now: Long)

    @Query("UPDATE conversations SET webToolsEnabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun updateWebTools(id: String, enabled: Boolean, now: Long)

    @Query("UPDATE conversations SET updatedAt = :now WHERE id = :id")
    suspend fun touchConversation(id: String, now: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)

    @Query("DELETE FROM conversations")
    suspend fun deleteAllConversations()

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY rowid ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY rowid ASC")
    suspend fun messages(conversationId: String): List<MessageEntity>

    @Upsert
    suspend fun upsertMessage(message: MessageEntity)

    @Upsert
    suspend fun upsertMessages(messages: List<MessageEntity>)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY rowid DESC LIMIT 1")
    suspend fun lastMessage(conversationId: String): MessageEntity?

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: String)

    @Query(
        "DELETE FROM messages WHERE conversationId = :conversationId AND rowid > " +
            "(SELECT rowid FROM messages WHERE id = :messageId)",
    )
    suspend fun deleteMessagesAfter(conversationId: String, messageId: String)

    @Query("UPDATE messages SET status = 'INTERRUPTED' WHERE status = 'STREAMING'")
    suspend fun markInterrupted()
}
