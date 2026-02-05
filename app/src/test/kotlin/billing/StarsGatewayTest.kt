package billing

import billing.model.Tier
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.IOException
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StarsGatewayTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `createInvoiceLink success`() =
        runTest {
            var capturedRequest: HttpRequestData? = null
            val client =
                testClient { request ->
                    capturedRequest = request
                    respond(
                        content = """{"ok":true,"result":"https://t.me/pay/123"}""",
                        status = HttpStatusCode.OK,
                        headers =
                        Headers.build {
                            append(HttpHeaders.ContentType, "application/json")
                        },
                    )
                }
            val gateway = TelegramStarsGateway(botToken = "TEST_TOKEN", client = client)

            val result = gateway.createInvoiceLink(Tier.PRO, 150, "payload-1")

            assertTrue(result.isSuccess)
            assertEquals("https://t.me/pay/123", result.getOrNull())
            val request = requireNotNull(capturedRequest)
            assertEquals(HttpMethod.Post, request.method)
            assertTrue(request.url.encodedPath.endsWith("/createInvoiceLink"))
            val body = request.body as FormDataContent
            val parameters = body.formData
            assertEquals("Subscription: PRO", parameters["title"])
            assertEquals("Subscription tier PRO", parameters["description"])
            assertEquals("payload-1", parameters["payload"])
            assertEquals("XTR", parameters["currency"])
            val prices = parameters["prices"]
            assertNotNull(prices)
            val parsedPrices = json.parseToJsonElement(prices) as JsonArray
            val priceObject = parsedPrices.first().jsonObject
            assertEquals("PRO", priceObject["label"]?.jsonPrimitive?.content)
            assertEquals(150L, priceObject["amount"]?.jsonPrimitive?.long)
        }

    @Test
    fun `createInvoiceLink returns failure on telegram error`() =
        runTest {
            val client =
                testClient {
                    respond(
                        content = """{"ok":false,"description":"BAD REQUEST: invalid"}""",
                        status = HttpStatusCode.OK,
                        headers =
                        Headers.build {
                            append(HttpHeaders.ContentType, "application/json")
                        },
                    )
                }
            val gateway = TelegramStarsGateway(botToken = "TEST_TOKEN", client = client)

            val result = gateway.createInvoiceLink(Tier.PRO, 100, "payload")

            assertTrue(result.isFailure)
            val exception = result.exceptionOrNull()
            assertNotNull(exception)
            assertTrue(exception.message?.contains("Telegram: BAD REQUEST") == true)
        }

    @Test
    fun `createInvoiceLink retries on 429 with retry-after`() =
        runTest {
            val attempts = AtomicInteger(0)
            val client =
                testClient { request ->
                    val currentAttempt = attempts.incrementAndGet()
                    if (currentAttempt == 1) {
                        respond(
                            content = "Too Many Requests",
                            status = HttpStatusCode.TooManyRequests,
                            headers =
                            Headers.build {
                                append(HttpHeaders.RetryAfter, "1")
                            },
                        )
                    } else {
                        respond(
                            content = """{"ok":true,"result":"https://t.me/pay/retry"}""",
                            status = HttpStatusCode.OK,
                            headers =
                            Headers.build {
                                append(HttpHeaders.ContentType, "application/json")
                            },
                        )
                    }
                }
            val gateway = TelegramStarsGateway(botToken = "TEST_TOKEN", client = client)

            val result = gateway.createInvoiceLink(Tier.PRO_PLUS, 250, "payload")

            assertTrue(result.isSuccess)
            assertEquals("https://t.me/pay/retry", result.getOrNull())
            assertEquals(2, attempts.get())
        }

    @Test
    fun `createInvoiceLink fails after retries on server error`() =
        runTest {
            val attempts = AtomicInteger(0)
            val client =
                testClient {
                    val currentAttempt = attempts.incrementAndGet()
                    val status =
                        if (currentAttempt == 1) {
                            HttpStatusCode.BadGateway
                        } else {
                            HttpStatusCode.InternalServerError
                        }
                    respond(
                        content = """{"ok":false,"description":"Server error"}""",
                        status = status,
                        headers =
                        Headers.build {
                            append(HttpHeaders.ContentType, "application/json")
                        },
                    )
                }
            val gateway = TelegramStarsGateway(botToken = "TEST_TOKEN", client = client)

            val result = gateway.createInvoiceLink(Tier.VIP, 500, "payload")

            assertTrue(result.isFailure)
            assertTrue(attempts.get() > 1)
        }

    @Test
    fun `createInvoiceLink handles network exception`() =
        runTest {
            val client =
                testClient {
                    throw IOException("network down")
                }
            val gateway = TelegramStarsGateway(botToken = "TEST_TOKEN", client = client)

            val result = gateway.createInvoiceLink(Tier.PRO, 100, "payload")

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IOException)
        }

    @Test
    fun `createInvoiceLink validates input`() =
        runTest {
            val gateway =
                TelegramStarsGateway(
                    botToken = "TEST_TOKEN",
                    client = testClient { error("should not call") },
                )

            assertFailsWith<IllegalArgumentException> {
                gateway.createInvoiceLink(Tier.PRO, -1, "payload")
            }
            val longPayload = "a".repeat(65)
            assertFailsWith<IllegalArgumentException> {
                gateway.createInvoiceLink(Tier.PRO, 100, longPayload)
            }
        }

    private fun testClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
        HttpClient(MockEngine) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = Duration.ofSeconds(1).toMillis()
                requestTimeoutMillis = Duration.ofSeconds(5).toMillis()
                socketTimeoutMillis = Duration.ofSeconds(5).toMillis()
            }
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpRequestRetry) {
                maxRetries = 3
                retryIf { _, response ->
                    response.status == HttpStatusCode.TooManyRequests || response.status.value >= 500
                }
                retryOnExceptionIf { _, cause -> cause is IOException }
                delayMillis(respectRetryAfterHeader = true) { attempt ->
                    val baseDelay = 300L
                    val maxDelay = 2000L
                    val multiplier = 1L shl attempt
                    val computed = baseDelay * multiplier
                    kotlin.math.min(computed, maxDelay)
                }
            }
            engine {
                addHandler(handler)
            }
        }
}
