package dev.jonas.openchat

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import dev.jonas.openchat.core.model.AppSettings
import dev.jonas.openchat.core.model.ThemeMode
import dev.jonas.openchat.ui.chat.ChatUiState
import dev.jonas.openchat.ui.chat.ChatViewModel
import dev.jonas.openchat.ui.settings.SettingsScreen
import dev.jonas.openchat.ui.theme.OpenChatTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = OpenChatApplication::class)
class SettingsRenderTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rendersConnectionAndGenerationSections() {
        val app = ApplicationProvider.getApplicationContext<OpenChatApplication>()
        val viewModel = ChatViewModel(app.container)
        compose.setContent {
            OpenChatTheme(ThemeMode.LIGHT) {
                SettingsScreen(
                    state = ChatUiState(
                        settingsLoaded = true,
                        settings = AppSettings(),
                    ),
                    viewModel = viewModel,
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("Connection").assertExists()
        compose.onNodeWithText("Endpoint URL").assertExists()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasText("Generation"))
        compose.onNodeWithText("Generation").assertExists()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasText("Web tools"))
        compose.onNodeWithText("Web tools").assertExists()
    }
}
