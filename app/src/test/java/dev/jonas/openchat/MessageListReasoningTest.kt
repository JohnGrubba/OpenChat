package dev.jonas.openchat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.jonas.openchat.core.model.ChatMessage
import dev.jonas.openchat.core.model.ContentBlock
import dev.jonas.openchat.core.model.Role
import dev.jonas.openchat.core.model.ThemeMode
import dev.jonas.openchat.ui.chat.ChatUiState
import dev.jonas.openchat.ui.chat.MessageList
import dev.jonas.openchat.ui.chat.StreamingState
import dev.jonas.openchat.ui.theme.OpenChatTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageListReasoningTest {
    @get:Rule
    val compose = createComposeRule()

    private val reasoning = "Let me think about this carefully."

    @Test
    fun reasoningOpensAndStaysOpenAcrossUpdates() {
        var state by mutableStateOf(
            ChatUiState(
                messages = listOf(
                    ChatMessage(
                        id = "m1",
                        conversationId = "c1",
                        role = Role.ASSISTANT,
                        blocks = listOf(ContentBlock.Text("The answer.")),
                        reasoning = reasoning,
                    ),
                ),
            ),
        )
        compose.setContent {
            OpenChatTheme(ThemeMode.LIGHT) {
                MessageList(
                    state = state,
                    listState = rememberLazyListState(),
                    onRetry = {},
                    onOpenImage = {},
                    onSuggestion = {},
                )
            }
        }

        compose.onNodeWithText(reasoning).assertDoesNotExist()
        compose.onNodeWithText("Reasoning").performClick()
        compose.onNodeWithText(reasoning).assertExists()

        // Persisting another message (what streaming does between rounds) must not close it.
        compose.runOnIdle {
            state = state.copy(
                messages = state.messages + ChatMessage(
                    id = "m2",
                    conversationId = "c1",
                    role = Role.USER,
                    blocks = listOf(ContentBlock.Text("thanks")),
                ),
            )
        }
        compose.onNodeWithText(reasoning).assertExists()
    }

    @Test
    fun streamingKeepsTheEndOfTheReplyInView() {
        var state by mutableStateOf(
            ChatUiState(
                messages = listOf(
                    ChatMessage(
                        id = "u1",
                        conversationId = "c1",
                        role = Role.USER,
                        blocks = listOf(ContentBlock.Text("Tell me a long story.")),
                    ),
                ),
                streaming = StreamingState(messageId = "m1", text = "Once upon a time."),
            ),
        )
        lateinit var listState: LazyListState
        compose.setContent {
            OpenChatTheme(ThemeMode.LIGHT) {
                Box(modifier = Modifier.height(360.dp)) {
                    val stateHolder = rememberLazyListState()
                    listState = stateHolder
                    MessageList(
                        state = state,
                        listState = stateHolder,
                        onRetry = {},
                        onOpenImage = {},
                        onSuggestion = {},
                    )
                }
            }
        }

        compose.runOnIdle {
            state = state.copy(
                streaming = StreamingState(
                    messageId = "m1",
                    text = (1..400).joinToString("\n\n") { "Paragraph number $it of the story." },
                ),
            )
        }
        compose.waitForIdle()

        // Growth of the reply must not stop the list from following its end.
        assertFalse(listState.canScrollForward)
    }

    @Test
    fun reasoningIsOpenWhileThinkingAndCollapsesWhenTheAnswerStarts() {
        var state by mutableStateOf(
            ChatUiState(streaming = StreamingState(messageId = "m1", reasoning = reasoning)),
        )
        compose.setContent {
            OpenChatTheme(ThemeMode.LIGHT) {
                MessageList(
                    state = state,
                    listState = rememberLazyListState(),
                    onRetry = {},
                    onOpenImage = {},
                    onSuggestion = {},
                )
            }
        }

        compose.onNodeWithText(reasoning).assertExists()

        compose.runOnIdle {
            state = state.copy(
                streaming = StreamingState(
                    messageId = "m1",
                    reasoning = reasoning,
                    text = "Answer.",
                ),
            )
        }
        compose.onNodeWithText(reasoning).assertDoesNotExist()
        compose.onNodeWithText("Answer.").assertExists()
    }
}
