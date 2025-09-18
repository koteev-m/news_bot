package coingecko

import http.HttpClientError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.math.BigDecimal
import testutils.testHttpClient

private const val BASE_URL = "https://mock.coingecko"

class CoinGeckoClientTest : FunSpec({
    test("getSimplePrice returns parsed prices") {
        val registry = SimpleMeterRegistry()
        val json = """
            {"bitcoin":{"usd": "100000.0", "rub": "9000000.0"}}
        """.trimIndent()
        var callCount = 0
        val client = testHttpClient { _ ->
            callCount++
            respond(
                content = json,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
            )
        }
        val coinGeckoClient = CoinGeckoClient(client, BASE_URL, registry)
        try {
            val result = coinGeckoClient.getSimplePrice(listOf("bitcoin"), listOf("usd", "rub"))
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
        val json = """{"bitcoin":{"usd":"1"}}"""
        var callCount = 0
        val client = testHttpClient { _ ->
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
        val coinGeckoClient = CoinGeckoClient(client, BASE_URL, registry)
        try {
            val result = coinGeckoClient.getSimplePrice(listOf("bitcoin"), listOf("usd"))
            result.isSuccess shouldBe true
            callCount shouldBe 3
        } finally {
            client.close()
            registry.close()
        }
    }

    test("getSimplePrice retries on server errors") {
        val registry = SimpleMeterRegistry()
        val json = """{"bitcoin":{"usd":"1"}}"""
        var callCount = 0
        val client = testHttpClient { _ ->
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
        val coinGeckoClient = CoinGeckoClient(client, BASE_URL, registry)
        try {
            val result = coinGeckoClient.getSimplePrice(listOf("bitcoin"), listOf("usd"))
            result.isSuccess shouldBe true
            callCount shouldBe 3
        } finally {
            client.close()
            registry.close()
        }
    }

    test("getSimplePrice uses cache for repeated requests") {
        val registry = SimpleMeterRegistry()
        val json = """{"bitcoin":{"usd":"1"}}"""
        var callCount = 0
        val client = testHttpClient { _ ->
            callCount++
            respond(
                content = json,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
            )
        }
        val coinGeckoClient = CoinGeckoClient(client, BASE_URL, registry)
        try {
            val first = coinGeckoClient.getSimplePrice(listOf("bitcoin"), listOf("usd"))
            val second = coinGeckoClient.getSimplePrice(listOf("bitcoin"), listOf("usd"))
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
        val client = testHttpClient { _ ->
            respond(
                content = "[]",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
            )
        }
        val coinGeckoClient = CoinGeckoClient(client, BASE_URL, registry)
        try {
            val result = coinGeckoClient.getSimplePrice(listOf("bitcoin"), listOf("usd"))
            result.isSuccess shouldBe false
            result.exceptionOrNull().shouldBeInstanceOf<HttpClientError.DeserializationError>()
        } finally {
            client.close()
            registry.close()
        }
    }

    test("getSimplePrice rejects invalid arguments") {
        val registry = SimpleMeterRegistry()
        val client = testHttpClient { error("should not be called") }
        val coinGeckoClient = CoinGeckoClient(client, BASE_URL, registry)
        try {
            val result = coinGeckoClient.getSimplePrice(emptyList(), listOf("usd"))
            result.isSuccess shouldBe false
            result.exceptionOrNull().shouldBeInstanceOf<HttpClientError.ValidationError>()
        } finally {
            client.close()
            registry.close()
        }
    }

    test("getMarketChart parses chart data") {
        val registry = SimpleMeterRegistry()
        val json = """
            {
              "prices": [["1716200000000","100.0"]],
              "market_caps": [["1716200000000","200.0"]],
              "total_volumes": [["1716200000000","300.0"]]
            }
        """.trimIndent()
        val client = testHttpClient { _ ->
            respond(
                content = json,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
            )
        }
        val coinGeckoClient = CoinGeckoClient(client, BASE_URL, registry)
        try {
            val result = coinGeckoClient.getMarketChart("bitcoin", "usd", 30)
            result.isSuccess shouldBe true
            result.getOrThrow().prices.first().value shouldBe BigDecimal("100.0")
        } finally {
            client.close()
            registry.close()
        }
    }
})
