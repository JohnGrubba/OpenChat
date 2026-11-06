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

    @Test
    fun rendersFractionsAsAStackedLayout() {
        compose.setContent {
            OpenChatTheme(ThemeMode.LIGHT) {
                MarkdownView(
                    text = """
                        \[
                        \frac{a+b}{2c}
                        \]
                    """.trimIndent(),
                )
            }
        }
        compose.onNodeWithText("a + b").assertExists()
        compose.onNodeWithText("2c").assertExists()
    }

    @Test
    fun rendersLatexReasoningLikeTheChatTranscript() {
        compose.setContent {
            OpenChatTheme(ThemeMode.DARK) {
                MarkdownView(
                    text = """
                        Let's solve

                        \[
                        a+b+c=abc
                        \]

                        for positive integers \(a,b,c\).

                        ### Step 1: Use symmetry
                        The equation is symmetric in \(a,b,c\), so assume without loss of generality that

                        \[
                        a\le b\le c.
                        \]
                    """.trimIndent(),
                )
            }
        }
        compose.onNodeWithText("Step 1: Use symmetry").assertExists()
        compose.onNodeWithText("a + b + c = abc", substring = true).assertExists()
        compose.onNodeWithText("a ≤ b ≤ c", substring = true).assertExists()
        compose.onNodeWithText("###", substring = true).assertDoesNotExist()
        compose.onNodeWithText("\\[", substring = true).assertDoesNotExist()
    }
}
