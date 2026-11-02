package dev.jonas.openchat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import dev.jonas.openchat.core.model.AppSettings
import dev.jonas.openchat.core.model.ThemeMode
import dev.jonas.openchat.ui.chat.ChatUiState
import dev.jonas.openchat.ui.chat.Composer
import dev.jonas.openchat.ui.theme.OpenChatTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposerRenderTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun showsInputSendAndWebToggle() {
        compose.setContent {
            OpenChatTheme(ThemeMode.LIGHT) {
                Composer(
                    state = ChatUiState(
                        settings = AppSettings(sendOnEnter = true),
                        input = "hello",
                    ),
                    onInput = {},
                    onSend = {},
                    onStop = {},
                    onAttach = {},
                    onToggleWebTools = {},
                    onRemove = {},
                    onOpenImage = {},
                )
            }
        }
        compose.onNodeWithText("hello").assertExists()
        compose.onNodeWithContentDescription("Send").assertExists()
        compose.onNodeWithContentDescription("Enable web tools").assertExists()
        compose.onNodeWithText("AI can make mistakes. Check important information.").assertExists()
    }
}
