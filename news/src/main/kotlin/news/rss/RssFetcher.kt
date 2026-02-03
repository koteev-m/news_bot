package news.rss

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.CancellationException
import news.config.NewsConfig
import org.slf4j.LoggerFactory
import org.w3c.dom.Element
import kotlin.text.Charsets

class RssFetcher(
    private val config: NewsConfig,
    private val client: HttpClient = defaultClient(config),
    private val stateStore: FeedStateStore = FeedStateStore.Noop,
    private val metrics: RssFetchMetrics = RssFetchMetrics.Noop,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(RssFetcher::class.java)

    suspend fun fetchRss(sourceId: String, url: String): List<RssItem> {
        val now = clock.instant()
        val state = safeGetState(sourceId)
        if (state?.cooldownUntil?.isAfter(now) == true) {
            metrics.markCooldownActive(sourceId, true)
            logger.debug(
                "Skipping RSS fetch for {} (cooldown active until {})",
                sourceId,
                state.cooldownUntil
            )
            return emptyList()
        }
        metrics.markCooldownActive(sourceId, false)
        logger.info("Fetching RSS feed: {}", sanitizeUrl(url))
        return try {
            val response = client.get(url) {
                state?.etag?.let { header(HttpHeaders.IfNoneMatch, it) }
                state?.lastModified?.let { header(HttpHeaders.IfModifiedSince, it) }
            }
            if (response.status == HttpStatusCode.NotModified) {
                val updated = updateState(
                    sourceId = sourceId,
                    previous = state,
                    now = now,
                    etag = response.headers[HttpHeaders.ETag],
                    lastModified = response.headers[HttpHeaders.LastModified],
                    failureCount = 0,
                    cooldownUntil = null,
                )
                safeUpsertState(updated)
                logger.info("RSS feed not modified: {}", sanitizeUrl(url))
                return emptyList()
            }
            if (response.status.value !in 200..299) {
                handleFailure(sourceId, state, now, "HTTP ${response.status.value}")
                return emptyList()
            }
            val payload = response.bodyAsText()
            val normalized = parseRss(payload)
            val updated = updateState(
                sourceId = sourceId,
                previous = state,
                now = now,
                etag = response.headers[HttpHeaders.ETag],
                lastModified = response.headers[HttpHeaders.LastModified],
                failureCount = 0,
                cooldownUntil = null,
            )
            safeUpsertState(updated)
            logger.info("Parsed {} RSS items from {}", normalized.size, sanitizeUrl(url))
            return normalized
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            handleFailure(sourceId, state, now, ex.message, ex)
            emptyList()
        }
    }

    private suspend fun handleFailure(
        sourceId: String,
        state: FeedState?,
        now: Instant,
        details: String?,
        ex: Throwable? = null,
    ) {
        val failureCount = (state?.failureCount ?: 0) + 1
        val cooldownSeconds = calculateCooldownSeconds(failureCount)
        val cooldownUntil = cooldownSeconds?.let { now.plusSeconds(it) }
        val updated = updateState(
            sourceId = sourceId,
            previous = state,
            now = now,
            etag = state?.etag,
            lastModified = state?.lastModified,
            failureCount = failureCount,
            cooldownUntil = cooldownUntil,
        )
        safeUpsertState(updated)
        if (cooldownUntil != null) {
            metrics.incCooldownTotal(sourceId)
            metrics.markCooldownActive(sourceId, true)
        }
        val message = if (cooldownUntil != null) {
            "RSS fetch failed for $sourceId, cooldown ${cooldownSeconds}s"
        } else {
            "RSS fetch failed for $sourceId"
        }
        if (ex != null) {
            logger.warn("{} (details={})", message, details, ex)
        } else {
            logger.warn("{} (details={})", message, details)
        }
    }

    private fun calculateCooldownSeconds(failureCount: Int): Long? {
        val backoff = config.rssBackoff
        if (failureCount < backoff.minFailures) {
            return null
        }
        val maxCooldownSeconds = backoff.maxCooldownSeconds
        if (maxCooldownSeconds <= 0) {
            return 0
        }
        val exponent = (failureCount - backoff.minFailures).coerceAtLeast(0)
        val base = backoff.baseCooldownSeconds.coerceAtLeast(1)
        if (maxCooldownSeconds < base) {
            return maxCooldownSeconds
        }
        if (exponent >= 61) {
            return maxCooldownSeconds
        }
        val multiplier = 1L shl exponent
        val maxAllowed = maxCooldownSeconds / multiplier
        val value = if (base > maxAllowed) {
            maxCooldownSeconds
        } else {
            base * multiplier
        }
        return value.coerceAtMost(maxCooldownSeconds)
    }

    private suspend fun safeGetState(sourceId: String): FeedState? {
        return try {
            stateStore.get(sourceId)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logger.warn("Failed to load RSS state for {}", sourceId, ex)
            null
        }
    }

    private suspend fun safeUpsertState(state: FeedState) {
        try {
            stateStore.upsert(state)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logger.warn("Failed to persist RSS state for {}", state.sourceId, ex)
        }
    }

    private fun updateState(
        sourceId: String,
        previous: FeedState?,
        now: Instant,
        etag: String?,
        lastModified: String?,
        failureCount: Int,
        cooldownUntil: Instant?,
    ): FeedState {
        return FeedState(
            sourceId = sourceId,
            etag = etag ?: previous?.etag,
            lastModified = lastModified ?: previous?.lastModified,
            lastFetchedAt = now,
            lastSuccessAt = if (failureCount == 0) now else previous?.lastSuccessAt,
            failureCount = failureCount,
            cooldownUntil = cooldownUntil,
        )
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
