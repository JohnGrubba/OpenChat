package dev.jonas.openchat.core.tool

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import okio.Buffer
import org.jsoup.Jsoup
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.math.min

data class FetchedPage(
    val url: String,
    val title: String,
    val content: String,
    val truncated: Boolean,
)

/**
 * Fetches public pages for the local web_fetch tool. Redirects are followed manually so every hop
 * is re-validated; private, loopback and link-local addresses are rejected to avoid SSRF.
 */
class WebFetchClient(
    private val client: OkHttpClient = defaultClient(),
) {
    private data class CacheEntry(val page: FetchedPage, val fetchedAt: Long)

    private val cache = object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
            size > 24
    }

    suspend fun fetch(rawUrl: String, maxChars: Int = 12_000): FetchedPage =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val limit = maxChars.coerceIn(1_000, 20_000)
            val url = validate(rawUrl)
            val key = "$url|$limit"
            synchronized(cache) {
                cache[key]?.takeIf { System.currentTimeMillis() - it.fetchedAt < 10 * 60_000 }
            }?.let { return@withContext it.page }

            var current = url
            var redirects = 0
            while (redirects <= 5) {
                when (val outcome = fetchOnce(current, limit)) {
                    is FetchOutcome.Page -> {
                        synchronized(cache) {
                            cache[key] = CacheEntry(outcome.page, System.currentTimeMillis())
                        }
                        return@withContext outcome.page
                    }
                    is FetchOutcome.Redirect -> {
                        current = validate(outcome.url)
                        redirects++
                    }
                }
            }
            throw ToolException("Too many redirects")
        }

    private sealed interface FetchOutcome {
        data class Redirect(val url: String) : FetchOutcome
        data class Page(val page: FetchedPage) : FetchOutcome
    }

    private suspend fun fetchOnce(
        current: okhttp3.HttpUrl,
        limit: Int,
    ): FetchOutcome {
        val request = Request.Builder()
            .url(current)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) OpenChat/1.0")
            .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
            .header("Accept-Language", "en-US,en;q=0.8")
            .build()
        val call = client.newCall(request)
        val cancellation = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
        call.execute().use { response ->
            if (response.isRedirect) {
                val location = response.header("Location")
                    ?: throw ToolException("Redirect without a location")
                val next = current.resolve(location)
                    ?: throw ToolException("Invalid redirect target")
                return FetchOutcome.Redirect(next.toString())
            }
            if (!response.isSuccessful) {
                throw ToolException("The page returned HTTP ${response.code}")
            }
            val body = response.body ?: throw ToolException("Empty response")
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            if (contentType.isNotEmpty() &&
                !contentType.contains("text/") &&
                !contentType.contains("json") &&
                !contentType.contains("xml") &&
                !contentType.contains("xhtml")
            ) {
                throw ToolException(
                    "That page is ${contentType.substringBefore(';')}; only HTML and text can be read.",
                )
            }
            if (body.contentLength() > MAX_BYTES) {
                throw ToolException("The page is too large to read (over 4 MB)")
            }
            val source = body.source()
            val buffer = Buffer()
            var total = 0L
            while (total < MAX_BYTES) {
                val read = source.read(buffer, min(16 * 1024L, MAX_BYTES - total))
                if (read == -1L) break
                total += read
            }
            val raw = String(buffer.readByteArray(), Charsets.UTF_8)
            val page = if (contentType.contains("html") || contentType.isBlank()) {
                extractHtml(raw, current.toString(), limit)
            } else {
                val text = raw.trim()
                val truncated = text.length > limit
                FetchedPage(
                    url = current.toString(),
                    title = "",
                    content = if (truncated) text.take(limit) + "\n\n[Truncated]" else text,
                    truncated = truncated,
                )
            }
            return FetchOutcome.Page(page)
        }
        } finally {
            cancellation.dispose()
        }
    }

    internal fun validate(rawUrl: String): HttpUrl {
        val url = rawUrl.trim().toHttpUrlOrNull()
            ?: throw ToolException("That is not a valid URL")
        if (url.scheme != "https") {
            throw ToolException("Only HTTPS pages can be fetched")
        }
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw ToolException("URLs with embedded credentials are not allowed")
        }
        val host = url.host.lowercase()
        if (host == "localhost" || host.endsWith(".localhost") ||
            host.endsWith(".local") || host.endsWith(".internal")
        ) {
            throw ToolException("Local and private addresses cannot be fetched")
        }
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull()
        if (addresses.isNullOrEmpty()) throw ToolException("Could not resolve $host")
        if (addresses.any(::isBlockedAddress)) {
            throw ToolException("Local and private addresses cannot be fetched")
        }
        return url
    }

    internal fun isBlockedAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        val bytes = address.address
        if (bytes.size == 4) {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            if (first == 100 && second in 64..127) return true // 100.64.0.0/10
            if (first == 0) return true
        }
        if (bytes.size == 16) {
            val first = bytes[0].toInt() and 0xff
            if (first and 0xfe == 0xfc) return true // fc00::/7
        }
        return false
    }

    internal fun extractHtml(html: String, baseUrl: String, maxChars: Int): FetchedPage {
        val doc = Jsoup.parse(html, baseUrl)
        val title = doc.title().trim()
        doc.select(
            "script, style, noscript, svg, iframe, form, nav, header, footer, aside, button, " +
                "[role=navigation], [aria-hidden=true]",
        ).remove()

        val root = doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.body()
        val builder = StringBuilder()
        var last = ""
        root?.select("h1, h2, h3, h4, p, li, pre, blockquote, td")?.forEach { element ->
            val text = element.text().trim().replace(Regex("\\s+"), " ")
            if (text.isBlank() || text == last) return@forEach
            last = text
            when (element.tagName()) {
                "h1" -> builder.append("\n# ").append(text).append("\n")
                "h2" -> builder.append("\n## ").append(text).append("\n")
                "h3", "h4" -> builder.append("\n### ").append(text).append("\n")
                "li" -> builder.append("• ").append(text).append('\n')
                "pre", "blockquote" -> builder.append(text).append("\n\n")
                else -> builder.append(text).append("\n\n")
            }
        }
        var content = builder.toString().trim()
        if (content.length < 200) {
            content = root?.text()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        }
        val truncated = content.length > maxChars
        if (truncated) content = content.take(maxChars).trimEnd() + "\n\n[Truncated]"
        return FetchedPage(
            url = baseUrl,
            title = title,
            content = content.ifBlank { "(The page had no readable text)" },
            truncated = truncated,
        )
    }

    companion object {
        private const val MAX_BYTES = 4L * 1024 * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
