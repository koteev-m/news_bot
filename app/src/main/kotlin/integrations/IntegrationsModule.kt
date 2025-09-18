package integrations

import cbr.CbrClient
import coingecko.CoinGeckoClient
import http.defaultHttpClient
import io.ktor.client.HttpClient
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.config.configOrNull
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.ktor.util.AttributeKey
import moex.MoexIssClient

private val IntegrationsModuleKey = AttributeKey<IntegrationsModule>("IntegrationsModule")

class IntegrationsModule(
    val httpClient: HttpClient,
    val meterRegistry: PrometheusMeterRegistry,
    val moexClient: MoexIssClient,
    val coinGeckoClient: CoinGeckoClient,
    val cbrClient: CbrClient
)

fun Application.integrationsModule(): IntegrationsModule {
    if (attributes.contains(IntegrationsModuleKey)) {
        return attributes[IntegrationsModuleKey]
    }
    val config = environment.config
    val integrationsConfig = config.configOrNull("integrations")
    val appName = integrationsConfig?.propertyOrNull("appName")?.getString() ?: "newsbot/dev"
    val moexBaseUrl = integrationsConfig?.configOrNull("moex")?.propertyOrNull("baseUrl")?.getString()
        ?: "https://iss.moex.com"
    val coinGeckoBaseUrl = integrationsConfig?.configOrNull("coingecko")?.propertyOrNull("baseUrl")?.getString()
        ?: "https://api.coingecko.com"
    val cbrBaseUrl = integrationsConfig?.configOrNull("cbr")?.propertyOrNull("baseUrl")?.getString()
        ?: "https://www.cbr.ru"

    val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT).also {
        it.config().commonTags("component", "integrations")
    }
    val httpClient = defaultHttpClient(appName)

    val module = IntegrationsModule(
        httpClient = httpClient,
        meterRegistry = meterRegistry,
        moexClient = MoexIssClient(httpClient, moexBaseUrl, meterRegistry),
        coinGeckoClient = CoinGeckoClient(httpClient, coinGeckoBaseUrl, meterRegistry),
        cbrClient = CbrClient(httpClient, cbrBaseUrl, meterRegistry)
    )
    attributes.put(IntegrationsModuleKey, module)

    environment.monitor.subscribe(ApplicationStopping) {
        meterRegistry.close()
        httpClient.close()
    }

    return module
}
