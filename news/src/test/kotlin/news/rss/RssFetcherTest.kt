package news.rss

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import news.config.NewsDefaults
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

    private fun httpClient(payload: String): HttpClient {
        return HttpClient(MockEngine { _ ->
            respond(
                content = payload,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Xml.toString())
            )
        }) {
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
}
