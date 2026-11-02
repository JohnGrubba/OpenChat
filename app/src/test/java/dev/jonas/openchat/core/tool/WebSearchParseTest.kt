package dev.jonas.openchat.core.tool

import dev.jonas.openchat.core.model.SearchBackend
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchParseTest {
    private val client = WebSearchClient()

    @Test
    fun `duckduckgo results and redirects are parsed`() {
        val html = """
            <html><body>
              <div class="result">
                <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fpage">
                  Example title
                </a>
                <div class="result__snippet">A useful snippet</div>
              </div>
            </body></html>
        """.trimIndent()
        val results = client.parseDuckDuckGo(Jsoup.parse(html))
        assertEquals(1, results.size)
        assertEquals("Example title", results[0].title.trim())
        assertEquals("https://example.com/page", results[0].url)
        assertEquals("A useful snippet", results[0].snippet)
    }

    @Test
    fun `lite results are parsed`() {
        val html = """
            <html><body><table>
              <tr>
                <td><a class="result-link" href="https://example.org/next">Next</a></td>
              </tr>
              <tr><td class="result-snippet">Snippet here</td></tr>
            </table></body></html>
        """.trimIndent()
        val results = client.parseDuckDuckGoLite(Jsoup.parse(html))
        assertEquals(1, results.size)
        assertEquals("https://example.org/next", results[0].url)
        assertEquals("Snippet here", results[0].snippet)
    }

    @Test
    fun `tool results are formatted with numbered sources`() {
        val formatted = listOf(
            SearchResult("One", "https://one.dev", "first"),
            SearchResult("Two", "https://two.dev", ""),
        ).formatForModel()
        assertTrue(formatted.startsWith("1. One"))
        assertTrue(formatted.contains("https://two.dev"))
    }

    @Test
    fun `unknown tools return an error result without network access`() = runBlocking {
        val executor = LocalToolExecutor()
        val result = executor.execute(
            dev.jonas.openchat.core.model.ContentBlock.ToolCall(
                id = "call_1",
                name = "unknown_tool",
                arguments = "{}",
            ),
            backend = SearchBackend.DUCKDUCKGO,
            apiKey = "",
        )
        assertTrue(result.isError)
        assertEquals("call_1", result.toolCallId)
    }
}
