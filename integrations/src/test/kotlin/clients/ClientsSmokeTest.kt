package clients

import http.CircuitBreakerOpenException
import http.IntegrationsMetrics
import http.TestHttpFixtures
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.io.IOException
import java.math.BigDecimal
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import cbr.CbrClient
import coingecko.CoinGeckoClient
import moex.MoexIssClient

class ClientsSmokeTest {

    @Test
    fun clientsRetryAndReportMetrics() = runTest {
        val registry = SimpleMeterRegistry()
        val metrics = IntegrationsMetrics(registry)
        val clock = Clock.systemUTC()
        val cfg = TestHttpFixtures.defaultCfg()
        val attempts = mutableMapOf<String, Int>()
        val http = TestHttpFixtures.client(cfg, metrics, clock) {
            addHandler { request ->
                val path = request.url.encodedPath
                val count = attempts.merge(path, 1, Int::plus) ?: 1
                if (count == 1) {
                    respond(
                        content = "{}",
                        status = HttpStatusCode.InternalServerError,
                        headers = headersOf(HttpHeaders.ContentType, listOf(ContentType.Application.Json.toString()))
                    )
                } else {
                    when (path) {
                        "/iss/engines/stock/markets/shares/marketstatus.json" -> respond(
                            content = """{"marketstatus":{"columns":["boardid","market","state"],"data":[["TQBR","stock","OPEN"]]}}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, listOf(ContentType.Application.Json.toString()))
                        )
                        "/api/v3/simple/price" -> respond(
                            content = """{"bitcoin":{"usd":50000}}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, listOf(ContentType.Application.Json.toString()))
                        )
                        "/scripts/XML_daily.asp" -> respond(
                            content = """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <ValCurs Date="02.11.2024">
                                  <Valute><CharCode>USD</CharCode><Nominal>1</Nominal><Value>95,0000</Value></Valute>
                                </ValCurs>
                            """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, listOf(ContentType.Application.Xml.toString()))
                        )
                        else -> respond(
                            content = "{}",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, listOf(ContentType.Application.Json.toString()))
                        )
                    }
                }
            }
        }
        val moexCb = TestHttpFixtures.cb(cfg, metrics, clock, service = "moex")
        val cgCb = TestHttpFixtures.cb(cfg, metrics, clock, service = "coingecko")
        val cbrCb = TestHttpFixtures.cb(cfg, metrics, clock, service = "cbr")

        val moexClient = MoexIssClient(http, moexCb, metrics, cacheTtlMs = 15_000L, statusCacheTtlMs = 5_000L, clock = clock).apply {
            setBaseUrl("https://example.test")
        }
        val cgClient = CoinGeckoClient(
            http,
            cgCb,
            metrics,
            clock = clock,
            minRequestInterval = kotlin.time.Duration.ZERO,
            priceCacheTtlMs = 15_000L
        ).apply { setBaseUrl("https://example.test") }
        val cbrClient = CbrClient(http, cbrCb, metrics, cacheTtlMs = 15_000L, clock = clock).apply {
            setBaseUrl("https://example.test")
        }

        val moexResult = moexClient.getMarketStatus()
        assertTrue(moexResult.isSuccess, "moex failure: ${moexResult.exceptionOrNull()}")
        assertEquals("OPEN", moexResult.getOrThrow().statuses.first().state)

        val cgResult = cgClient.getSimplePrice(listOf("bitcoin"), listOf("usd"))
        assertTrue(cgResult.isSuccess, "cg failure: ${cgResult.exceptionOrNull()}")
        assertEquals(BigDecimal("50000"), cgResult.getOrThrow()["bitcoin"]?.get("usd"))

        val cbrResult = cbrClient.getXmlDaily(null)
        assertTrue(cbrResult.isSuccess, "cbr failure: ${cbrResult.exceptionOrNull()}")
        assertEquals("USD", cbrResult.getOrThrow().first().currencyCode)

        assertEquals(2, attempts["/iss/engines/stock/markets/shares/marketstatus.json"])
        assertEquals(2, attempts["/api/v3/simple/price"])
        assertEquals(2, attempts["/scripts/XML_daily.asp"])

        assertEquals(1.0, registry.counter("integrations_retry_total", "service", "moex").count())
        assertEquals(1.0, registry.counter("integrations_retry_total", "service", "coingecko").count())
        assertEquals(1.0, registry.counter("integrations_retry_total", "service", "cbr").count())

        http.close()
    }

    @Test
    fun openBreakerPreventsCall() = runTest {
        val registry = SimpleMeterRegistry()
        val metrics = IntegrationsMetrics(registry)
        val clock = Clock.systemUTC()
        val cfg = TestHttpFixtures.defaultCfg()
        var called = 0
        val http = TestHttpFixtures.client(cfg, metrics, clock) {
            addHandler {
                called += 1
                respond(
                    content = """{"marketstatus":{"columns":[],"data":[]}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, listOf(ContentType.Application.Json.toString()))
                )
            }
        }
        val cb = TestHttpFixtures.cb(cfg, metrics, clock, service = "moex")
        val moexClient = MoexIssClient(http, cb, metrics, cacheTtlMs = 15_000L, statusCacheTtlMs = 5_000L, clock = clock).apply {
            setBaseUrl("https://example.test")
        }

        repeat(cfg.circuitBreaker.failuresThreshold) {
            assertFailsWith<IOException> {
                cb.withPermit { throw IOException("boom") }
            }
        }

        val result = moexClient.getMarketStatus()
        val failure = result.exceptionOrNull()
        assertIs<CircuitBreakerOpenException>(failure)
        assertEquals(0, called)

        http.close()
    }
}
