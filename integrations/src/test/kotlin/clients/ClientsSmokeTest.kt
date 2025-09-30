package clients

import cbr.CbrClient
import coingecko.CoinGeckoClient
import http.CircuitBreaker
import http.CircuitBreakerCfg
import http.CircuitBreakerOpenException
import http.HttpClients
import http.IntegrationsHttpConfig
import http.IntegrationsMetrics
import http.RetryCfg
import http.TimeoutMs
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.io.IOException
import java.math.BigDecimal
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import moex.MoexIssClient

class ClientsSmokeTest {
    @Test
    fun `clients retry and report metrics`() = runTest {
        val registry = SimpleMeterRegistry()
        val metrics = IntegrationsMetrics(registry)
        val clock = Clock.systemUTC()
        val cfg = IntegrationsHttpConfig(
            userAgent = "test-agent",
            timeoutMs = TimeoutMs(connect = 5000, socket = 5000, request = 5000),
            retry = RetryCfg(
                maxAttempts = 3,
                baseBackoffMs = 1,
                jitterMs = 0,
                respectRetryAfter = false,
                retryOn = listOf(429, 500, 502, 503, 504)
            ),
            circuitBreaker = CircuitBreakerCfg(
                failuresThreshold = 3,
                windowSeconds = 60,
                openSeconds = 30,
                halfOpenMaxCalls = 1
            )
        )
        val attempts = mutableMapOf<String, Int>()
        val engine = MockEngine { request ->
            val key = request.url.encodedPath
            val count = attempts.merge(key, 1, Int::plus) ?: 1
            if (count == 1) {
                respond(
                    content = "",
                    status = HttpStatusCode.InternalServerError,
                    headers = headersOf(HttpHeaders.ContentType, listOf(ContentType.Application.Json.toString()))
                )
            } else {
                when (key) {
                    "/iss/engines/stock/markets/shares/marketstatus.json" -> respond(
                        content = """{"marketstatus":{"columns":["boardid","market","state","title"],"data":[["TQBR","stock","OPEN","Trading"]]}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, listOf(ContentType.Application.Json.toString()))
                    )
                    "/api/v3/simple/price" -> respond(
                        content = """{"bitcoin":{"usd":50000}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, listOf(ContentType.Application.Json.toString()))
                    )
                    "/scripts/XML_daily.asp" -> respond(
                        content = """<?xml version="1.0" encoding="UTF-8"?><ValCurs Date="02.11.2024"><Valute><CharCode>USD</CharCode><Nominal>1</Nominal><Value>95,0000</Value></Valute></ValCurs>""",
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
        val client = HttpClient(engine) {
            HttpClients.run {
                configure(cfg, metrics, clock)
            }
        }
        HttpClients.registerRetryMonitor(client, metrics)

        val cbCfg = CircuitBreakerCfg(
            failuresThreshold = 3,
            windowSeconds = 60,
            openSeconds = 30,
            halfOpenMaxCalls = 1
        )
        val moexCb = CircuitBreaker("moex", cbCfg, metrics, clock)
        val cgCb = CircuitBreaker("coingecko", cbCfg, metrics, clock)
        val cbrCb = CircuitBreaker("cbr", cbCfg, metrics, clock)

        val moexClient = MoexIssClient(client, moexCb, metrics, clock).apply { setBaseUrl("https://example.com") }
        val cgClient = CoinGeckoClient(client, cgCb, metrics, clock, minRequestInterval = kotlin.time.Duration.ZERO).apply {
            setBaseUrl("https://example.com")
        }
        val cbrClient = CbrClient(client, cbrCb, metrics, clock).apply { setBaseUrl("https://example.com") }

        val moexResult = moexClient.getMarketStatus()
        assertTrue(moexResult.isSuccess)
        assertEquals("OPEN", moexResult.getOrNull()?.statuses?.firstOrNull()?.state)

        val cgResult = cgClient.getSimplePrice(listOf("bitcoin"), listOf("usd"))
        assertTrue(cgResult.isSuccess)
        assertEquals(BigDecimal("50000"), cgResult.getOrNull()?.get("bitcoin")?.get("usd"))

        val cbrResult = cbrClient.getXmlDaily(null)
        assertTrue(cbrResult.isSuccess)
        assertEquals("USD", cbrResult.getOrNull()?.firstOrNull()?.currencyCode)

        assertEquals(2, attempts["/iss/engines/stock/markets/shares/marketstatus.json"])
        assertEquals(2, attempts["/api/v3/simple/price"])
        assertEquals(2, attempts["/scripts/XML_daily.asp"])

        assertEquals(1.0, registry.counter("integrations_retry_total", "service", "moex").count())
        assertEquals(1.0, registry.counter("integrations_retry_total", "service", "coingecko").count())
        assertEquals(1.0, registry.counter("integrations_retry_total", "service", "cbr").count())

        assertEquals(1L, registry.timer("integrations_request_seconds", "service", "moex", "outcome", "success").count())
        assertEquals(1L, registry.timer("integrations_request_seconds", "service", "coingecko", "outcome", "success").count())
        assertEquals(1L, registry.timer("integrations_request_seconds", "service", "cbr", "outcome", "success").count())

        client.close()
    }

    @Test
    fun `open breaker prevents call`() = runTest {
        val registry = SimpleMeterRegistry()
        val metrics = IntegrationsMetrics(registry)
        val clock = Clock.systemUTC()
        val cfg = IntegrationsHttpConfig(
            userAgent = "test-agent",
            timeoutMs = TimeoutMs(connect = 1000, socket = 1000, request = 1000),
            retry = RetryCfg(
                maxAttempts = 2,
                baseBackoffMs = 1,
                jitterMs = 0,
                respectRetryAfter = false,
                retryOn = listOf(429, 500)
            ),
            circuitBreaker = CircuitBreakerCfg(
                failuresThreshold = 1,
                windowSeconds = 60,
                openSeconds = 30,
                halfOpenMaxCalls = 1
            )
        )
        var called = 0
        val engine = MockEngine {
            called += 1
            respond(
                content = """{"marketstatus":{"columns":[],"data":[]}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, listOf(ContentType.Application.Json.toString()))
            )
        }
        val client = HttpClient(engine) {
            HttpClients.run {
                configure(cfg, metrics, clock)
            }
        }
        HttpClients.registerRetryMonitor(client, metrics)

        val cb = CircuitBreaker("moex", cfg.circuitBreaker, metrics, clock)
        val moexClient = MoexIssClient(client, cb, metrics, clock).apply { setBaseUrl("https://example.com") }

        assertFailsWith<IOException> {
            cb.withPermit { throw IOException("boom") }
        }

        val result = moexClient.getMarketStatus()
        val failure = result.exceptionOrNull()
        assertIs<CircuitBreakerOpenException>(failure)
        assertEquals(0, called)

        client.close()
    }
}
