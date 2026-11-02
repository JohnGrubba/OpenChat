package dev.jonas.openchat.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val modelId: String,
    val systemPrompt: String,
    val sessionId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean,
    val webToolsEnabled: Boolean,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val blocksJson: String,
    val status: String,
    val createdAt: Long,
    val modelId: String?,
    val usageJson: String?,
    val reasoning: String,
    val error: String?,
)
