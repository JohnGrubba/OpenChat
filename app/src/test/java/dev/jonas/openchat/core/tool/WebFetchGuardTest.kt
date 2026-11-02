package dev.jonas.openchat.core.tool

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class WebFetchGuardTest {
    private val client = WebFetchClient(OkHttpClient())

    @Test
    fun `private and loopback addresses are blocked`() {
        assertTrue(client.isBlockedAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue(client.isBlockedAddress(InetAddress.getByName("10.1.2.3")))
        assertTrue(client.isBlockedAddress(InetAddress.getByName("192.168.1.10")))
        assertTrue(client.isBlockedAddress(InetAddress.getByName("169.254.169.254")))
        assertTrue(client.isBlockedAddress(InetAddress.getByName("::1")))
        assertFalse(client.isBlockedAddress(InetAddress.getByName("8.8.8.8")))
    }

    @Test
    fun `localhost and metadata hosts are rejected before connecting`() {
        listOf(
            "http://localhost/",
            "http://127.0.0.1/",
            "http://169.254.169.254/latest/meta-data",
            "https://printer.local/",
        ).forEach { url ->
            val error = runCatching { client.validate(url) }.exceptionOrNull()
            assertTrue("$url should be blocked", error is ToolException)
        }
    }

    @Test
    fun `remote plain http is rejected`() {
        assertTrue(runCatching { client.validate("http://example.com") }.isFailure)
    }

    @Test
    fun `public ip literal is accepted`() {
        val url = client.validate("https://8.8.8.8/path")
        assertEquals("8.8.8.8", url.host)
    }

    @Test
    fun `invalid schemes are rejected`() {
        assertTrue(runCatching { client.validate("ftp://example.com") }.isFailure)
        assertTrue(runCatching { client.validate("not a url") }.isFailure)
    }

    @Test
    fun `html extraction strips navigation and keeps article text`() {
        val html = """
            <html><head><title>Test Page</title></head>
            <body>
              <nav>navigation junk</nav>
              <article>
                <h1>Heading</h1>
                <p>This is the first paragraph with enough text to be useful.</p>
                <p>This is the second paragraph with more useful details.</p>
              </article>
              <footer>footer junk</footer>
            </body></html>
        """.trimIndent()
        val page = client.extractHtml(html, "https://example.com", 10_000)
        assertEquals("Test Page", page.title)
        assertTrue(page.content.contains("first paragraph"))
        assertTrue(page.content.contains("second paragraph"))
        assertFalse(page.content.contains("navigation junk"))
        assertFalse(page.content.contains("footer junk"))
    }

    @Test
    fun `html extraction truncates long content`() {
        val html = "<html><body><article><p>" + "word ".repeat(1000) + "</p></article></body></html>"
        val page = client.extractHtml(html, "https://example.com", 1_000)
        assertTrue(page.truncated)
        assertTrue(page.content.contains("[Truncated]"))
        assertTrue(page.content.length <= 1_050)
    }
}
