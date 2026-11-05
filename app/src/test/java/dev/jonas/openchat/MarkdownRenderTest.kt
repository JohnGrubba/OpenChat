package dev.jonas.openchat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.jonas.openchat.core.model.ThemeMode
import dev.jonas.openchat.ui.markdown.MarkdownView
import dev.jonas.openchat.ui.theme.OpenChatTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarkdownRenderTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rendersMarkdownBlocks() {
        compose.setContent {
            OpenChatTheme(ThemeMode.LIGHT) {
                MarkdownView(
                    text = "## Hello world\n\nThis is **bold**.\n\n- first item\n- second item",
                )
            }
        }
        compose.onNodeWithText("Hello world").assertExists()
        compose.onNodeWithText("This is bold.", substring = true).assertExists()
        compose.onNodeWithText("first item").assertExists()
    }

    @Test
    fun rendersMathAndTablesWithoutCrashing() {
        compose.setContent {
            OpenChatTheme(ThemeMode.LIGHT) {
                MarkdownView(
                    text = """
                        The formula is:

                        ${'$'}${'$'}x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}${'$'}${'$'}

                        | Name | Value |
                        |---|---|
                        | Alpha | 1 |
                        | Beta | 2 |
                    """.trimIndent(),
                )
            }
        }
        compose.onNodeWithText("Alpha").assertExists()
        compose.onNodeWithText("Beta").assertExists()
    }
}
