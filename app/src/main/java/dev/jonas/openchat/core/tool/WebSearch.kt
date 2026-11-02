package dev.jonas.openchat.core.tool

import dev.jonas.openchat.core.model.SearchBackend
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String,
)

class WebSearchClient(
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun search(
        query: String,
        maxResults: Int,
        backend: SearchBackend,
        apiKey: String,
    ): List<SearchResult> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        when (backend) {
            SearchBackend.DUCKDUCKGO -> duckDuckGo(query, maxResults)
            SearchBackend.BRAVE -> brave(query, maxResults, apiKey)
        }
    }

    private suspend fun duckDuckGo(query: String, maxResults: Int): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val html = get("https://html.duckduckgo.com/html/?q=$encoded") ?: ""
        var results = parseDuckDuckGo(Jsoup.parse(html))
        if (results.isEmpty()) {
            val lite = get("https://lite.duckduckgo.com/lite/?q=$encoded") ?: ""
            results = parseDuckDuckGoLite(Jsoup.parse(lite))
        }
        return results.take(maxResults.coerceIn(1, 8))
    }

    internal fun parseDuckDuckGo(doc: Document): List<SearchResult> =
        doc.select("div.result").mapNotNull { element ->
            val anchor = element.selectFirst("a.result__a") ?: return@mapNotNull null
            val href = unwrapRedirect(anchor.attr("href"))
            if (href.isBlank()) return@mapNotNull null
            SearchResult(
                title = anchor.text().trim(),
                url = href,
                snippet = element.selectFirst(".result__snippet")?.text()?.trim().orEmpty(),
            )
        }

    internal fun parseDuckDuckGoLite(doc: Document): List<SearchResult> =
        doc.select("a.result-link").mapNotNull { anchor ->
            val href = unwrapRedirect(anchor.attr("href"))
            if (href.isBlank()) return@mapNotNull null
            val row = anchor.parents().firstOrNull { it.tagName() == "tr" }
            val snippet = row?.selectFirst(".result-snippet")?.text()?.trim()
                ?: row?.nextElementSibling()?.selectFirst(".result-snippet")?.text()?.trim()
                ?: ""
            SearchResult(
                title = anchor.text().trim(),
                url = href,
                snippet = snippet,
            )
        }

    private suspend fun brave(query: String, maxResults: Int, apiKey: String): List<SearchResult> {
        if (apiKey.isBlank()) {
            throw ToolException("Brave Search needs an API key in Settings, or switch to DuckDuckGo.")
        }
        val url = "https://api.search.brave.com/res/v1/web/search" +
            "?q=${URLEncoder.encode(query, "UTF-8")}" +
            "&count=${maxResults.coerceIn(1, 8)}"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-Subscription-Token", apiKey)
            .header("User-Agent", "OpenChat/1.0 (Android)")
            .build()
        val call = client.newCall(request)
        val cancellation = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
        call.execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ToolException("Brave Search returned HTTP ${response.code}: ${body.take(300)}")
            }
            val root = json.parseToJsonElement(body).jsonObject
            val items = root["web"]?.jsonObject?.get("results")?.jsonArray ?: return emptyList()
            return items.mapNotNull { item ->
                val obj = item.jsonObject
                val link = obj["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                SearchResult(
                    title = obj["title"]?.jsonPrimitive?.content.orEmpty(),
                    url = link,
                    snippet = obj["description"]?.jsonPrimitive?.content.orEmpty(),
                )
            }
        }
        } finally {
            cancellation.dispose()
        }
    }

    private suspend fun get(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
            )
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        val call = client.newCall(request)
        val cancellation = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) return null
                return response.body?.string()
            }
        } finally {
            cancellation.dispose()
        }
    }

    internal fun unwrapRedirect(href: String): String {
        val absolute = when {
            href.startsWith("//") -> "https:$href"
            href.startsWith("/") -> "https://duckduckgo.com$href"
            else -> href
        }
        val uri = runCatching { URI(absolute) }.getOrNull() ?: return absolute
        if (!uri.host.orEmpty().endsWith("duckduckgo.com")) return absolute
        val encoded = uri.rawQuery
            ?.split('&')
            ?.firstOrNull { it.startsWith("uddg=") }
            ?.substringAfter('=')
            ?: return absolute
        return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(absolute)
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }
}

class ToolException(message: String) : Exception(message)

fun List<SearchResult>.formatForModel(): String =
    if (isEmpty()) {
        "No results found."
    } else {
        mapIndexed { index, result ->
            buildString {
                append(index + 1).append(". ").append(result.title.ifBlank { result.url }).append('\n')
                append(result.url).append('\n')
                if (result.snippet.isNotBlank()) append(result.snippet).append('\n')
            }
        }.joinToString("\n").trim()
    }
