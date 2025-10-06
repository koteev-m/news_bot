package http

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import http.HttpPoolConfig

object TestHttpFixtures {

    fun defaultCfg(): IntegrationsHttpConfig = IntegrationsHttpConfig(
        userAgent = "test-agent/1.0",
        timeoutMs = TimeoutMs(connect = 500, socket = 800, request = 1200),
        retry = RetryCfg(
            maxAttempts = 3,
            baseBackoffMs = 50,
            jitterMs = 0,
            respectRetryAfter = true,
            retryOn = listOf(429, 500, 502, 503, 504)
        ),
        circuitBreaker = CircuitBreakerCfg(
            failuresThreshold = 3,
            windowSeconds = 10,
            openSeconds = 3,
            halfOpenMaxCalls = 1
        )
    )

    fun metrics(): IntegrationsMetrics = IntegrationsMetrics(SimpleMeterRegistry())

    fun cb(
        cfg: IntegrationsHttpConfig,
        metrics: IntegrationsMetrics,
        clock: Clock = Clock.systemUTC(),
        service: String = "test"
    ): CircuitBreaker = CircuitBreaker(service, cfg.circuitBreaker, metrics, clock)

    fun client(
        cfg: IntegrationsHttpConfig,
        metrics: IntegrationsMetrics,
        clock: Clock = Clock.systemUTC(),
        service: String = "test",
        engineConfig: MockEngineConfig.() -> Unit
    ): HttpClient = HttpClient(MockEngine) {
        HttpClients.run {
            configure(cfg, defaultPool(), metrics, clock)
        }
        engine(engineConfig)
    }.also { client ->
        HttpClients.registerRetryMonitor(client, metrics)
    }

    fun okEngine(body: String = """{"ok":true}"""): MockEngine = MockEngine {
        respond(content = body, status = HttpStatusCode.OK)
    }

    private fun defaultPool(): HttpPoolConfig = HttpPoolConfig(
        maxConnectionsPerRoute = 8,
        keepAliveSeconds = 15
    )
}
