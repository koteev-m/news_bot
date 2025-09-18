package moex

import http.HttpClientError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.math.BigDecimal
import java.time.LocalDate
import testutils.testHttpClient

private const val BASE_URL = "https://mock.moex"

class MoexIssClientTest : FunSpec({
    test("getSecuritiesTqbr returns parsed securities") {
        val registry = SimpleMeterRegistry()
        val responseJson = """
            {
              "securities": {
                "columns": ["SECID","SHORTNAME","BOARDID","LOTSIZE","FACEUNIT","PREVPRICE","LAST","SECTYPE","ISSUESIZEPLACED"],
                "data": [
                  ["SBER","Sberbank","TQBR","10","RUB","250.10","251.00","share","1"],
                  ["GAZP","Gazprom","TQBR","10","RUB","150.00","151.50","share","0"]
                ]
              }
            }
        """.trimIndent()
        var callCount = 0
        val client = testHttpClient { _ ->
            callCount++
            respond(
                content = responseJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
            )
        }
        val moexClient = MoexIssClient(client, BASE_URL, registry)
        try {
            val result = moexClient.getSecuritiesTqbr(listOf("SBER", "GAZP"))
            result.isSuccess shouldBe true
            val securities = result.getOrThrow().securities
            securities.shouldHaveSize(2)
            securities.first { it.code == "SBER" }.marketPrice shouldBe BigDecimal("251.00")
            callCount shouldBe 1
        } finally {
            client.close()
            registry.close()
        }
    }

    test("getSecuritiesTqbr retries on 429 responses") {
        val registry = SimpleMeterRegistry()
        val successJson = """
            {"securities":{"columns":["SECID"],"data":[["SBER"]]}}
        """.trimIndent()
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
                    content = successJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
                )
            }
        }
        val moexClient = MoexIssClient(client, BASE_URL, registry)
        try {
            val result = moexClient.getSecuritiesTqbr(listOf("SBER"))
            result.isSuccess shouldBe true
            callCount shouldBe 3
        } finally {
            client.close()
            registry.close()
        }
    }

    test("getCandlesDaily retries on server errors") {
        val registry = SimpleMeterRegistry()
        val candlesJson = """
            {"candles":{"columns":["OPEN","CLOSE","HIGH","LOW","VALUE","VOLUME","BEGIN","END"],
            "data":[["10.00","10.50","10.75","9.90","1000","5000","2024-05-20 10:00:00","2024-05-20 18:40:00"]]}}
        """.trimIndent()
        var callCount = 0
        val client = testHttpClient { _ ->
            callCount++
            if (callCount < 3) {
                respond(
                    content = "{}",
                    status = HttpStatusCode.InternalServerError,
                    headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
                )
            } else {
                respond(
                    content = candlesJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
                )
            }
        }
        val moexClient = MoexIssClient(client, BASE_URL, registry)
        try {
            val result = moexClient.getCandlesDaily("SBER", LocalDate.parse("2024-05-20"), LocalDate.parse("2024-05-21"))
            result.isSuccess shouldBe true
            result.getOrThrow().candles.first().open shouldBe BigDecimal("10.00")
            callCount shouldBe 3
        } finally {
            client.close()
            registry.close()
        }
    }

    test("getMarketStatus uses cache between calls") {
        val registry = SimpleMeterRegistry()
        val statusJson = """
            {"marketstatus":{"columns":["BOARDID","STATE"],"data":[["TQBR","OPEN"]]}}
        """.trimIndent()
        var callCount = 0
        val client = testHttpClient { _ ->
            callCount++
            respond(
                content = statusJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
            )
        }
        val moexClient = MoexIssClient(client, BASE_URL, registry)
        try {
            val first = moexClient.getMarketStatus()
            val second = moexClient.getMarketStatus()
            first.isSuccess shouldBe true
            second.isSuccess shouldBe true
            callCount shouldBe 1
        } finally {
            client.close()
            registry.close()
        }
    }

    test("getSecuritiesTqbr fails on malformed payload") {
        val registry = SimpleMeterRegistry()
        val malformedJson = """
            {"securities":{"columns":["SHORTNAME"],"data":[["Broken"]]}}
        """.trimIndent()
        val client = testHttpClient { _ ->
            respond(
                content = malformedJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
            )
        }
        val moexClient = MoexIssClient(client, BASE_URL, registry)
        try {
            val result = moexClient.getSecuritiesTqbr(listOf("SBER"))
            result.isSuccess shouldBe false
            result.exceptionOrNull().shouldBeInstanceOf<HttpClientError.DeserializationError>()
        } finally {
            client.close()
            registry.close()
        }
    }
})
