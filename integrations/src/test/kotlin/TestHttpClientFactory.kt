package testutils

import http.CircuitBreakerCfg
import http.HttpClients
import http.IntegrationsHttpConfig
import http.IntegrationsMetrics
import http.RetryCfg
import http.TimeoutMs
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import java.time.Clock

fun testHttpClient(
    metrics: IntegrationsMetrics,
    clock: Clock = Clock.systemUTC(),
    config: IntegrationsHttpConfig = testHttpConfig(),
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
): HttpClient {
    val client = HttpClient(MockEngine) {
        HttpClients.run {
            configure(config, metrics, clock)
        }
        engine {
            addHandler(handler)
        }
    }
    HttpClients.registerRetryMonitor(client, metrics)
    return client
}

fun testHttpConfig(
    userAgent: String = "newsbot-test",
    timeoutMs: TimeoutMs = TimeoutMs(connect = 1000, socket = 1000, request = 1000),
    retryCfg: RetryCfg = RetryCfg(
        maxAttempts = 3,
        baseBackoffMs = 1,
        jitterMs = 0,
        respectRetryAfter = true,
        retryOn = listOf(429, 500, 502, 503, 504)
    ),
    circuitBreakerCfg: CircuitBreakerCfg = CircuitBreakerCfg(
        failuresThreshold = 5,
        windowSeconds = 60,
        openSeconds = 30,
        halfOpenMaxCalls = 2
    )
): IntegrationsHttpConfig = IntegrationsHttpConfig(
    userAgent = userAgent,
    timeoutMs = timeoutMs,
    retry = retryCfg,
    circuitBreaker = circuitBreakerCfg
)
