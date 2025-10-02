package integrations

import cbr.CbrClient
import coingecko.CoinGeckoClient
import http.CircuitBreaker
import http.CircuitBreakerCfg
import http.HttpClients
import http.HttpPoolConfig
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
        val performanceConfig = env.config.configOrNull("performance")
        val poolConfig = performanceConfig.httpPoolConfig()
        val cacheTtlConfig = performanceConfig.cacheTtlConfig()
        val metrics = IntegrationsMetrics(registry)
        val clock = Clock.systemUTC()
        val httpClient = HttpClients.build(httpConfig, poolConfig, metrics, clock)
        val cbCfg = httpConfig.circuitBreaker

        val moexCb = newCircuitBreaker("moex", cbCfg, metrics, clock)
        val coingeckoCb = newCircuitBreaker("coingecko", cbCfg, metrics, clock)
        val cbrCb = newCircuitBreaker("cbr", cbCfg, metrics, clock)

        val moexClient = MoexIssClient(
            client = httpClient,
            cb = moexCb,
            metrics = metrics,
            cacheTtlMs = cacheTtlConfig.moex,
            statusCacheTtlMs = cacheTtlConfig.moex
        ).apply {
            setBaseUrl(integrationsConfig.baseUrl("moex", "https://iss.moex.com"))
        }
        val coinGeckoClient = CoinGeckoClient(
            client = httpClient,
            cb = coingeckoCb,
            metrics = metrics,
            clock = clock,
            priceCacheTtlMs = cacheTtlConfig.coingecko,
            chartCacheTtlMs = cacheTtlConfig.coingecko
        ).apply {
            setBaseUrl(integrationsConfig.baseUrl("coingecko", "https://api.coingecko.com"))
        }
        val cbrClient = CbrClient(
            client = httpClient,
            cb = cbrCb,
            metrics = metrics,
            cacheTtlMs = cacheTtlConfig.cbr,
            clock = clock
        ).apply {
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

private data class CacheTtlConfig(
    val moex: Long,
    val coingecko: Long,
    val cbr: Long
)

private fun ApplicationConfig?.httpPoolConfig(): HttpPoolConfig {
    val section = this?.configOrNull("httpClient")
    val maxPerRoute = section?.intOrNull("maxConnectionsPerRoute") ?: 100
    val keepAliveSeconds = section?.longOrNull("keepAliveSeconds") ?: 30L
    return HttpPoolConfig(maxConnectionsPerRoute = maxPerRoute, keepAliveSeconds = keepAliveSeconds)
}

private fun ApplicationConfig?.cacheTtlConfig(): CacheTtlConfig {
    val section = this?.configOrNull("cacheTtlMs")
    val moexTtl = section?.longOrNull("moex") ?: 15_000L
    val coingeckoTtl = section?.longOrNull("coingecko") ?: 15_000L
    val cbrTtl = section?.longOrNull("cbr") ?: 60_000L
    return CacheTtlConfig(moex = moexTtl, coingecko = coingeckoTtl, cbr = cbrTtl)
}

private fun ApplicationConfig.intOrNull(path: String): Int? =
    propertyOrNull(path)?.getString()?.toIntOrNull()

private fun ApplicationConfig.longOrNull(path: String): Long? =
    propertyOrNull(path)?.getString()?.toLongOrNull()
