package billing.stars

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StarsClientTest {
    @Test
    fun `adds user agent and accept headers`() =
        runBlocking {
            var capturedHeaders: Headers? = null
            val engine =
                MockEngine { request ->
                    capturedHeaders = request.headers
                    respond(
                        content = """{"ok":true,"result":{"amount":1}}""",
                        status = HttpStatusCode.OK,
                        headers =
                        HeadersBuilder()
                            .apply {
                                append(
                                    HttpHeaders.ContentType,
                                    "application/json",
                                )
                            }.build(),
                    )
                }

            val client =
                StarsClient(
                    botToken = "test",
                    config =
                    StarsClientConfig(
                        connectTimeoutMs = 1,
                        readTimeoutMs = 1,
                        retryMax = 0,
                        retryBaseDelayMs = 1,
                    ),
                    client =
                    HttpClient(engine) {
                        expectSuccess = false
                        install(ContentNegotiation) { json() }
                        defaultRequest {
                            header(HttpHeaders.UserAgent, "stars-client")
                            header(HttpHeaders.Accept, "application/json")
                        }
                    },
                )

            client.getBotStarAmount()

            val headers = requireNotNull(capturedHeaders)
            assertEquals("stars-client", headers[HttpHeaders.UserAgent])
            assertEquals("application/json", headers[HttpHeaders.Accept])
        }

    @Test
    fun `parses retry-after http date header`() =
        runBlocking {
            val retryDate = ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(5)
            val engine =
                MockEngine { _ ->
                    respond(
                        content = "",
                        status = HttpStatusCode.TooManyRequests,
                        headers =
                        HeadersBuilder()
                            .apply {
                                append(
                                    HttpHeaders.RetryAfter,
                                    DateTimeFormatter.RFC_1123_DATE_TIME.format(retryDate),
                                )
                            }.build(),
                    )
                }

            val client =
                StarsClient(
                    botToken = "test",
                    config =
                    StarsClientConfig(
                        connectTimeoutMs = 1,
                        readTimeoutMs = 1,
                        retryMax = 0,
                        retryBaseDelayMs = 1,
                    ),
                    client =
                    HttpClient(engine) {
                        expectSuccess = false
                        install(ContentNegotiation) { json() }
                    },
                )

            val result = runCatching { client.getBotStarBalance() }
            val error = result.exceptionOrNull() as StarsClientRateLimited
            val retryAfter = error.retryAfterSeconds
            assertTrue(retryAfter != null && retryAfter >= 1)
        }

    @Test
    fun `coerces retry-after past http date to minimum`() =
        runBlocking {
            val retryDate = ZonedDateTime.now(ZoneOffset.UTC).minusSeconds(5)
            val engine =
                MockEngine { _ ->
                    respond(
                        content = "",
                        status = HttpStatusCode.TooManyRequests,
                        headers =
                        HeadersBuilder()
                            .apply {
                                append(
                                    HttpHeaders.RetryAfter,
                                    DateTimeFormatter.RFC_1123_DATE_TIME.format(retryDate),
                                )
                            }.build(),
                    )
                }

            val client =
                StarsClient(
                    botToken = "test",
                    config =
                    StarsClientConfig(
                        connectTimeoutMs = 1,
                        readTimeoutMs = 1,
                        retryMax = 0,
                        retryBaseDelayMs = 1,
                    ),
                    client =
                    HttpClient(engine) {
                        expectSuccess = false
                        install(ContentNegotiation) { json() }
                    },
                )

            val result = runCatching { client.getBotStarBalance() }
            val error = result.exceptionOrNull() as StarsClientRateLimited
            val retryAfter = error.retryAfterSeconds
            assertTrue(retryAfter != null && retryAfter >= 1)
        }

    @Test
    fun `decodes legacy balance payload`() =
        runBlocking {
            val engine =
                MockEngine { _ ->
                    respond(
                        content = "" +
                            """{"ok":true,"result":""" +
                            """{"available_balance":10,"pending_balance":2,"updated_at":123}}""",
                        status = HttpStatusCode.OK,
                        headers =
                        HeadersBuilder()
                            .apply {
                                append(
                                    HttpHeaders.ContentType,
                                    "application/json",
                                )
                            }.build(),
                    )
                }
            val client =
                StarsClient(
                    botToken = "test",
                    config =
                    StarsClientConfig(
                        connectTimeoutMs = 1,
                        readTimeoutMs = 1,
                        retryMax = 0,
                        retryBaseDelayMs = 1,
                    ),
                    client =
                    HttpClient(engine) {
                        expectSuccess = false
                        install(ContentNegotiation) { json() }
                    },
                )

            val balance = client.getBotStarBalance()
            assertEquals(10, balance.available)
            assertEquals(2, balance.pending)
            assertEquals(123, balance.updatedAtEpochSeconds)

            val amount = client.getBotStarAmount()
            assertEquals(10, amount.amount)
        }

    @Test
    fun `decodes new star amount payload`() =
        runBlocking {
            val engine =
                MockEngine { _ ->
                    respond(
                        content = """{"ok":true,"result":{"amount":321,"nanostar_amount":5}}""",
                        status = HttpStatusCode.OK,
                        headers =
                        HeadersBuilder()
                            .apply {
                                append(
                                    HttpHeaders.ContentType,
                                    "application/json",
                                )
                            }.build(),
                    )
                }
            val client =
                StarsClient(
                    botToken = "test",
                    config =
                    StarsClientConfig(
                        connectTimeoutMs = 1,
                        readTimeoutMs = 1,
                        retryMax = 0,
                        retryBaseDelayMs = 1,
                    ),
                    client =
                    HttpClient(engine) {
                        expectSuccess = false
                        install(ContentNegotiation) { json() }
                    },
                )

            val amount = client.getBotStarAmount()
            assertEquals(321, amount.amount)
            assertEquals(5, amount.nano)

            val balance = client.getBotStarBalance()
            assertEquals(321, balance.available)
        }

    @Test
    fun `ok false rate limited maps to typed error with parameters`() =
        runBlocking {
            val engine =
                MockEngine { _ ->
                    respond(
                        content = """{"ok":false,"error_code":429,"parameters":{"retry_after":7}}""",
                        status = HttpStatusCode.OK,
                        headers =
                        HeadersBuilder()
                            .apply {
                                append(
                                    HttpHeaders.ContentType,
                                    "application/json",
                                )
                            }.build(),
                    )
                }
            val client = clientWithRetry(engine)

            val result = runCatching { client.getBotStarAmount() }
            val error = result.exceptionOrNull() as StarsClientRateLimited
            assertEquals(7, error.retryAfterSeconds)
        }

    @Test
    fun `ok false rate limited falls back to header`() =
        runBlocking {
            val engine =
                MockEngine { _ ->
                    respond(
                        content = """{"ok":false,"error_code":429}""",
                        status = HttpStatusCode.OK,
                        headers =
                        HeadersBuilder()
                            .apply {
                                append(HttpHeaders.ContentType, "application/json")
                                append(HttpHeaders.RetryAfter, "3")
                            }.build(),
                    )
                }
            val client = clientWithRetry(engine)

            val result = runCatching { client.getBotStarAmount() }
            val error = result.exceptionOrNull() as StarsClientRateLimited
            assertEquals(3, error.retryAfterSeconds)
        }

    @Test
    fun `ok false bad request maps to typed error`() =
        runBlocking {
            val engine =
                MockEngine { _ ->
                    respond(
                        content = """{"ok":false,"error_code":400}""",
                        status = HttpStatusCode.OK,
                        headers =
                        HeadersBuilder()
                            .apply {
                                append(
                                    HttpHeaders.ContentType,
                                    "application/json",
                                )
                            }.build(),
                    )
                }
            val client = clientWithRetry(engine)

            val result = runCatching { client.getBotStarAmount() }
            assertTrue(result.exceptionOrNull() is StarsClientBadRequest)
        }

    @Test
    fun `ok false server error maps to typed error`() =
        runBlocking {
            val engine =
                MockEngine { _ ->
                    respond(
                        content = """{"ok":false,"error_code":500}""",
                        status = HttpStatusCode.OK,
                        headers =
                        HeadersBuilder()
                            .apply {
                                append(
                                    HttpHeaders.ContentType,
                                    "application/json",
                                )
                            }.build(),
                    )
                }
            val client = clientWithRetry(engine)

            val result = runCatching { client.getBotStarAmount() }
            assertTrue(result.exceptionOrNull() is StarsClientServerError)
        }

    @Test
    fun `429 without retry after is not retried`() =
        runBlocking {
            var calls = 0
            val engine =
                MockEngine { _ ->
                    calls += 1
                    respond(
                        content = "",
                        status = HttpStatusCode.TooManyRequests,
                    )
                }
            val client = clientWithRetry(engine, retryMax = 2)

            val result = runCatching { client.getBotStarAmount() }
            assertTrue(result.exceptionOrNull() is StarsClientRateLimited)
            assertEquals(1, calls)
        }

    private fun clientWithRetry(
        engine: MockEngine,
        retryMax: Int = 0,
    ): StarsClient =
        StarsClient(
            botToken = "test",
            config =
            StarsClientConfig(
                connectTimeoutMs = 1,
                readTimeoutMs = 1,
                retryMax = retryMax,
                retryBaseDelayMs = 1,
            ),
            client =
            HttpClient(engine) {
                expectSuccess = false
                install(ContentNegotiation) { json() }
                install(HttpRequestRetry) {
                    maxRetries = retryMax
                    retryIf { _, response ->
                        when {
                            response.status == HttpStatusCode.TooManyRequests ->
                                parseRetryAfter(response.headers[HttpHeaders.RetryAfter]) != null
                            response.status.value >= 500 -> true
                            else -> false
                        }
                    }
                    delayMillis(respectRetryAfterHeader = true) { attempt -> exponentialDelay(attempt) }
                }
            },
        )

    private fun parseRetryAfter(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        raw.toLongOrNull()?.let { return it.coerceAtLeast(1) }
        return runCatching {
            val date = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            val now = Instant.now()
            val diff = Duration.between(now, date).seconds
            diff.coerceAtLeast(1)
        }.getOrNull()
    }

    private fun exponentialDelay(attempt: Int): Long {
        val cappedAttempt = attempt.coerceAtLeast(0)
        val multiplier = 1L shl cappedAttempt
        val maxDelay = 1L * 10
        val baseDelay = maxOf(1L, minOf(1L * multiplier, maxDelay))
        val jitter = kotlin.random.Random.nextLong(baseDelay / 10 + 1)
        return baseDelay + jitter
    }
}
