package data.moex

import http.CircuitBreaker
import http.CircuitBreakerCfg
import http.HttpClientError
import http.HttpClients
import http.HttpPoolConfig
import http.IntegrationsHttpConfig
import http.IntegrationsMetrics
import http.RetryCfg
import http.TimeoutMs
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import java.time.Clock
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import netflow2.Netflow2PullWindow
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

class Netflow2ClientTest {
    @Test
    fun `parses csv payload with nullable columns`() = runTest {
        val csvPayload = """
            meta;ignored
            meta;ignored
            SECID;DATE;P30;P70;P100;PV30;PV70;PV100;VOL;OI
            SBER;2024-01-01;1;;3;4; ; ;7;8
            SBER;2024-01-02;;;;;;;100;
        """.trimIndent()

        val metrics = IntegrationsMetrics(SimpleMeterRegistry())
        val httpConfig = IntegrationsHttpConfig(
            userAgent = "test-agent",
            timeoutMs = TimeoutMs(connect = 1_000, socket = 1_000, request = 1_000),
            retry = RetryCfg(maxAttempts = 2, baseBackoffMs = 1, jitterMs = 0, respectRetryAfter = false, retryOn = listOf()),
            circuitBreaker = CircuitBreakerCfg(failuresThreshold = 5, windowSeconds = 60, openSeconds = 10, halfOpenMaxCalls = 1)
        )

        val httpClient = HttpClient(MockEngine) {
            HttpClients.configure(httpConfig, HttpPoolConfig(maxConnectionsPerRoute = 4, keepAliveSeconds = 30), metrics, Clock.systemUTC())
            engine {
                addHandler { request ->
                    assertEquals("/iss/analyticalproducts/netflow2/securities/SBER.csv", request.url.encodedPath)
                    assertEquals("2024-01-01", request.url.parameters["from"])
                    assertEquals("2024-01-03", request.url.parameters["till"])
                    respond(csvPayload)
                }
            }
        }

        try {
            val client = Netflow2Client(
                client = httpClient,
                circuitBreaker = CircuitBreaker("netflow2", httpConfig.circuitBreaker, metrics, Clock.systemUTC()),
                metrics = metrics,
                retryCfg = httpConfig.retry
            )

            val window = Netflow2PullWindow.ofInclusive(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 3))
            val rows = client.fetchWindow("sber", window).getOrThrow()

        assertEquals(2, rows.size)
        val first = rows.first()
        val second = rows.last()

        assertEquals(LocalDate.of(2024, 1, 1), first.date)
        assertEquals("SBER", first.ticker)
        assertEquals(1L, first.p30)
        assertNull(first.p70)
        assertEquals(3L, first.p100)
        assertEquals(4L, first.pv30)
        assertNull(first.pv70)
        assertNull(first.pv100)
        assertEquals(7L, first.vol)
        assertEquals(8L, first.oi)

        assertEquals(LocalDate.of(2024, 1, 2), second.date)
        assertNull(second.p30)
        assertNull(second.p70)
        assertNull(second.pv30)
        assertNull(second.vol)
            assertEquals(100L, second.oi)
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun `parses csv payload with variable meta lines`() = runTest {
        val csvPayload = """
            something random
            generation date: 2024-01-01
            meta;about date value
            SECID;DATE;P30;P70;P100;PV30;PV70;PV100;VOL;OI
            SBER;2024-01-01;1;2;3;;;;;
        """.trimIndent()

        val metrics = IntegrationsMetrics(SimpleMeterRegistry())
        val httpConfig = IntegrationsHttpConfig(
            userAgent = "test-agent",
            timeoutMs = TimeoutMs(connect = 1_000, socket = 1_000, request = 1_000),
            retry = RetryCfg(maxAttempts = 1, baseBackoffMs = 1, jitterMs = 0, respectRetryAfter = false, retryOn = listOf()),
            circuitBreaker = CircuitBreakerCfg(failuresThreshold = 5, windowSeconds = 60, openSeconds = 10, halfOpenMaxCalls = 1)
        )

        val httpClient = HttpClient(MockEngine) {
            HttpClients.configure(httpConfig, HttpPoolConfig(maxConnectionsPerRoute = 4, keepAliveSeconds = 30), metrics, Clock.systemUTC())
            engine {
                addHandler { respond(csvPayload) }
            }
        }

        try {
            val client = Netflow2Client(
                client = httpClient,
                circuitBreaker = CircuitBreaker("netflow2", httpConfig.circuitBreaker, metrics, Clock.systemUTC()),
                metrics = metrics,
                retryCfg = httpConfig.retry
            )

            val window = Netflow2PullWindow.ofInclusive(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2))
            val rows = client.fetchWindow("sber", window).getOrThrow()

            assertEquals(1, rows.size)
            assertEquals(1L, rows.single().p30)
            assertEquals(2L, rows.single().p70)
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun `ignores meta lines that mention date text`() = runTest {
        val csvPayload = """
            report date only
            meta;date mentioned
            SECID;TRADEDATE;P30;OI
            SBER;2024-01-01;9;10
        """.trimIndent()

        val metrics = IntegrationsMetrics(SimpleMeterRegistry())
        val httpConfig = IntegrationsHttpConfig(
            userAgent = "test-agent",
            timeoutMs = TimeoutMs(connect = 1_000, socket = 1_000, request = 1_000),
            retry = RetryCfg(maxAttempts = 1, baseBackoffMs = 1, jitterMs = 0, respectRetryAfter = false, retryOn = listOf()),
            circuitBreaker = CircuitBreakerCfg(failuresThreshold = 5, windowSeconds = 60, openSeconds = 10, halfOpenMaxCalls = 1)
        )

        val httpClient = HttpClient(MockEngine) {
            HttpClients.configure(httpConfig, HttpPoolConfig(maxConnectionsPerRoute = 4, keepAliveSeconds = 30), metrics, Clock.systemUTC())
            engine { addHandler { respond(csvPayload) } }
        }

        try {
            val client = Netflow2Client(
                client = httpClient,
                circuitBreaker = CircuitBreaker("netflow2", httpConfig.circuitBreaker, metrics, Clock.systemUTC()),
                metrics = metrics,
                retryCfg = httpConfig.retry
            )

            val window = Netflow2PullWindow.ofInclusive(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2))
            val rows = client.fetchWindow("sber", window).getOrThrow()

            assertEquals(1, rows.size)
            val row = rows.single()
            assertEquals(LocalDate.of(2024, 1, 1), row.date)
            assertEquals(9L, row.p30)
            assertEquals(10L, row.oi)
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun `parses csv payload with bom header`() = runTest {
        val csvPayload = """
            random meta
            \uFEFFSECID;DATE;P30;OI
            SBER;2024-02-01;11;12
        """.trimIndent()

        val metrics = IntegrationsMetrics(SimpleMeterRegistry())
        val httpConfig = IntegrationsHttpConfig(
            userAgent = "test-agent",
            timeoutMs = TimeoutMs(connect = 1_000, socket = 1_000, request = 1_000),
            retry = RetryCfg(maxAttempts = 1, baseBackoffMs = 1, jitterMs = 0, respectRetryAfter = false, retryOn = listOf()),
            circuitBreaker = CircuitBreakerCfg(failuresThreshold = 5, windowSeconds = 60, openSeconds = 10, halfOpenMaxCalls = 1)
        )

        val httpClient = HttpClient(MockEngine) {
            HttpClients.configure(httpConfig, HttpPoolConfig(maxConnectionsPerRoute = 4, keepAliveSeconds = 30), metrics, Clock.systemUTC())
            engine { addHandler { respond(csvPayload) } }
        }

        try {
            val client = Netflow2Client(
                client = httpClient,
                circuitBreaker = CircuitBreaker("netflow2", httpConfig.circuitBreaker, metrics, Clock.systemUTC()),
                metrics = metrics,
                retryCfg = httpConfig.retry
            )

            val window = Netflow2PullWindow.ofInclusive(LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 2))
            val rows = client.fetchWindow("sber", window).getOrThrow()

            assertEquals(1, rows.size)
            val row = rows.single()
            assertEquals(LocalDate.of(2024, 2, 1), row.date)
            assertEquals("SBER", row.ticker)
            assertEquals(11L, row.p30)
            assertEquals(12L, row.oi)
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun `parses csv payload with bom and space before header`() = runTest {
        val csvPayload = """
            notice
             \uFEFF SECID;DATE;P30;OI
            SBER;2024-03-01;13;14
        """.trimIndent()

        val metrics = IntegrationsMetrics(SimpleMeterRegistry())
        val httpConfig = IntegrationsHttpConfig(
            userAgent = "test-agent",
            timeoutMs = TimeoutMs(connect = 1_000, socket = 1_000, request = 1_000),
            retry = RetryCfg(maxAttempts = 1, baseBackoffMs = 1, jitterMs = 0, respectRetryAfter = false, retryOn = listOf()),
            circuitBreaker = CircuitBreakerCfg(failuresThreshold = 5, windowSeconds = 60, openSeconds = 10, halfOpenMaxCalls = 1)
        )

        val httpClient = HttpClient(MockEngine) {
            HttpClients.configure(httpConfig, HttpPoolConfig(maxConnectionsPerRoute = 4, keepAliveSeconds = 30), metrics, Clock.systemUTC())
            engine { addHandler { respond(csvPayload) } }
        }

        try {
            val client = Netflow2Client(
                client = httpClient,
                circuitBreaker = CircuitBreaker("netflow2", httpConfig.circuitBreaker, metrics, Clock.systemUTC()),
                metrics = metrics,
                retryCfg = httpConfig.retry
            )

            val window = Netflow2PullWindow.ofInclusive(LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 2))
            val rows = client.fetchWindow("sber", window).getOrThrow()

            assertEquals(1, rows.size)
            val row = rows.single()
            assertEquals(LocalDate.of(2024, 3, 1), row.date)
            assertEquals("SBER", row.ticker)
            assertEquals(13L, row.p30)
            assertEquals(14L, row.oi)
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun `parses json payload and ignores cursor dataset`() = runTest {
        val jsonPayload = """
            {
              "netflow2": {
                "columns": ["SECID", "DATE", "P30", "OI"],
                "data": [
                  ["sber", "2024-01-01", "5", "10"],
                  [null, "2024-01-02", null, null]
                ]
              },
              "netflow2.cursor": {
                "columns": ["foo"],
                "data": [["bar"]]
              }
            }
        """.trimIndent()

        val metrics = IntegrationsMetrics(SimpleMeterRegistry())
        val httpConfig = IntegrationsHttpConfig(
            userAgent = "test-agent",
            timeoutMs = TimeoutMs(connect = 1_000, socket = 1_000, request = 1_000),
            retry = RetryCfg(maxAttempts = 2, baseBackoffMs = 1, jitterMs = 0, respectRetryAfter = false, retryOn = listOf()),
            circuitBreaker = CircuitBreakerCfg(failuresThreshold = 5, windowSeconds = 60, openSeconds = 10, halfOpenMaxCalls = 1)
        )

        val httpClient = HttpClient(MockEngine) {
            HttpClients.configure(httpConfig, HttpPoolConfig(maxConnectionsPerRoute = 4, keepAliveSeconds = 30), metrics, Clock.systemUTC())
            engine {
                addHandler { request ->
                    assertEquals("/iss/analyticalproducts/netflow2/securities/SBER.json", request.url.encodedPath)
                    assertEquals("2024-01-01", request.url.parameters["from"])
                    assertEquals("2024-01-03", request.url.parameters["till"])
                    respond(jsonPayload)
                }
            }
        }

        try {
            val client = Netflow2Client(
                client = httpClient,
                circuitBreaker = CircuitBreaker("netflow2", httpConfig.circuitBreaker, metrics, Clock.systemUTC()),
                metrics = metrics,
                retryCfg = httpConfig.retry,
                config = Netflow2ClientConfig(format = Netflow2Format.JSON)
            )

            val window = Netflow2PullWindow.ofInclusive(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 3))
            val rows = client.fetchWindow("sber", window).getOrThrow()

        assertEquals(2, rows.size)
        val first = rows.first()
        val second = rows.last()

        assertEquals("SBER", first.ticker)
        assertEquals(5L, first.p30)
        assertEquals(10L, first.oi)

            assertEquals(LocalDate.of(2024, 1, 2), second.date)
            assertEquals("SBER", second.ticker)
            assertNull(second.p30)
            assertNull(second.oi)
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun `parses minimal json payload without explicit netflow key`() = runTest {
        val jsonPayload = """
            {
              "data": [["SBER", "2024-01-01", "7"]],
              "columns": ["SECID", "DATE", "P30"],
              "meta.cursor": {}
            }
        """.trimIndent()

        val metrics = IntegrationsMetrics(SimpleMeterRegistry())
        val httpConfig = IntegrationsHttpConfig(
            userAgent = "test-agent",
            timeoutMs = TimeoutMs(connect = 1_000, socket = 1_000, request = 1_000),
            retry = RetryCfg(maxAttempts = 1, baseBackoffMs = 1, jitterMs = 0, respectRetryAfter = false, retryOn = listOf()),
            circuitBreaker = CircuitBreakerCfg(failuresThreshold = 5, windowSeconds = 60, openSeconds = 10, halfOpenMaxCalls = 1)
        )

        val httpClient = HttpClient(MockEngine) {
            HttpClients.configure(httpConfig, HttpPoolConfig(maxConnectionsPerRoute = 4, keepAliveSeconds = 30), metrics, Clock.systemUTC())
            engine { addHandler { respond(jsonPayload) } }
        }

        try {
            val client = Netflow2Client(
                client = httpClient,
                circuitBreaker = CircuitBreaker("netflow2", httpConfig.circuitBreaker, metrics, Clock.systemUTC()),
                metrics = metrics,
                retryCfg = httpConfig.retry,
                config = Netflow2ClientConfig(format = Netflow2Format.JSON)
            )

            val window = Netflow2PullWindow.ofInclusive(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2))
            val rows = client.fetchWindow("SBER", window).getOrThrow()

            assertEquals(1, rows.size)
            val row = rows.single()
            assertEquals(LocalDate.of(2024, 1, 1), row.date)
            assertEquals("SBER", row.ticker)
            assertEquals(7L, row.p30)
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun `does not retry non-retriable http status and surfaces not found`() = runTest {
        val metrics = IntegrationsMetrics(SimpleMeterRegistry())
        val httpConfig = IntegrationsHttpConfig(
            userAgent = "test-agent",
            timeoutMs = TimeoutMs(connect = 1_000, socket = 1_000, request = 1_000),
            retry = RetryCfg(maxAttempts = 3, baseBackoffMs = 1, jitterMs = 0, respectRetryAfter = false, retryOn = listOf()),
            circuitBreaker = CircuitBreakerCfg(failuresThreshold = 5, windowSeconds = 60, openSeconds = 10, halfOpenMaxCalls = 1)
        )

        var attempts = 0
        val httpClient = HttpClient(MockEngine) {
            HttpClients.configure(httpConfig, HttpPoolConfig(maxConnectionsPerRoute = 4, keepAliveSeconds = 30), metrics, Clock.systemUTC())
            engine {
                addHandler {
                    attempts += 1
                    respondError(HttpStatusCode.NotFound)
                }
            }
        }

        try {
            val client = Netflow2Client(
                client = httpClient,
                circuitBreaker = CircuitBreaker("netflow2", httpConfig.circuitBreaker, metrics, Clock.systemUTC()),
                metrics = metrics,
                retryCfg = httpConfig.retry
            )

            val window = Netflow2PullWindow.ofInclusive(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 3))
            val result = client.fetchWindow("sber", window)

            assertTrue(result.isFailure)
            assertEquals(1, attempts)
            val error = result.exceptionOrNull()
            val notFound = assertIs<Netflow2ClientError.NotFound>(error)
            val origin = assertIs<HttpClientError.HttpStatusError>(notFound.origin)
            assertEquals(HttpStatusCode.NotFound, origin.status)
            assertTrue(origin.requestUrl.contains("/iss/analyticalproducts/netflow2/securities/"))
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun `maps http status error 404 to not found when thrown by engine`() = runTest {
        val metrics = IntegrationsMetrics(SimpleMeterRegistry())
        val httpConfig = IntegrationsHttpConfig(
            userAgent = "test-agent",
            timeoutMs = TimeoutMs(connect = 1_000, socket = 1_000, request = 1_000),
            retry = RetryCfg(maxAttempts = 1, baseBackoffMs = 1, jitterMs = 0, respectRetryAfter = false, retryOn = listOf()),
            circuitBreaker = CircuitBreakerCfg(failuresThreshold = 5, windowSeconds = 60, openSeconds = 10, halfOpenMaxCalls = 1)
        )

        val httpClient = HttpClient(MockEngine) {
            HttpClients.configure(httpConfig, HttpPoolConfig(maxConnectionsPerRoute = 4, keepAliveSeconds = 30), metrics, Clock.systemUTC())
            engine {
                addHandler { request ->
                    throw HttpClientError.HttpStatusError(
                        status = HttpStatusCode.NotFound,
                        requestUrl = request.url.toString(),
                        origin = IllegalStateException("response exception")
                    )
                }
            }
        }

        try {
            val client = Netflow2Client(
                client = httpClient,
                circuitBreaker = CircuitBreaker("netflow2", httpConfig.circuitBreaker, metrics, Clock.systemUTC()),
                metrics = metrics,
                retryCfg = httpConfig.retry
            )

            val window = Netflow2PullWindow.ofInclusive(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 3))
            val result = client.fetchWindow("SBER", window)

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            val notFound = assertIs<Netflow2ClientError.NotFound>(error)
            assertEquals("SBER", notFound.sec)
            val origin = assertIs<HttpClientError.HttpStatusError>(notFound.origin)
            assertEquals(HttpStatusCode.NotFound, origin.status)
            assertTrue(origin.requestUrl.contains("/iss/analyticalproducts/netflow2/securities/"))
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun `maps http status error 404 with dot ticker to not found`() = runTest {
        val metrics = IntegrationsMetrics(SimpleMeterRegistry())
        val httpConfig = IntegrationsHttpConfig(
            userAgent = "test-agent",
            timeoutMs = TimeoutMs(connect = 1_000, socket = 1_000, request = 1_000),
            retry = RetryCfg(maxAttempts = 1, baseBackoffMs = 1, jitterMs = 0, respectRetryAfter = false, retryOn = listOf()),
            circuitBreaker = CircuitBreakerCfg(failuresThreshold = 5, windowSeconds = 60, openSeconds = 10, halfOpenMaxCalls = 1)
        )

        val httpClient = HttpClient(MockEngine) {
            HttpClients.configure(httpConfig, HttpPoolConfig(maxConnectionsPerRoute = 4, keepAliveSeconds = 30), metrics, Clock.systemUTC())
            engine {
                addHandler { request ->
                    throw HttpClientError.HttpStatusError(
                        status = HttpStatusCode.NotFound,
                        requestUrl = request.url.toString(),
                        origin = IllegalStateException("response exception")
                    )
                }
            }
        }

        try {
            val client = Netflow2Client(
                client = httpClient,
                circuitBreaker = CircuitBreaker("netflow2", httpConfig.circuitBreaker, metrics, Clock.systemUTC()),
                metrics = metrics,
                retryCfg = httpConfig.retry
            )

            val window = Netflow2PullWindow.ofInclusive(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 3))
            val result = client.fetchWindow("abc.def", window)

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            val notFound = assertIs<Netflow2ClientError.NotFound>(error)
            assertEquals("ABC.DEF", notFound.sec)
            val origin = assertIs<HttpClientError.HttpStatusError>(notFound.origin)
            assertEquals(HttpStatusCode.NotFound, origin.status)
            assertTrue(origin.requestUrl.contains("/iss/analyticalproducts/netflow2/securities/"))
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun `maps 400 response to validation error with http status origin`() = runTest {
        val metrics = IntegrationsMetrics(SimpleMeterRegistry())
        val httpConfig = IntegrationsHttpConfig(
            userAgent = "test-agent",
            timeoutMs = TimeoutMs(connect = 1_000, socket = 1_000, request = 1_000),
            retry = RetryCfg(maxAttempts = 3, baseBackoffMs = 1, jitterMs = 0, respectRetryAfter = false, retryOn = listOf()),
            circuitBreaker = CircuitBreakerCfg(failuresThreshold = 5, windowSeconds = 60, openSeconds = 10, halfOpenMaxCalls = 1)
        )

        var attempts = 0
        val httpClient = HttpClient(MockEngine) {
            HttpClients.configure(httpConfig, HttpPoolConfig(maxConnectionsPerRoute = 4, keepAliveSeconds = 30), metrics, Clock.systemUTC())
            engine {
                addHandler {
                    attempts += 1
                    respondError(HttpStatusCode.BadRequest)
                }
            }
        }

        try {
            val client = Netflow2Client(
                client = httpClient,
                circuitBreaker = CircuitBreaker("netflow2", httpConfig.circuitBreaker, metrics, Clock.systemUTC()),
                metrics = metrics,
                retryCfg = httpConfig.retry
            )

            val window = Netflow2PullWindow.ofInclusive(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 3))
            val result = client.fetchWindow("SBER", window)

            assertTrue(result.isFailure)
            assertEquals(1, attempts)
            val error = result.exceptionOrNull()
            val validation = assertIs<Netflow2ClientError.ValidationError>(error)
            assertTrue(validation.details.contains("invalid sec"))
            val origin = assertIs<HttpClientError.HttpStatusError>(validation.origin)
            assertEquals(HttpStatusCode.BadRequest, origin.status)
            assertTrue(origin.requestUrl.contains("/iss/analyticalproducts/netflow2/securities/"))
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun `maps http status error 400 to validation error when thrown by engine`() = runTest {
        val metrics = IntegrationsMetrics(SimpleMeterRegistry())
        val httpConfig = IntegrationsHttpConfig(
            userAgent = "test-agent",
            timeoutMs = TimeoutMs(connect = 1_000, socket = 1_000, request = 1_000),
            retry = RetryCfg(maxAttempts = 1, baseBackoffMs = 1, jitterMs = 0, respectRetryAfter = false, retryOn = listOf()),
            circuitBreaker = CircuitBreakerCfg(failuresThreshold = 5, windowSeconds = 60, openSeconds = 10, halfOpenMaxCalls = 1)
        )

        val httpClient = HttpClient(MockEngine) {
            HttpClients.configure(httpConfig, HttpPoolConfig(maxConnectionsPerRoute = 4, keepAliveSeconds = 30), metrics, Clock.systemUTC())
            engine {
                addHandler { request ->
                    throw HttpClientError.HttpStatusError(
                        status = HttpStatusCode.BadRequest,
                        requestUrl = request.url.toString(),
                        origin = IllegalStateException("response exception")
                    )
                }
            }
        }

        try {
            val client = Netflow2Client(
                client = httpClient,
                circuitBreaker = CircuitBreaker("netflow2", httpConfig.circuitBreaker, metrics, Clock.systemUTC()),
                metrics = metrics,
                retryCfg = httpConfig.retry
            )

            val window = Netflow2PullWindow.ofInclusive(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 3))
            val result = client.fetchWindow("SBER", window)

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            val validation = assertIs<Netflow2ClientError.ValidationError>(error)
            assertTrue(validation.details.contains("invalid sec"))
            assertTrue(validation.details.contains("SBER"))
            val origin = assertIs<HttpClientError.HttpStatusError>(validation.origin)
            assertTrue(origin.requestUrl.contains("/iss/analyticalproducts/netflow2/securities/"))
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun `maps response exception to not found with sanitized body snippet`() = runTest {
        val metrics = IntegrationsMetrics(SimpleMeterRegistry())
        val httpConfig = IntegrationsHttpConfig(
            userAgent = "test-agent",
            timeoutMs = TimeoutMs(connect = 1_000, socket = 1_000, request = 1_000),
            retry = RetryCfg(maxAttempts = 1, baseBackoffMs = 1, jitterMs = 0, respectRetryAfter = false, retryOn = listOf()),
            circuitBreaker = CircuitBreakerCfg(failuresThreshold = 5, windowSeconds = 60, openSeconds = 10, halfOpenMaxCalls = 1)
        )

        val body = "missing\tsec\nnot found"
        val httpClient = HttpClient(MockEngine) {
            HttpClients.configure(httpConfig, HttpPoolConfig(maxConnectionsPerRoute = 4, keepAliveSeconds = 30), metrics, Clock.systemUTC())
            engine {
                addHandler { respond(body, status = HttpStatusCode.NotFound) }
            }
        }

        try {
            val client = Netflow2Client(
                client = httpClient,
                circuitBreaker = CircuitBreaker("netflow2", httpConfig.circuitBreaker, metrics, Clock.systemUTC()),
                metrics = metrics,
                retryCfg = httpConfig.retry
            )

            val window = Netflow2PullWindow.ofInclusive(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2))
            val result = client.fetchWindow("SBER", window)

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            val notFound = assertIs<Netflow2ClientError.NotFound>(error)
            val origin = assertIs<HttpClientError.HttpStatusError>(notFound.origin)
            assertEquals(HttpStatusCode.NotFound, origin.status)
            assertTrue(origin.requestUrl.contains("/iss/analyticalproducts/netflow2/securities/"))
            val snippet = assertNotNull(origin.bodySnippet)
            assertTrue(!snippet.contains("\n"))
            assertTrue(!snippet.contains("\r"))
            assertTrue(!snippet.contains("\t"))
            assertTrue(snippet.contains("missing sec"))
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun `includes sanitized body snippet in http status error`() = runTest {
        val metrics = IntegrationsMetrics(SimpleMeterRegistry())
        val httpConfig = IntegrationsHttpConfig(
            userAgent = "test-agent",
            timeoutMs = TimeoutMs(connect = 1_000, socket = 1_000, request = 1_000),
            retry = RetryCfg(maxAttempts = 1, baseBackoffMs = 1, jitterMs = 0, respectRetryAfter = false, retryOn = listOf()),
            circuitBreaker = CircuitBreakerCfg(failuresThreshold = 5, windowSeconds = 60, openSeconds = 10, halfOpenMaxCalls = 1)
        )

        val body = "start\tmiddle\n" + "a".repeat(600) + "\n end "
        val httpClient = HttpClient(MockEngine) {
            HttpClients.configure(httpConfig, HttpPoolConfig(maxConnectionsPerRoute = 4, keepAliveSeconds = 30), metrics, Clock.systemUTC())
            engine {
                addHandler { respond(body, status = HttpStatusCode.InternalServerError) }
            }
        }

        try {
            val client = Netflow2Client(
                client = httpClient,
                circuitBreaker = CircuitBreaker("netflow2", httpConfig.circuitBreaker, metrics, Clock.systemUTC()),
                metrics = metrics,
                retryCfg = httpConfig.retry
            )

            val window = Netflow2PullWindow.ofInclusive(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2))
            val result = client.fetchWindow("SBER", window)

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertIs<Netflow2ClientError.UpstreamError>(error)
            val cause = error.cause
            assertIs<HttpClientError.HttpStatusError>(cause)
            assertEquals(
                "https://iss.moex.com/iss/analyticalproducts/netflow2/securities/SBER.csv",
                cause.requestUrl
            )
            val snippet = assertNotNull(cause.bodySnippet)
            assertTrue(!snippet.contains("\n"))
            assertTrue(!snippet.contains("\r"))
            assertTrue(!snippet.contains("\t"))
            assertTrue(snippet.length <= 512)
            val normalized = ("start middle " + "a".repeat(600) + " end").trim()
            assertTrue(normalized.length > 512)
            assertTrue(snippet.endsWith("…"))
            assertTrue(error.message?.contains("HTTP 500 for ${cause.requestUrl}") == true)
            assertTrue(error.message?.contains("body:") == true)
        } finally {
            httpClient.close()
        }
    }
}
