package cbr

import http.CircuitBreaker
import http.IntegrationsHttpConfig
import http.IntegrationsMetrics
import http.HttpClientError
import io.kotest.core.spec.style.FunSpec
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
import java.time.LocalDate
import testutils.testHttpClient
import testutils.testHttpConfig

private const val BASE_URL = "https://mock.cbr"

class CbrClientTest : FunSpec({
    test("getXmlDaily parses XML response") {
        val registry = SimpleMeterRegistry()
        val metrics = IntegrationsMetrics(registry)
        val clock = Clock.systemUTC()
        val config = testHttpConfig()
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ValCurs Date="20.05.2024" name="Foreign Currency Market">
              <Valute ID="R01235">
                <CharCode>USD</CharCode>
                <Nominal>1</Nominal>
                <Value>90,1234</Value>
              </Valute>
            </ValCurs>
        """.trimIndent()
        var callCount = 0
        val (client, cbrClient) = newCbrClient(metrics, clock, config) { _ ->
            callCount++
            respond(
                content = xml,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Xml.toString()))
            )
        }
        try {
            val result = cbrClient.getXmlDaily(LocalDate.parse("2024-05-20"))
            result.isSuccess shouldBe true
            val rates = result.getOrThrow()
            rates.first().currencyCode shouldBe "USD"
            rates.first().rateRub shouldBe BigDecimal("90.12340000")
            callCount shouldBe 1
        } finally {
            client.close()
            registry.close()
        }
    }

    test("getXmlDaily retries on 429 responses") {
        val registry = SimpleMeterRegistry()
        val metrics = IntegrationsMetrics(registry)
        val clock = Clock.systemUTC()
        val config = testHttpConfig()
        val xml = """
            <ValCurs Date="20.05.2024">
              <Valute><CharCode>USD</CharCode><Nominal>1</Nominal><Value>90,00</Value></Valute>
            </ValCurs>
        """.trimIndent()
        var callCount = 0
        val (client, cbrClient) = newCbrClient(metrics, clock, config) { _ ->
            callCount++
            if (callCount < 3) {
                respond(
                    content = "",
                    status = HttpStatusCode.TooManyRequests,
                    headers = headersOf(
                        HttpHeaders.ContentType to listOf(ContentType.Application.Xml.toString()),
                        HttpHeaders.RetryAfter to listOf("0")
                    )
                )
            } else {
                respond(
                    content = xml,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Xml.toString()))
                )
            }
        }
        try {
            val result = cbrClient.getXmlDaily(null)
            result.isSuccess shouldBe true
            callCount shouldBe 3
        } finally {
            client.close()
            registry.close()
        }
    }

    test("getXmlDaily retries on server errors") {
        val registry = SimpleMeterRegistry()
        val metrics = IntegrationsMetrics(registry)
        val clock = Clock.systemUTC()
        val config = testHttpConfig()
        val xml = """
            <ValCurs Date="20.05.2024">
              <Valute><CharCode>USD</CharCode><Nominal>1</Nominal><Value>90,00</Value></Valute>
            </ValCurs>
        """.trimIndent()
        var callCount = 0
        val (client, cbrClient) = newCbrClient(metrics, clock, config) { _ ->
            callCount++
            if (callCount < 3) {
                respond(
                    content = "",
                    status = HttpStatusCode.BadGateway,
                    headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Xml.toString()))
                )
            } else {
                respond(
                    content = xml,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Xml.toString()))
                )
            }
        }
        try {
            val result = cbrClient.getXmlDaily(null)
            result.isSuccess shouldBe true
            callCount shouldBe 3
        } finally {
            client.close()
            registry.close()
        }
    }

    test("getXmlDaily caches latest response") {
        val registry = SimpleMeterRegistry()
        val metrics = IntegrationsMetrics(registry)
        val clock = Clock.systemUTC()
        val config = testHttpConfig()
        val xml = """
            <ValCurs Date="20.05.2024">
              <Valute><CharCode>USD</CharCode><Nominal>1</Nominal><Value>90,00</Value></Valute>
            </ValCurs>
        """.trimIndent()
        var callCount = 0
        val (client, cbrClient) = newCbrClient(metrics, clock, config) { _ ->
            callCount++
            respond(
                content = xml,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Xml.toString()))
            )
        }
        try {
            val first = cbrClient.getXmlDaily(null)
            val second = cbrClient.getXmlDaily(null)
            first.isSuccess shouldBe true
            second.isSuccess shouldBe true
            callCount shouldBe 1
        } finally {
            client.close()
            registry.close()
        }
    }

    test("getXmlDaily fails on malformed XML") {
        val registry = SimpleMeterRegistry()
        val metrics = IntegrationsMetrics(registry)
        val clock = Clock.systemUTC()
        val config = testHttpConfig()
        val xml = """
            <ValCurs Date="20.05.2024">
              <Valute><CharCode>USD</CharCode><Nominal>0</Nominal><Value>text</Value></Valute>
            </ValCurs>
        """.trimIndent()
        val (client, cbrClient) = newCbrClient(metrics, clock, config) { _ ->
            respond(
                content = xml,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Xml.toString()))
            )
        }
        try {
            val result = cbrClient.getXmlDaily(null)
            result.isSuccess shouldBe false
            result.exceptionOrNull().shouldBeInstanceOf<HttpClientError.DeserializationError>()
        } finally {
            client.close()
            registry.close()
        }
    }
})

private fun newCbrClient(
    metrics: IntegrationsMetrics,
    clock: Clock,
    config: IntegrationsHttpConfig,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
): Pair<HttpClient, CbrClient> {
    val client = testHttpClient(metrics = metrics, clock = clock, config = config, handler = handler)
    val breaker = CircuitBreaker("cbr", config.circuitBreaker, metrics, clock)
    val cbrClient = CbrClient(client, breaker, metrics, clock).apply { setBaseUrl(BASE_URL) }
    return client to cbrClient
}
