package coingecko

import http.CircuitBreaker
import http.IntegrationsHttpConfig
import http.IntegrationsMetrics
import http.HttpClientError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.math.BigDecimal
import java.time.Clock
import kotlin.time.Duration
import testutils.testHttpClient
import testutils.testHttpConfig

private const val BASE_URL = "https://mock.coingecko"

class CoinGeckoClientTest : FunSpec({
    test("getSimplePrice returns parsed prices") {
        val registry = SimpleMeterRegistry()
        val metrics = IntegrationsMetrics(registry)
        val clock = Clock.systemUTC()
        val config = testHttpConfig()
        val json = """{"bitcoin":{"usd": "100000.0", "rub": "9000000.0"}}""".trimIndent()
        var callCount = 0
        val (client, cgClient) = newCoinGeckoClient(metrics, clock, config) { _ ->
            callCount++
            respond(
                content = json,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
            )
        }
        try {
            val result = cgClient.getSimplePrice(listOf("bitcoin"), listOf("usd", "rub"))
            result.isSuccess shouldBe true
            val prices = result.getOrThrow()
            prices.shouldContainKey("bitcoin")
            prices.getValue("bitcoin").getValue("usd") shouldBe BigDecimal("100000.0")
            callCount shouldBe 1
        } finally {
            client.close()
            registry.close()
        }
    }

    test("getSimplePrice retries when rate limited") {
        val registry = SimpleMeterRegistry()
        val metrics = IntegrationsMetrics(registry)
        val clock = Clock.systemUTC()
        val config = testHttpConfig()
        val json = """{"bitcoin":{"usd":"1"}}"""
        var callCount = 0
        val (client, cgClient) = newCoinGeckoClient(metrics, clock, config) { _ ->
            callCount++
            if (callCount < 3) {
                respond(
                    content = "{}",
                    status = HttpStatusCode.TooManyRequests,
                    headers = headersOf(
                        HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                        HttpHeaders.RetryAfter to listOf("0")
                    )
                )
            } else {
                respond(
                    content = json,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
                )
            }
        }
        try {
            val result = cgClient.getSimplePrice(listOf("bitcoin"), listOf("usd"))
            result.isSuccess shouldBe true
            callCount shouldBe 3
        } finally {
            client.close()
            registry.close()
        }
    }

    test("getSimplePrice retries on server errors") {
        val registry = SimpleMeterRegistry()
        val metrics = IntegrationsMetrics(registry)
        val clock = Clock.systemUTC()
        val config = testHttpConfig()
        val json = """{"bitcoin":{"usd":"1"}}"""
        var callCount = 0
        val (client, cgClient) = newCoinGeckoClient(metrics, clock, config) { _ ->
            callCount++
            if (callCount < 3) {
                respond(
                    content = "{}",
                    status = HttpStatusCode.BadGateway,
                    headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
                )
            } else {
                respond(
                    content = json,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
                )
            }
        }
        try {
            val result = cgClient.getSimplePrice(listOf("bitcoin"), listOf("usd"))
            result.isSuccess shouldBe true
            callCount shouldBe 3
        } finally {
            client.close()
            registry.close()
        }
    }

    test("getSimplePrice uses cache for repeated requests") {
        val registry = SimpleMeterRegistry()
        val metrics = IntegrationsMetrics(registry)
        val clock = Clock.systemUTC()
        val config = testHttpConfig()
        val json = """{"bitcoin":{"usd":"1"}}"""
        var callCount = 0
        val (client, cgClient) = newCoinGeckoClient(metrics, clock, config) { _ ->
            callCount++
            respond(
                content = json,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
            )
        }
        try {
            val first = cgClient.getSimplePrice(listOf("bitcoin"), listOf("usd"))
            val second = cgClient.getSimplePrice(listOf("bitcoin"), listOf("usd"))
            first.isSuccess shouldBe true
            second.isSuccess shouldBe true
            callCount shouldBe 1
        } finally {
            client.close()
            registry.close()
        }
    }

    test("getSimplePrice fails for malformed payload") {
        val registry = SimpleMeterRegistry()
        val metrics = IntegrationsMetrics(registry)
        val clock = Clock.systemUTC()
        val config = testHttpConfig()
        val (client, cgClient) = newCoinGeckoClient(metrics, clock, config) { _ ->
            respond(
                content = "[]",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
            )
        }
        try {
            val result = cgClient.getSimplePrice(listOf("bitcoin"), listOf("usd"))
            result.isSuccess shouldBe false
            result.exceptionOrNull().shouldBeInstanceOf<HttpClientError.DeserializationError>()
        } finally {
            client.close()
            registry.close()
        }
    }

    test("getSimplePrice rejects invalid arguments") {
        val registry = SimpleMeterRegistry()
        val metrics = IntegrationsMetrics(registry)
        val clock = Clock.systemUTC()
        val config = testHttpConfig()
        val (client, cgClient) = newCoinGeckoClient(metrics, clock, config) { error("should not be called") }
        try {
            val result = cgClient.getSimplePrice(emptyList(), listOf("usd"))
            result.isSuccess shouldBe false
            result.exceptionOrNull().shouldBeInstanceOf<HttpClientError.ValidationError>()
        } finally {
            client.close()
            registry.close()
        }
    }

    test("getMarketChart parses chart data") {
        val registry = SimpleMeterRegistry()
        val metrics = IntegrationsMetrics(registry)
        val clock = Clock.systemUTC()
        val config = testHttpConfig()
        val json = """
            {
              "prices": [["1716200000000","100.0"]],
              "market_caps": [["1716200000000","200.0"]],
              "total_volumes": [["1716200000000","300.0"]]
            }
        """.trimIndent()
        val (client, cgClient) = newCoinGeckoClient(metrics, clock, config) { _ ->
            respond(
                content = json,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
            )
        }
        try {
            val result = cgClient.getMarketChart("bitcoin", "usd", 30)
            result.isSuccess shouldBe true
            result.getOrThrow().prices.first().value shouldBe BigDecimal("100.0")
        } finally {
            client.close()
            registry.close()
        }
    }
})

private fun newCoinGeckoClient(
    metrics: IntegrationsMetrics,
    clock: Clock,
    config: IntegrationsHttpConfig,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
): Pair<HttpClient, CoinGeckoClient> {
    val client = testHttpClient(metrics = metrics, clock = clock, config = config, handler = handler)
    val breaker = CircuitBreaker("coingecko", config.circuitBreaker, metrics, clock)
    val cgClient = CoinGeckoClient(
        client = client,
        cb = breaker,
        metrics = metrics,
        clock = clock,
        minRequestInterval = Duration.ZERO
    ).apply { setBaseUrl(BASE_URL) }
    return client to cgClient
}
