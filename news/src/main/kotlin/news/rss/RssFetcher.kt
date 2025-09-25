package news.rss

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.xml.parsers.DocumentBuilderFactory
import news.config.NewsConfig
import org.slf4j.LoggerFactory
import org.w3c.dom.Element
import kotlin.text.Charsets

class RssFetcher(
    private val config: NewsConfig,
    private val client: HttpClient = defaultClient(config)
) {
    private val logger = LoggerFactory.getLogger(RssFetcher::class.java)

    suspend fun fetchRss(url: String): List<RssItem> {
        logger.info("Fetching RSS feed: {}", sanitizeUrl(url))
        val response = client.get(url)
        val payload = response.bodyAsText()
        val normalized = parseRss(payload)
        logger.info("Parsed {} RSS items from {}", normalized.size, sanitizeUrl(url))
        return normalized
    }

    private fun parseRss(payload: String): List<RssItem> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        val builder = factory.newDocumentBuilder()
        val document = builder.parse(ByteArrayInputStream(payload.toByteArray(Charsets.UTF_8)))
        val nodes = document.getElementsByTagName("item")
        val items = mutableListOf<RssItem>()
        for (index in 0 until nodes.length) {
            val element = nodes.item(index)
            if (element is Element) {
                val title = element.textContentOf("title")?.trim().orEmpty()
                val link = element.textContentOf("link")?.trim().orEmpty()
                val guid = element.textContentOf("guid")?.trim()
                val description = element.textContentOf("description")?.trim()
                val content = element.textContentOf("content:encoded")?.trim()
                val publishedAt = parseDate(element.textContentOf("pubDate"))
                if (title.isNotBlank() && link.isNotBlank()) {
                    items.add(
                        RssItem(
                            id = guid ?: sha1(link),
                            title = title,
                            link = link,
                            description = description,
                            content = content,
                            publishedAt = publishedAt
                        )
                    )
                }
            }
        }
        return items
    }

    companion object {
        fun defaultClient(config: NewsConfig): HttpClient {
            return HttpClient(CIO) {
                install(HttpTimeout) {
                    requestTimeoutMillis = config.httpTimeoutMs
                    connectTimeoutMillis = config.httpTimeoutMs
                    socketTimeoutMillis = config.httpTimeoutMs
                }
                install(HttpRequestRetry) {
                    retryOnException(maxRetries = 3)
                    retryIf(maxRetries = 3) { _, response ->
                        response?.status == HttpStatusCode.TooManyRequests || (response?.status?.value ?: 0) >= 500
                    }
                    exponentialDelay()
                }
                install(DefaultRequest) {
                    header(HttpHeaders.UserAgent, config.userAgent)
                }
                install(Logging) {
                    level = LogLevel.INFO
                    sanitizeHeader { header -> header == HttpHeaders.Authorization }
                }
            }
        }
    }
}

data class RssItem(
    val id: String,
    val title: String,
    val link: String,
    val description: String?,
    val content: String?,
    val publishedAt: Instant?
)

private fun Element.textContentOf(tag: String): String? {
    val nodes = getElementsByTagName(tag)
    if (nodes.length == 0) return null
    return nodes.item(0)?.textContent
}

private fun parseDate(value: String?): Instant? {
    if (value.isNullOrBlank()) return null
    val formats = listOf(
        DateTimeFormatter.RFC_1123_DATE_TIME,
        DateTimeFormatter.ISO_OFFSET_DATE_TIME,
        DateTimeFormatter.ISO_ZONED_DATE_TIME
    )
    for (formatter in formats) {
        try {
            return ZonedDateTime.parse(value.trim(), formatter).toInstant()
        } catch (_: DateTimeParseException) {
        }
    }
    return null
}

private fun sanitizeUrl(url: String): String {
    return url.substringBefore('?')
}

private fun sha1(value: String): String {
    val digest = MessageDigest.getInstance("SHA-1")
    val hashed = digest.digest(value.toByteArray(Charsets.UTF_8))
    return hashed.joinToString(separator = "") { String.format("%02x", it) }
}
