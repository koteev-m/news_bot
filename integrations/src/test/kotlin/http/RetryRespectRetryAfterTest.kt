package http

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class RetryRespectRetryAfterTest {
    @Test
    fun `retries honor numeric retry-after header`() = runTest {
        val registry = SimpleMeterRegistry()
        val metrics = IntegrationsMetrics(registry)
        val clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)
        var attempts = 0
        val engine = MockEngine {
            attempts += 1
            if (attempts == 1) {
                respond(
                    content = "",
                    status = HttpStatusCode.TooManyRequests,
                    headers = headersOf(HttpHeaders.RetryAfter, listOf("2"))
                )
            } else {
                respond(
                    content = "{}",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, listOf(ContentType.Application.Json.toString()))
                )
            }
        }
        val client = HttpClient(engine) {
            HttpClients.run {
                configure(testConfig(respectRetryAfter = true), metrics, clock)
            }
        }
        HttpClients.registerRetryMonitor(client, metrics)

        val response = HttpClients.measure("test-service") {
            client.get("https://example.com/test")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(2, attempts)
        assertEquals(1.0, registry.counter("integrations_retry_total", "service", "test-service").count())
        assertEquals(
            1.0,
            registry.counter("integrations_retry_after_honored_total", "service", "test-service").count()
        )

        client.close()
    }

    @Test
    fun `retries honor http-date retry-after header`() = runTest {
        val registry = SimpleMeterRegistry()
        val metrics = IntegrationsMetrics(registry)
        val clock = Clock.fixed(Instant.parse("2024-05-05T12:00:00Z"), ZoneOffset.UTC)
        var attempts = 0
        val retryAfterValue = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
            clock.instant().plusSeconds(3).atZone(ZoneOffset.UTC)
        )
        val engine = MockEngine {
            attempts += 1
            if (attempts == 1) {
                respond(
                    content = "",
                    status = HttpStatusCode.TooManyRequests,
                    headers = headersOf(HttpHeaders.RetryAfter, listOf(retryAfterValue))
                )
            } else {
                respond(
                    content = "{}",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, listOf(ContentType.Application.Json.toString()))
                )
            }
        }
        val client = HttpClient(engine) {
            HttpClients.run {
                configure(testConfig(respectRetryAfter = true), metrics, clock)
            }
        }
        HttpClients.registerRetryMonitor(client, metrics)

        val response = HttpClients.measure("test-service") {
            client.get("https://example.com/test-date")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(2, attempts)
        assertEquals(1.0, registry.counter("integrations_retry_total", "service", "test-service").count())
        assertEquals(
            1.0,
            registry.counter("integrations_retry_after_honored_total", "service", "test-service").count()
        )

        client.close()
    }

    private fun testConfig(respectRetryAfter: Boolean): IntegrationsHttpConfig = IntegrationsHttpConfig(
        userAgent = "test-agent",
        timeoutMs = TimeoutMs(connect = 500, socket = 500, request = 500),
        retry = RetryCfg(
            maxAttempts = 3,
            baseBackoffMs = 1,
            jitterMs = 0,
            respectRetryAfter = respectRetryAfter,
            retryOn = listOf(429, 500, 502, 503, 504)
        ),
        circuitBreaker = CircuitBreakerCfg(
            failuresThreshold = 3,
            windowSeconds = 60,
            openSeconds = 30,
            halfOpenMaxCalls = 1
        )
    )
}
