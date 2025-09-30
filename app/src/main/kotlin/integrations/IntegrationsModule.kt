package integrations

import cbr.CbrClient
import coingecko.CoinGeckoClient
import http.CircuitBreaker
import http.CircuitBreakerCfg
import http.HttpClients
import http.IntegrationsHttpConfig
import http.IntegrationsMetrics
import http.RetryCfg
import http.TimeoutMs
import io.ktor.client.HttpClient
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.configOrNull
import io.ktor.server.config.propertyOrNull
import io.ktor.util.AttributeKey
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import java.time.Clock
import moex.MoexIssClient

private val IntegrationsProviderKey = AttributeKey<IntegrationsProvider>("IntegrationsProvider")

class IntegrationsProvider(
    val httpClient: HttpClient,
    val metrics: IntegrationsMetrics,
    val registry: MeterRegistry,
    val moexClient: MoexIssClient,
    val coinGeckoClient: CoinGeckoClient,
    val cbrClient: CbrClient,
    val circuitBreakers: Map<String, CircuitBreaker>
)

object IntegrationsModule {
    fun provide(env: ApplicationEnvironment, registry: MeterRegistry): IntegrationsProvider {
        val integrationsConfig = env.config.config("integrations")
        val httpConfig = env.integrationsHttpConfig()
        val metrics = IntegrationsMetrics(registry)
        val clock = Clock.systemUTC()
        val httpClient = HttpClients.build(httpConfig, metrics, clock)
        val cbCfg = httpConfig.circuitBreaker

        val moexCb = newCircuitBreaker("moex", cbCfg, metrics, clock)
        val coingeckoCb = newCircuitBreaker("coingecko", cbCfg, metrics, clock)
        val cbrCb = newCircuitBreaker("cbr", cbCfg, metrics, clock)

        val moexClient = MoexIssClient(httpClient, moexCb, metrics).apply {
            setBaseUrl(integrationsConfig.baseUrl("moex", "https://iss.moex.com"))
        }
        val coinGeckoClient = CoinGeckoClient(httpClient, coingeckoCb, metrics).apply {
            setBaseUrl(integrationsConfig.baseUrl("coingecko", "https://api.coingecko.com"))
        }
        val cbrClient = CbrClient(httpClient, cbrCb, metrics).apply {
            setBaseUrl(integrationsConfig.baseUrl("cbr", "https://www.cbr.ru"))
        }

        return IntegrationsProvider(
            httpClient = httpClient,
            metrics = metrics,
            registry = registry,
            moexClient = moexClient,
            coinGeckoClient = coinGeckoClient,
            cbrClient = cbrClient,
            circuitBreakers = mapOf(
                "moex" to moexCb,
                "coingecko" to coingeckoCb,
                "cbr" to cbrCb
            )
        )
    }

    private fun newCircuitBreaker(
        service: String,
        cfg: CircuitBreakerCfg,
        metrics: IntegrationsMetrics,
        clock: Clock
    ): CircuitBreaker = CircuitBreaker(service, cfg, metrics, clock)
}

fun Application.integrationsModule(): IntegrationsProvider {
    if (attributes.contains(IntegrationsProviderKey)) {
        return attributes[IntegrationsProviderKey]
    }
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT).also {
        it.config().commonTags("component", "integrations")
    }
    val provider = IntegrationsModule.provide(environment, registry)
    attributes.put(IntegrationsProviderKey, provider)

    environment.monitor.subscribe(ApplicationStopping) {
        provider.httpClient.close()
        if (registry is AutoCloseable) {
            registry.close()
        }
    }

    return provider
}

private fun ApplicationConfig.baseUrl(section: String, default: String): String =
    configOrNull(section)?.propertyOrNull("baseUrl")?.getString() ?: default

private fun ApplicationEnvironment.integrationsHttpConfig(): IntegrationsHttpConfig {
    val integrationsRoot = config.config("integrations")
    val httpRoot = integrationsRoot.config("http")
    val timeout = httpRoot.config("timeoutMs")
    val retry = httpRoot.config("retry")
    val cb = httpRoot.config("circuitBreaker")
    return IntegrationsHttpConfig(
        userAgent = integrationsRoot.property("userAgent").getString(),
        timeoutMs = TimeoutMs(
            connect = timeout.property("connect").getString().toLong(),
            socket = timeout.property("socket").getString().toLong(),
            request = timeout.property("request").getString().toLong()
        ),
        retry = RetryCfg(
            maxAttempts = retry.property("maxAttempts").getString().toInt(),
            baseBackoffMs = retry.property("baseBackoffMs").getString().toLong(),
            jitterMs = retry.property("jitterMs").getString().toLong(),
            respectRetryAfter = retry.property("respectRetryAfter").getString().toBooleanStrict(),
            retryOn = retry.property("retryOn").getList().map { it.toInt() }
        ),
        circuitBreaker = CircuitBreakerCfg(
            failuresThreshold = cb.property("failuresThreshold").getString().toInt(),
            windowSeconds = cb.property("windowSeconds").getString().toLong(),
            openSeconds = cb.property("openSeconds").getString().toLong(),
            halfOpenMaxCalls = cb.property("halfOpenMaxCalls").getString().toInt()
        )
    )
}
