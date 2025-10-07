package http

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TracePropagationTest {
    @Test
    fun passes_trace_headers_from_context() = runTest {
        val cfg = IntegrationsHttpConfig(
            userAgent = "test-agent",
            timeoutMs = TimeoutMs(connect = 100, socket = 100, request = 200),
            retry = RetryCfg(
                maxAttempts = 2,
                baseBackoffMs = 50,
                jitterMs = 10,
                respectRetryAfter = false,
                retryOn = listOf(500)
            ),
            circuitBreaker = CircuitBreakerCfg(
                failuresThreshold = 3,
                windowSeconds = 10,
                openSeconds = 3,
                halfOpenMaxCalls = 1
            )
        )
        val metrics = IntegrationsMetrics(SimpleMeterRegistry())
        var seenRequestId: String? = null
        var seenTraceId: String? = null
        val client = HttpClient(MockEngine) {
            HttpClients.run {
                configure(cfg, HttpPoolConfig(maxConnectionsPerRoute = 2, keepAliveSeconds = 5), metrics, Clock.systemUTC())
            }
            engine {
                addHandler { request ->
                    seenRequestId = request.headers["X-Request-Id"]
                    seenTraceId = request.headers["Trace-Id"]
                    respond("ok", HttpStatusCode.OK)
                }
            }
        }
        HttpClients.registerRetryMonitor(client, metrics)

        withContext(TraceContext("abc-123")) {
            client.get("https://example.test/ping")
        }

        assertEquals("abc-123", seenRequestId)
        assertEquals("abc-123", seenTraceId)
    }
}
