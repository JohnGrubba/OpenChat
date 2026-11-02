package dev.jonas.openchat.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.jonas.openchat.core.model.ChatMessage
import dev.jonas.openchat.core.model.ContentBlock
import dev.jonas.openchat.core.model.MessageStatus
import dev.jonas.openchat.core.model.Role
import dev.jonas.openchat.core.model.Usage
import dev.jonas.openchat.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: ChatRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ChatRepository(database.chatDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `conversations and rich messages round trip`() = runBlocking {
        val conversation = repository.createConversation(
            modelId = "deepseek-v4.1-flash",
            systemPrompt = "be nice",
            webToolsEnabled = true,
        )
        val message = ChatMessage(
            id = repository.newMessageId(),
            conversationId = conversation.id,
            role = Role.USER,
            blocks = listOf(
                ContentBlock.Text("hello"),
                ContentBlock.Image("/tmp/a.jpg", "image/jpeg", "a.jpg"),
                ContentBlock.Document("notes.txt", "text/plain", "body", truncated = true),
            ),
            status = MessageStatus.COMPLETE,
            createdAt = repository.now(),
            modelId = conversation.modelId,
            usage = Usage(inputTokens = 10, outputTokens = 2, costUsd = 0.001),
        )
        repository.saveMessage(message)

        val loaded = repository.messages(conversation.id).single()
        assertEquals(message.id, loaded.id)
        assertEquals(3, loaded.blocks.size)
        assertEquals("hello", loaded.text)
        assertEquals("a.jpg", loaded.images.single().name)
        assertTrue(loaded.documents.single().truncated)
        assertEquals(10, loaded.usage!!.inputTokens)
        assertEquals(0.001, loaded.usage!!.costUsd, 0.00001)
    }

    @Test
    fun `auto title rename pin and model updates persist`() = runBlocking {
        val conversation = repository.createConversation("model-a", "", false)
        repository.autoTitle(conversation.id, "  A very   long conversation title that should be shortened to fit  ")
        val titled = repository.conversation(conversation.id)!!
        assertTrue(titled.title.startsWith("A very long conversation title"))
        assertTrue(titled.title.endsWith("…"))

        repository.rename(conversation.id, "Renamed")
        repository.setPinned(conversation.id, true)
        repository.setModel(conversation.id, "model-b")
        repository.setWebTools(conversation.id, true)

        val updated = repository.conversation(conversation.id)!!
        assertEquals("Renamed", updated.title)
        assertTrue(updated.pinned)
        assertEquals("model-b", updated.modelId)
        assertTrue(updated.webToolsEnabled)
    }

    @Test
    fun `deleteMessagesAfter keeps the user turn only`() = runBlocking {
        val conversation = repository.createConversation("m", "", false)
        val user = ChatMessage(
            id = repository.newMessageId(),
            conversationId = conversation.id,
            role = Role.USER,
            blocks = listOf(ContentBlock.Text("question")),
            createdAt = repository.now(),
        )
        val assistant = ChatMessage(
            id = repository.newMessageId(),
            conversationId = conversation.id,
            role = Role.ASSISTANT,
            blocks = listOf(ContentBlock.Text("answer")),
            createdAt = repository.now(),
        )
        val tool = ChatMessage(
            id = repository.newMessageId(),
            conversationId = conversation.id,
            role = Role.TOOL,
            blocks = listOf(ContentBlock.ToolResult("call", "web_search", "result")),
            createdAt = repository.now(),
        )
        repository.saveMessages(listOf(user, assistant, tool))

        repository.deleteMessagesAfter(conversation.id, user.id)

        val remaining = repository.messages(conversation.id)
        assertEquals(1, remaining.size)
        assertEquals(user.id, remaining.single().id)
    }

    @Test
    fun `streaming messages are marked interrupted`() = runBlocking {
        val conversation = repository.createConversation("m", "", false)
        repository.saveMessage(
            ChatMessage(
                id = repository.newMessageId(),
                conversationId = conversation.id,
                role = Role.ASSISTANT,
                blocks = listOf(ContentBlock.Text("partial")),
                status = MessageStatus.STREAMING,
                createdAt = repository.now(),
            ),
        )
        repository.markInterrupted()
        assertEquals(
            MessageStatus.INTERRUPTED,
            repository.messages(conversation.id).single().status,
        )
    }

    @Test
    fun `deleting a conversation cascades to messages and export contains data`() = runBlocking {
        val conversation = repository.createConversation("m", "", false)
        repository.saveMessage(
            ChatMessage(
                id = repository.newMessageId(),
                conversationId = conversation.id,
                role = Role.USER,
                blocks = listOf(ContentBlock.Text("keep me")),
                createdAt = repository.now(),
            ),
        )
        val exported = repository.exportJson()
        assertTrue(exported.contains("keep me"))
        assertTrue(exported.contains(conversation.id))

        repository.deleteConversation(conversation.id)
        assertTrue(repository.conversations().isEmpty())
        assertTrue(repository.messages(conversation.id).isEmpty())
    }
}
