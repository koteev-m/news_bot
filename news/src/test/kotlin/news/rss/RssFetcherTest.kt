package news.rss

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Headers
import io.ktor.http.headersOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import news.config.NewsDefaults
import news.config.RssBackoffConfig
import news.sources.CbrSource
import news.sources.MoexSource

class RssFetcherTest {
    private val config = NewsDefaults.defaultConfig

    @Test
    fun `parse cbr feed`() = runTest {
        val payload = loadFixture("feeds/cbr_rss.xml")
        val client = httpClient(payload)
        val fetcher = RssFetcher(config, client)
        val source = CbrSource(fetcher, "https://test.local/cbr.xml")

        val articles = source.fetch()

        assertEquals(2, articles.size)
        val first = articles.first()
        assertEquals("www.cbr.ru", first.domain)
        assertEquals("ru", first.language)
        assertEquals(emptySet(), first.tickers)
        assertEquals(setOf("Bank of Russia"), first.entities)
    }

    @Test
    fun `parse moex feed with tickers`() = runTest {
        val payload = loadFixture("feeds/moex_rss.xml")
        val client = httpClient(payload)
        val fetcher = RssFetcher(config, client)
        val source = MoexSource(fetcher, "https://test.local/moex.xml")

        val articles = source.fetch()

        assertEquals(2, articles.size)
        val first = articles.first()
        assertEquals(setOf("SBER", "GAZP"), first.tickers)
        assertEquals(setOf("Moscow Exchange", "SBER", "GAZP"), first.entities)
    }

    @Test
    fun `uses conditional headers and handles 304`() = runTest {
        val payload = loadFixture("feeds/cbr_rss.xml")
        val requestHeaders = mutableListOf<Headers>()
        var callCount = 0
        val client = HttpClient(
            MockEngine { request ->
                requestHeaders += request.headers
                callCount += 1
                if (callCount == 1) {
                    respond(
                        content = payload,
                        headers = headersOf(
                            HttpHeaders.ContentType to listOf(ContentType.Application.Xml.toString()),
                            HttpHeaders.ETag to listOf("\"etag-value\""),
                            HttpHeaders.LastModified to listOf("Wed, 21 Oct 2015 07:28:00 GMT"),
                        ),
                    )
                } else {
                    respond(
                        content = "",
                        status = HttpStatusCode.NotModified,
                    )
                }
            }
        ) {
            install(HttpTimeout) {
                requestTimeoutMillis = 1000
                connectTimeoutMillis = 1000
            }
        }
        val store = InMemoryFeedStateStore()
        val fetcher = RssFetcher(config, client, store)

        val first = fetcher.fetchRss("cbr", "https://test.local/cbr.xml")
        val second = fetcher.fetchRss("cbr", "https://test.local/cbr.xml")

        assertEquals(2, first.size)
        assertEquals(0, second.size)
        assertNull(requestHeaders.first()[HttpHeaders.IfNoneMatch])
        assertNull(requestHeaders.first()[HttpHeaders.IfModifiedSince])
        assertEquals("\"etag-value\"", requestHeaders.last()[HttpHeaders.IfNoneMatch])
        assertNotNull(requestHeaders.last()[HttpHeaders.IfModifiedSince])
    }

    @Test
    fun `cooldown skips repeated failures`() = runTest {
        var callCount = 0
        val client = HttpClient(
            MockEngine {
                callCount += 1
                respond(
                    content = "",
                    status = HttpStatusCode.InternalServerError,
                )
            }
        ) {
            install(HttpTimeout) {
                requestTimeoutMillis = 1000
                connectTimeoutMillis = 1000
            }
        }
        val clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)
        val store = InMemoryFeedStateStore()
        val testConfig = NewsDefaults.defaultConfig.copy(
            rssBackoff = RssBackoffConfig(
                minFailures = 1,
                baseCooldownSeconds = 60,
                maxCooldownSeconds = 3600,
            )
        )
        val fetcher = RssFetcher(testConfig, client, store, clock = clock)

        val first = fetcher.fetchRss("cbr", "https://test.local/cbr.xml")
        val second = fetcher.fetchRss("cbr", "https://test.local/cbr.xml")

        assertEquals(0, first.size)
        assertEquals(0, second.size)
        assertEquals(1, callCount)
    }

    private fun httpClient(payload: String): HttpClient {
        return HttpClient(
            MockEngine { _ ->
                respond(
                    content = payload,
                    headers = headersOf(
                        HttpHeaders.ContentType to listOf(ContentType.Application.Xml.toString()),
                    ),
                )
            }
        ) {
            install(HttpTimeout) {
                requestTimeoutMillis = 1000
                connectTimeoutMillis = 1000
            }
        }
    }

    private fun loadFixture(path: String): String {
        val resource = checkNotNull(javaClass.classLoader?.getResource(path)) { "Missing fixture $path" }
        return resource.readText()
    }

    private class InMemoryFeedStateStore : FeedStateStore {
        private val items = mutableMapOf<String, FeedState>()

        override suspend fun get(sourceId: String): FeedState? = items[sourceId]

        override suspend fun upsert(state: FeedState) {
            items[state.sourceId] = state
        }
    }
}
