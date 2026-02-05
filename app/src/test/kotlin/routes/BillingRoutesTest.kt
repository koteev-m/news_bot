package routes

import billing.model.BillingPlan
import billing.model.SubStatus
import billing.model.Tier
import billing.model.UserSubscription
import billing.model.Xtr
import billing.service.BillingService
import billing.service.EntitlementsService
import billing.stars.BotBalanceRateLimiter
import billing.stars.BotStarBalance
import billing.stars.BotStarBalancePort
import billing.stars.BotStarBalanceResult
import billing.stars.CacheState
import billing.stars.InMemoryStarBalancePort
import billing.stars.StarBalancePort
import billing.stars.StarsAdminResults
import billing.stars.StarsClient
import billing.stars.StarsClientBadRequest
import billing.stars.StarsClientConfig
import billing.stars.StarsClientDecodeError
import billing.stars.StarsClientRateLimited
import billing.stars.StarsClientServerError
import billing.stars.StarsHeaders
import billing.stars.StarsMetrics
import billing.stars.StarsOutcomes
import billing.stars.StarsPublicResults
import billing.stars.StarsService
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import errors.installErrorPages
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BillingRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `GET plans returns sorted plans`() =
        testApplication {
            val service =
                FakeBillingService().apply {
                    listPlansResult =
                        Result.success(
                            listOf(
                                BillingPlan(Tier.VIP, "VIP", Xtr(5500), isActive = true),
                                BillingPlan(Tier.PRO, "Pro", Xtr(1500), isActive = true),
                            ),
                        )
                }
            configure(service)

            val response = client.get("/api/billing/plans")

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.parseToJsonElement(response.bodyAsText())
            assertTrue(payload is JsonArray)
            val tiers = payload.jsonArray.map { element -> element.jsonObject["tier"]!!.jsonPrimitive.content }
            assertEquals(listOf("VIP", "PRO"), tiers)
        }

    @Test
    fun `POST invoice requires authentication`() =
        testApplication {
            configure(FakeBillingService())

            val response =
                client.post("/api/billing/stars/invoice") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"tier":"PRO"}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `POST invoice validates tier`() =
        testApplication {
            configure(FakeBillingService())

            val response =
                client.post("/api/billing/stars/invoice") {
                    header(HttpHeaders.Authorization, bearerFor("42"))
                    contentType(ContentType.Application.Json)
                    setBody("""{"tier":"FOO"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val payload = HttpErrorResponse(response.bodyAsText())
            assertEquals("bad_request", payload.error)
        }

    @Test
    fun `POST invoice returns link`() =
        testApplication {
            val service =
                FakeBillingService().apply {
                    createInvoiceResult = Result.success("https://t.me/pay/invoice")
                }
            configure(service)

            val response =
                client.post("/api/billing/stars/invoice") {
                    header(HttpHeaders.Authorization, bearerFor("99"))
                    contentType(ContentType.Application.Json)
                    setBody("""{"tier":"PRO"}""")
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val body = json.parseToJsonElement(response.bodyAsText())
            assertEquals("https://t.me/pay/invoice", body.jsonObject["invoiceLink"]?.jsonPrimitive?.content)
        }

    @Test
    fun `POST invoice handles missing plan`() =
        testApplication {
            val service =
                FakeBillingService().apply {
                    createInvoiceResult = Result.failure(NoSuchElementException("plan not found"))
                }
            configure(service)

            val response =
                client.post("/api/billing/stars/invoice") {
                    header(HttpHeaders.Authorization, bearerFor("50"))
                    contentType(ContentType.Application.Json)
                    setBody("""{"tier":"PRO"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `POST invoice propagates internal errors`() =
        testApplication {
            val service =
                FakeBillingService().apply {
                    createInvoiceResult = Result.failure(RuntimeException("boom"))
                }
            configure(service)

            val response =
                client.post("/api/billing/stars/invoice") {
                    header(HttpHeaders.Authorization, bearerFor("51"))
                    contentType(ContentType.Application.Json)
                    setBody("""{"tier":"PRO"}""")
                }

            assertEquals(HttpStatusCode.InternalServerError, response.status)
        }

    @Test
    fun `GET my subscription requires authentication`() =
        testApplication {
            configure(FakeBillingService())

            val response = client.get("/api/billing/stars/me")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `GET my subscription returns subscription`() =
        testApplication {
            val service =
                FakeBillingService().apply {
                    getMySubscriptionResult =
                        Result.success(
                            UserSubscription(
                                userId = 77L,
                                tier = Tier.PRO_PLUS,
                                status = SubStatus.ACTIVE,
                                startedAt = java.time.Instant.parse("2024-01-01T00:00:00Z"),
                                expiresAt = java.time.Instant.parse("2024-02-01T00:00:00Z"),
                            ),
                        )
                }
            configure(service)

            val response =
                client.get("/api/billing/stars/me") {
                    header(HttpHeaders.Authorization, bearerFor("77"))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.parseToJsonElement(response.bodyAsText())
            assertEquals("PRO_PLUS", body.jsonObject["tier"]?.jsonPrimitive?.content)
            assertEquals("ACTIVE", body.jsonObject["status"]?.jsonPrimitive?.content)
        }

    @Test
    fun `GET my subscription falls back to free`() =
        testApplication {
            val service =
                FakeBillingService().apply {
                    getMySubscriptionResult = Result.success(null)
                }
            configure(service)

            val response =
                client.get("/api/billing/stars/me") {
                    header(HttpHeaders.Authorization, bearerFor("70"))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.parseToJsonElement(response.bodyAsText())
            assertEquals("FREE", body.jsonObject["tier"]?.jsonPrimitive?.content)
            assertEquals("NONE", body.jsonObject["status"]?.jsonPrimitive?.content)
        }

    @Test
    fun `GET plans returns internal error on failure`() =
        testApplication {
            val service =
                FakeBillingService().apply {
                    listPlansResult = Result.failure(RuntimeException("down"))
                }
            configure(service)

            val response = client.get("/api/billing/plans")

            assertEquals(HttpStatusCode.InternalServerError, response.status)
        }

    @Test
    fun `GET bot balance requires admin`() =
        testApplication {
            val botPort = FakeBotStarBalancePort()
            configure(FakeBillingService(), botStarBalancePort = botPort, adminUserIds = setOf(123L))

            val response =
                client.get(
                    "/api/admin/stars/bot-balance",
                ) { header(HttpHeaders.Authorization, bearerFor("42")) }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals(null, response.headers[StarsHeaders.CACHE])
            assertEquals(null, response.headers[StarsHeaders.CACHE_AGE])
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        }

    @Test
    fun `GET bot balance returns balance for admins`() =
        testApplication {
            val botPort =
                FakeBotStarBalancePort().apply {
                    behavior = {
                        BotStarBalanceResult(
                            balance = BotStarBalance(available = 20, pending = 2, updatedAtEpochSeconds = 12345),
                            cacheState = CacheState.MISS,
                            cacheAgeSeconds = 0,
                        )
                    }
                }
            configure(FakeBillingService(), botStarBalancePort = botPort, adminUserIds = setOf(7L))

            val response =
                client.get(
                    "/api/admin/stars/bot-balance",
                ) { header(HttpHeaders.Authorization, bearerFor("7")) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("miss", response.headers[StarsHeaders.CACHE])
            assertEquals("0", response.headers[StarsHeaders.CACHE_AGE])
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            val payload = json.parseToJsonElement(response.bodyAsText())
            assertEquals(20, payload.jsonObject["available"]?.jsonPrimitive?.long)
            assertEquals(2, payload.jsonObject["pending"]?.jsonPrimitive?.long)
            assertEquals(12345, payload.jsonObject["updatedAtEpochSeconds"]?.jsonPrimitive?.long)
        }

    @Test
    fun `GET bot balance exposes stale cache header`() =
        testApplication {
            val botPort =
                FakeBotStarBalancePort().apply {
                    behavior = {
                        BotStarBalanceResult(
                            balance =
                            BotStarBalance(
                                available = 15,
                                pending = 0,
                                updatedAtEpochSeconds = 222,
                                stale = true,
                            ),
                            cacheState = CacheState.STALE,
                            cacheAgeSeconds = 12,
                        )
                    }
                }
            configure(FakeBillingService(), botStarBalancePort = botPort, adminUserIds = setOf(13L))

            val response =
                client.get(
                    "/api/admin/stars/bot-balance",
                ) { header(HttpHeaders.Authorization, bearerFor("13")) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("stale", response.headers[StarsHeaders.CACHE])
            assertEquals("12", response.headers[StarsHeaders.CACHE_AGE])
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            val payload = json.parseToJsonElement(response.bodyAsText())
            assertEquals(true, payload.jsonObject["stale"]?.jsonPrimitive?.booleanOrNull)
        }

    @Test
    fun `GET bot balance exposes hit cache header`() =
        testApplication {
            val botPort =
                FakeBotStarBalancePort().apply {
                    var call = 0
                    behavior = {
                        call += 1
                        val state = if (call == 1) CacheState.MISS else CacheState.HIT
                        BotStarBalanceResult(
                            balance = BotStarBalance(available = 30, pending = 1, updatedAtEpochSeconds = 333),
                            cacheState = state,
                            cacheAgeSeconds = 0,
                        )
                    }
                }

            configure(FakeBillingService(), botStarBalancePort = botPort, adminUserIds = setOf(17L))

            client.get("/api/admin/stars/bot-balance") { header(HttpHeaders.Authorization, bearerFor("17")) }
            val second =
                client.get(
                    "/api/admin/stars/bot-balance",
                ) { header(HttpHeaders.Authorization, bearerFor("17")) }

            assertEquals(HttpStatusCode.OK, second.status)
            assertEquals("hit", second.headers[StarsHeaders.CACHE])
            assertEquals("0", second.headers[StarsHeaders.CACHE_AGE])
            assertEquals("no-store", second.headers[HttpHeaders.CacheControl])
        }

    @Test
    fun `GET bot balance exposes cache age for hits`() =
        testApplication {
            val botPort =
                FakeBotStarBalancePort().apply {
                    var call = 0
                    behavior = {
                        call += 1
                        val state = if (call == 1) CacheState.MISS else CacheState.HIT
                        val age = if (call == 1) 0L else 2L
                        BotStarBalanceResult(
                            balance = BotStarBalance(available = 30, pending = 1, updatedAtEpochSeconds = 333),
                            cacheState = state,
                            cacheAgeSeconds = age,
                        )
                    }
                }

            configure(FakeBillingService(), botStarBalancePort = botPort, adminUserIds = setOf(171L))

            client.get("/api/admin/stars/bot-balance") { header(HttpHeaders.Authorization, bearerFor("171")) }
            val second =
                client.get(
                    "/api/admin/stars/bot-balance",
                ) { header(HttpHeaders.Authorization, bearerFor("171")) }

            assertEquals(HttpStatusCode.OK, second.status)
            assertEquals("hit", second.headers[StarsHeaders.CACHE])
            assertEquals("2", second.headers[StarsHeaders.CACHE_AGE])
        }

    @Test
    fun `GET bot balance enforces per-admin rate limit`() =
        testApplication {
            val botPort = FakeBotStarBalancePort()
            val limiter = BotBalanceRateLimiter(capacity = 1, refillPerMinute = 1)

            configure(
                FakeBillingService(),
                botStarBalancePort = botPort,
                adminUserIds = setOf(21L),
                botBalanceRateLimiter = limiter,
            )

            val first =
                client.get(
                    "/api/admin/stars/bot-balance",
                ) { header(HttpHeaders.Authorization, bearerFor("21")) }
            assertEquals(HttpStatusCode.OK, first.status)

            val second =
                client.get(
                    "/api/admin/stars/bot-balance",
                ) { header(HttpHeaders.Authorization, bearerFor("21")) }
            assertEquals(HttpStatusCode.TooManyRequests, second.status)
            assertTrue((second.headers[HttpHeaders.RetryAfter]?.toIntOrNull() ?: 0) >= 1)
            assertEquals(null, second.headers[StarsHeaders.CACHE])
            assertEquals(null, second.headers[StarsHeaders.CACHE_AGE])
            assertEquals("no-store", second.headers[HttpHeaders.CacheControl])
        }

    @Test
    fun `GET bot balance returns retry after during rate limit window`() =
        testApplication {
            val registry = SimpleMeterRegistry()
            val telegramClient =
                object : StarsClient(
                    botToken = "test",
                    config =
                    StarsClientConfig(
                        connectTimeoutMs = 1,
                        readTimeoutMs = 1,
                        retryMax = 0,
                        retryBaseDelayMs = 1,
                    ),
                    client =
                    HttpClient(MockEngine) {
                        engine { addHandler { error("unexpected http call") } }
                    },
                ) {
                    var callCount = 0

                    override suspend fun getBotStarBalance(): BotStarBalance {
                        callCount += 1
                        throw StarsClientRateLimited(retryAfterSeconds = 5)
                    }
                }
            val service = StarsService(telegramClient, ttlSeconds = 0, maxStaleSeconds = 20, meterRegistry = registry)

            configure(
                FakeBillingService(),
                botStarBalancePort = service,
                adminUserIds = setOf(29L),
                meterRegistry = registry,
            )

            val first =
                client.get(
                    "/api/admin/stars/bot-balance",
                ) { header(HttpHeaders.Authorization, bearerFor("29")) }
            assertEquals(HttpStatusCode.TooManyRequests, first.status)
            assertEquals("5", first.headers[HttpHeaders.RetryAfter])

            val second =
                client.get(
                    "/api/admin/stars/bot-balance",
                ) { header(HttpHeaders.Authorization, bearerFor("29")) }
            assertEquals(HttpStatusCode.TooManyRequests, second.status)
            assertTrue((second.headers[HttpHeaders.RetryAfter]?.toIntOrNull() ?: 0) >= 1)
            assertEquals(1, telegramClient.callCount)
        }

    @Test
    fun `records admin request metrics for local rate limits`() =
        testApplication {
            val botPort = FakeBotStarBalancePort()
            var now = 0L
            val limiter = BotBalanceRateLimiter(capacity = 1, refillPerMinute = 1, nanoTimeProvider = { now })
            val registry = SimpleMeterRegistry()

            configure(
                FakeBillingService(),
                botStarBalancePort = botPort,
                adminUserIds = setOf(22L),
                botBalanceRateLimiter = limiter,
                meterRegistry = registry,
            )

            client.get("/api/admin/stars/bot-balance") { header(HttpHeaders.Authorization, bearerFor("22")) }
            now = 0L // still no refill
            client.get("/api/admin/stars/bot-balance") { header(HttpHeaders.Authorization, bearerFor("22")) }

            assertEquals(
                1.0,
                registry
                    .counter(
                        StarsMetrics.CNT_ADMIN_REQUESTS,
                        StarsMetrics.LABEL_RESULT,
                        StarsAdminResults.OK,
                    ).count(),
            )
            assertEquals(
                1.0,
                registry
                    .counter(
                        StarsMetrics.CNT_ADMIN_REQUESTS,
                        StarsMetrics.LABEL_RESULT,
                        StarsAdminResults.LOCAL_RATE_LIMITED,
                    ).count(),
            )
        }

    @Test
    fun `records admin request metrics for telegram errors`() =
        testApplication {
            val botPort = FakeBotStarBalancePort()
            val registry = SimpleMeterRegistry()

            configure(
                FakeBillingService(),
                botStarBalancePort = botPort,
                adminUserIds = setOf(23L),
                meterRegistry = registry,
            )

            val scenarios =
                listOf(
                    Triple(
                        StarsClientRateLimited(retryAfterSeconds = 2),
                        StarsAdminResults.TG_RATE_LIMITED,
                        HttpStatusCode.TooManyRequests,
                    ),
                    Triple(StarsClientServerError(500), StarsAdminResults.SERVER, HttpStatusCode.ServiceUnavailable),
                    Triple(StarsClientBadRequest(400), StarsAdminResults.BAD_REQUEST, HttpStatusCode.BadGateway),
                    Triple(
                        StarsClientDecodeError(Exception("boom")),
                        StarsAdminResults.DECODE_ERROR,
                        HttpStatusCode.BadGateway,
                    ),
                    Triple(RuntimeException("boom"), StarsAdminResults.OTHER, HttpStatusCode.InternalServerError),
                )

            scenarios.forEach { (error, label, status) ->
                botPort.behavior = { throw error }
                val response =
                    client.get(
                        "/api/admin/stars/bot-balance",
                    ) { header(HttpHeaders.Authorization, bearerFor("23")) }
                assertEquals(status, response.status)
            }

            assertEquals(
                1.0,
                registry
                    .counter(
                        StarsMetrics.CNT_ADMIN_REQUESTS,
                        StarsMetrics.LABEL_RESULT,
                        StarsAdminResults.TG_RATE_LIMITED,
                    ).count(),
            )
            assertEquals(
                1.0,
                registry
                    .counter(
                        StarsMetrics.CNT_ADMIN_REQUESTS,
                        StarsMetrics.LABEL_RESULT,
                        StarsAdminResults.SERVER,
                    ).count(),
            )
            assertEquals(
                1.0,
                registry
                    .counter(
                        StarsMetrics.CNT_ADMIN_REQUESTS,
                        StarsMetrics.LABEL_RESULT,
                        StarsAdminResults.BAD_REQUEST,
                    ).count(),
            )
            assertEquals(
                1.0,
                registry
                    .counter(
                        StarsMetrics.CNT_ADMIN_REQUESTS,
                        StarsMetrics.LABEL_RESULT,
                        StarsAdminResults.DECODE_ERROR,
                    ).count(),
            )
            assertEquals(
                1.0,
                registry
                    .counter(
                        StarsMetrics.CNT_ADMIN_REQUESTS,
                        StarsMetrics.LABEL_RESULT,
                        StarsAdminResults.OTHER,
                    ).count(),
            )
            assertEquals(5L, registry.find(StarsMetrics.TIMER_ADMIN).timer()?.count() ?: 0L)
        }

    @Test
    fun `GET bot balance maps rate limited errors`() =
        testApplication {
            val botPort =
                FakeBotStarBalancePort().apply {
                    behavior =
                        { throw StarsClientRateLimited(retryAfterSeconds = 5) }
                }
            configure(FakeBillingService(), botStarBalancePort = botPort, adminUserIds = setOf(10L))

            val response =
                client.get(
                    "/api/admin/stars/bot-balance",
                ) { header(HttpHeaders.Authorization, bearerFor("10")) }

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertEquals("5", response.headers[HttpHeaders.RetryAfter])
            assertEquals(null, response.headers[StarsHeaders.CACHE])
            assertEquals(null, response.headers[StarsHeaders.CACHE_AGE])
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        }

    @Test
    fun `GET bot balance maps rate limited errors without retry after`() =
        testApplication {
            val botPort =
                FakeBotStarBalancePort().apply {
                    behavior =
                        { throw StarsClientRateLimited(retryAfterSeconds = null) }
                }
            configure(FakeBillingService(), botStarBalancePort = botPort, adminUserIds = setOf(101L))

            val response =
                client.get(
                    "/api/admin/stars/bot-balance",
                ) { header(HttpHeaders.Authorization, bearerFor("101")) }

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertEquals(null, response.headers[HttpHeaders.RetryAfter])
            assertEquals(null, response.headers[StarsHeaders.CACHE])
            assertEquals(null, response.headers[StarsHeaders.CACHE_AGE])
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        }

    @Test
    fun `GET bot balance maps server errors`() =
        testApplication {
            val botPort = FakeBotStarBalancePort().apply { behavior = { throw StarsClientServerError(502) } }
            configure(FakeBillingService(), botStarBalancePort = botPort, adminUserIds = setOf(11L))

            val response =
                client.get(
                    "/api/admin/stars/bot-balance",
                ) { header(HttpHeaders.Authorization, bearerFor("11")) }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals(null, response.headers[HttpHeaders.RetryAfter])
            assertEquals(null, response.headers[StarsHeaders.CACHE])
            assertEquals(null, response.headers[StarsHeaders.CACHE_AGE])
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        }

    @Test
    fun `GET bot balance maps bad requests`() =
        testApplication {
            val botPort = FakeBotStarBalancePort().apply { behavior = { throw StarsClientBadRequest(400) } }
            configure(FakeBillingService(), botStarBalancePort = botPort, adminUserIds = setOf(12L))

            val response =
                client.get(
                    "/api/admin/stars/bot-balance",
                ) { header(HttpHeaders.Authorization, bearerFor("12")) }

            assertEquals(HttpStatusCode.BadGateway, response.status)
            assertEquals(null, response.headers[HttpHeaders.RetryAfter])
            assertEquals(null, response.headers[StarsHeaders.CACHE])
            assertEquals(null, response.headers[StarsHeaders.CACHE_AGE])
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        }

    @Test
    fun `GET bot balance maps decode errors`() =
        testApplication {
            val botPort =
                FakeBotStarBalancePort().apply {
                    behavior =
                        { throw StarsClientDecodeError(Exception("boom")) }
                }
            configure(FakeBillingService(), botStarBalancePort = botPort, adminUserIds = setOf(14L))

            val response =
                client.get(
                    "/api/admin/stars/bot-balance",
                ) { header(HttpHeaders.Authorization, bearerFor("14")) }

            assertEquals(HttpStatusCode.BadGateway, response.status)
            assertEquals(null, response.headers[HttpHeaders.RetryAfter])
            assertEquals(null, response.headers[StarsHeaders.CACHE])
            assertEquals(null, response.headers[StarsHeaders.CACHE_AGE])
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        }

    @Test
    fun `records admin request metrics for auth errors`() =
        testApplication {
            val registry = SimpleMeterRegistry()

            configure(
                FakeBillingService(),
                botStarBalancePort = FakeBotStarBalancePort(),
                adminUserIds = setOf(45L),
                meterRegistry = registry,
            )

            val unauthorized = client.get("/api/admin/stars/bot-balance")
            assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)

            val forbidden =
                client.get(
                    "/api/admin/stars/bot-balance",
                ) { header(HttpHeaders.Authorization, bearerFor("46")) }
            assertEquals(HttpStatusCode.Forbidden, forbidden.status)

            assertEquals(
                1.0,
                registry
                    .counter(
                        StarsMetrics.CNT_ADMIN_REQUESTS,
                        StarsMetrics.LABEL_RESULT,
                        StarsAdminResults.UNAUTHORIZED,
                    ).count(),
            )
            assertEquals(
                1.0,
                registry
                    .counter(
                        StarsMetrics.CNT_ADMIN_REQUESTS,
                        StarsMetrics.LABEL_RESULT,
                        StarsAdminResults.FORBIDDEN,
                    ).count(),
            )
            assertEquals(2L, registry.find(StarsMetrics.TIMER_ADMIN).timer()?.count() ?: 0L)
        }

    @Test
    fun `records admin request metrics when bot balance is unconfigured`() =
        testApplication {
            val registry = SimpleMeterRegistry()

            configure(
                FakeBillingService(),
                botStarBalancePort = null,
                adminUserIds = setOf(47L),
                meterRegistry = registry,
            )

            val response =
                client.get(
                    "/api/admin/stars/bot-balance",
                ) { header(HttpHeaders.Authorization, bearerFor("47")) }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals(
                1.0,
                registry
                    .counter(
                        StarsMetrics.CNT_ADMIN_REQUESTS,
                        StarsMetrics.LABEL_RESULT,
                        StarsAdminResults.UNCONFIGURED,
                    ).count(),
            )
            assertEquals(1L, registry.find(StarsMetrics.TIMER_ADMIN).timer()?.count() ?: 0L)
        }

    @Test
    fun `GET bot balance maps unexpected errors to internal`() =
        testApplication {
            val botPort = FakeBotStarBalancePort().apply { behavior = { throw RuntimeException("boom") } }
            configure(FakeBillingService(), botStarBalancePort = botPort, adminUserIds = setOf(111L))

            val response =
                client.get(
                    "/api/admin/stars/bot-balance",
                ) { header(HttpHeaders.Authorization, bearerFor("111")) }

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals(null, response.headers[StarsHeaders.CACHE])
            assertEquals(null, response.headers[HttpHeaders.RetryAfter])
            assertEquals(null, response.headers[StarsHeaders.CACHE_AGE])
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        }

    @Test
    fun `GET bot balance requires authentication`() =
        testApplication {
            configure(FakeBillingService(), botStarBalancePort = FakeBotStarBalancePort(), adminUserIds = setOf(3L))

            val response = client.get("/api/admin/stars/bot-balance")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(null, response.headers[StarsHeaders.CACHE])
            assertEquals(null, response.headers[StarsHeaders.CACHE_AGE])
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        }

    @Test
    fun `GET bot balance unavailable when telegram not configured`() =
        testApplication {
            configure(FakeBillingService(), botStarBalancePort = null, adminUserIds = setOf(4L))

            val response =
                client.get(
                    "/api/admin/stars/bot-balance",
                ) { header(HttpHeaders.Authorization, bearerFor("4")) }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals(null, response.headers[StarsHeaders.CACHE])
            assertEquals(null, response.headers[StarsHeaders.CACHE_AGE])
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        }

    @Test
    fun `GET balance returns bot amount`() =
        testApplication {
            val engine =
                MockEngine { _ ->
                    respond(
                        content = """{"ok":true,"result":{"amount":123,"nanostar_amount":45}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/json"),
                    )
                }
            val registry = SimpleMeterRegistry()
            val starsClient =
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
                        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json() }
                    },
                )
            configure(FakeBillingService(), starsClient = starsClient, meterRegistry = registry)

            val response =
                client.get(
                    "/api/billing/stars/balance",
                ) { header(HttpHeaders.Authorization, bearerFor("55")) }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.parseToJsonElement(response.bodyAsText())
            assertEquals(123, payload.jsonObject["amount"]?.jsonPrimitive?.long)
            assertEquals(45, payload.jsonObject["nano"]?.jsonPrimitive?.long)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertEquals(null, response.headers[StarsHeaders.CACHE])
            assertEquals(null, response.headers[StarsHeaders.CACHE_AGE])
            assertEquals(
                1.0,
                registry
                    .counter(
                        StarsMetrics.CNT_PUBLIC_REQUESTS,
                        StarsMetrics.LABEL_RESULT,
                        StarsPublicResults.OK,
                    ).count(),
            )
            assertEquals(
                1.0,
                registry
                    .counter(
                        StarsMetrics.CNT_OUTCOME,
                        StarsMetrics.LABEL_OUTCOME,
                        StarsOutcomes.SUCCESS,
                    ).count(),
            )
            assertEquals(1, registry.find(StarsMetrics.TIMER_PUBLIC).timer()?.count())
        }

    @Test
    fun `GET balance maps legacy available_balance to amount`() =
        testApplication {
            val engine =
                MockEngine { _ ->
                    respond(
                        content = "" +
                            """{"ok":true,"result":""" +
                            """{"available_balance":77,"pending_balance":1,"updated_at":123}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/json"),
                    )
                }
            val registry = SimpleMeterRegistry()
            val starsClient =
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
                        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json() }
                    },
                )
            configure(FakeBillingService(), starsClient = starsClient, meterRegistry = registry)

            val response =
                client.get(
                    "/api/billing/stars/balance",
                ) { header(HttpHeaders.Authorization, bearerFor("99")) }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.parseToJsonElement(response.bodyAsText())
            assertEquals(77, payload.jsonObject["amount"]?.jsonPrimitive?.long)
            assertEquals(null, payload.jsonObject["nano"]?.jsonPrimitive?.longOrNull)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertEquals(null, response.headers[StarsHeaders.CACHE])
            assertEquals(null, response.headers[StarsHeaders.CACHE_AGE])
            assertEquals(
                1.0,
                registry
                    .counter(
                        StarsMetrics.CNT_PUBLIC_REQUESTS,
                        StarsMetrics.LABEL_RESULT,
                        StarsPublicResults.OK,
                    ).count(),
            )
            assertEquals(
                1.0,
                registry
                    .counter(
                        StarsMetrics.CNT_OUTCOME,
                        StarsMetrics.LABEL_OUTCOME,
                        StarsOutcomes.SUCCESS,
                    ).count(),
            )
            assertEquals(1, registry.find(StarsMetrics.TIMER_PUBLIC).timer()?.count())
        }

    @Test
    fun `GET balance unavailable when telegram not configured`() =
        testApplication {
            val registry = SimpleMeterRegistry()
            configure(FakeBillingService(), starsClient = null, meterRegistry = registry)

            val response =
                client.get(
                    "/api/billing/stars/balance",
                ) { header(HttpHeaders.Authorization, bearerFor("2")) }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals(null, response.headers[StarsHeaders.CACHE])
            assertEquals(null, response.headers[StarsHeaders.CACHE_AGE])
            assertEquals(
                1.0,
                registry
                    .counter(
                        StarsMetrics.CNT_PUBLIC_REQUESTS,
                        StarsMetrics.LABEL_RESULT,
                        StarsPublicResults.UNCONFIGURED,
                    ).count(),
            )
        }

    @Test
    fun `GET balance maps rate limited`() =
        testApplication {
            val engine =
                MockEngine { _ ->
                    respond(
                        "",
                        HttpStatusCode.TooManyRequests,
                        headers = headersOf(HttpHeaders.RetryAfter, "5"),
                    )
                }
            val registry = SimpleMeterRegistry()
            val starsClient =
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
                        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json() }
                    },
                )
            configure(FakeBillingService(), starsClient = starsClient, meterRegistry = registry)

            val response =
                client.get(
                    "/api/billing/stars/balance",
                ) { header(HttpHeaders.Authorization, bearerFor("5")) }

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertEquals("5", response.headers[HttpHeaders.RetryAfter])
            assertEquals(null, response.headers[StarsHeaders.CACHE])
            assertEquals(null, response.headers[StarsHeaders.CACHE_AGE])
            assertEquals(
                1.0,
                registry
                    .counter(
                        StarsMetrics.CNT_PUBLIC_REQUESTS,
                        StarsMetrics.LABEL_RESULT,
                        StarsPublicResults.TG_RATE_LIMITED,
                    ).count(),
            )
            assertEquals(
                1.0,
                registry
                    .counter(
                        StarsMetrics.CNT_OUTCOME,
                        StarsMetrics.LABEL_OUTCOME,
                        StarsOutcomes.RATE_LIMITED,
                    ).count(),
            )
        }

    @Test
    fun `GET balance maps rate limited http date`() =
        testApplication {
            val retryDate = ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(4)
            val engine =
                MockEngine { _ ->
                    respond(
                        "",
                        HttpStatusCode.TooManyRequests,
                        headers =
                        headersOf(
                            HttpHeaders.RetryAfter,
                            DateTimeFormatter.RFC_1123_DATE_TIME.format(retryDate),
                        ),
                    )
                }
            val registry = SimpleMeterRegistry()
            val starsClient =
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
                        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json() }
                    },
                )
            configure(FakeBillingService(), starsClient = starsClient, meterRegistry = registry)

            val response =
                client.get(
                    "/api/billing/stars/balance",
                ) { header(HttpHeaders.Authorization, bearerFor("53")) }

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            val retryAfterHeader = response.headers[HttpHeaders.RetryAfter]!!.toLong()
            assertTrue(retryAfterHeader >= 1)
            assertEquals(null, response.headers[StarsHeaders.CACHE])
            assertEquals(null, response.headers[StarsHeaders.CACHE_AGE])
            assertEquals(
                1.0,
                registry
                    .counter(
                        StarsMetrics.CNT_PUBLIC_REQUESTS,
                        StarsMetrics.LABEL_RESULT,
                        StarsPublicResults.TG_RATE_LIMITED,
                    ).count(),
            )
            assertEquals(
                1.0,
                registry
                    .counter(
                        StarsMetrics.CNT_OUTCOME,
                        StarsMetrics.LABEL_OUTCOME,
                        StarsOutcomes.RATE_LIMITED,
                    ).count(),
            )
        }

    @Test
    fun `GET balance maps ok false rate limited with parameters`() =
        testApplication {
            val engine =
                MockEngine { _ ->
                    respond(
                        content = """{"ok":false,"error_code":429,"parameters":{"retry_after":6}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val registry = SimpleMeterRegistry()
            val starsClient =
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
                        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json() }
                    },
                )
            configure(FakeBillingService(), starsClient = starsClient, meterRegistry = registry)

            val response =
                client.get(
                    "/api/billing/stars/balance",
                ) { header(HttpHeaders.Authorization, bearerFor("63")) }

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertEquals("6", response.headers[HttpHeaders.RetryAfter])
            assertEquals(null, response.headers[StarsHeaders.CACHE])
            assertEquals(null, response.headers[StarsHeaders.CACHE_AGE])
            assertEquals(
                1.0,
                registry
                    .counter(
                        StarsMetrics.CNT_PUBLIC_REQUESTS,
                        StarsMetrics.LABEL_RESULT,
                        StarsPublicResults.TG_RATE_LIMITED,
                    ).count(),
            )
            assertEquals(
                1.0,
                registry
                    .counter(
                        StarsMetrics.CNT_OUTCOME,
                        StarsMetrics.LABEL_OUTCOME,
                        StarsOutcomes.RATE_LIMITED,
                    ).count(),
            )
        }

    @Test
    fun `GET balance maps ok false bad request`() =
        testApplication {
            val engine =
                MockEngine { _ ->
                    respond(
                        content = """{"ok":false,"error_code":400}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val registry = SimpleMeterRegistry()
            val starsClient =
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
                        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json() }
                    },
                )
            configure(FakeBillingService(), starsClient = starsClient, meterRegistry = registry)

            val response =
                client.get(
                    "/api/billing/stars/balance",
                ) { header(HttpHeaders.Authorization, bearerFor("64")) }

            assertEquals(HttpStatusCode.BadGateway, response.status)
            assertEquals(null, response.headers[HttpHeaders.RetryAfter])
            assertEquals(null, response.headers[StarsHeaders.CACHE])
            assertEquals(null, response.headers[StarsHeaders.CACHE_AGE])
            assertEquals(
                1.0,
                registry
                    .counter(
                        StarsMetrics.CNT_PUBLIC_REQUESTS,
                        StarsMetrics.LABEL_RESULT,
                        StarsPublicResults.BAD_REQUEST,
                    ).count(),
            )
            assertEquals(
                1.0,
                registry
                    .counter(
                        StarsMetrics.CNT_OUTCOME,
                        StarsMetrics.LABEL_OUTCOME,
                        StarsOutcomes.BAD_REQUEST,
                    ).count(),
            )
        }

    @Test
    fun `GET balance maps ok false server error`() =
        testApplication {
            val engine =
                MockEngine { _ ->
                    respond(
                        content = """{"ok":false,"error_code":500}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val registry = SimpleMeterRegistry()
            val starsClient =
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
                        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json() }
                    },
                )
            configure(FakeBillingService(), starsClient = starsClient, meterRegistry = registry)

            val response =
                client.get(
                    "/api/billing/stars/balance",
                ) { header(HttpHeaders.Authorization, bearerFor("65")) }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals(null, response.headers[HttpHeaders.RetryAfter])
            assertEquals(null, response.headers[StarsHeaders.CACHE])
            assertEquals(null, response.headers[StarsHeaders.CACHE_AGE])
            assertEquals(
                1.0,
                registry
                    .counter(
                        StarsMetrics.CNT_PUBLIC_REQUESTS,
                        StarsMetrics.LABEL_RESULT,
                        StarsPublicResults.SERVER,
                    ).count(),
            )
            assertEquals(
                1.0,
                registry
                    .counter(
                        StarsMetrics.CNT_OUTCOME,
                        StarsMetrics.LABEL_OUTCOME,
                        StarsOutcomes.SERVER,
                    ).count(),
            )
        }

    private fun ApplicationTestBuilder.configure(
        service: BillingService,
        starBalancePort: StarBalancePort = InMemoryStarBalancePort(),
        botStarBalancePort: BotStarBalancePort? = FakeBotStarBalancePort(),
        adminUserIds: Set<Long> = setOf(1L),
        botBalanceRateLimiter: BotBalanceRateLimiter? = null,
        meterRegistry: MeterRegistry? = null,
        starsClient: StarsClient? = null,
    ) {
        application {
            installErrorPages()
            install(ContentNegotiation) { json() }
            install(testAuthPlugin)
            val entitlementsService = EntitlementsService(service)
            attributes.put(
                BillingRouteServicesKey,
                BillingRouteServices(
                    billingService = service,
                    starBalancePort = starBalancePort,
                    botStarBalancePort = botStarBalancePort,
                    entitlementsService = entitlementsService,
                    adminUserIds = adminUserIds,
                    botBalanceRateLimiter = botBalanceRateLimiter,
                    meterRegistry = meterRegistry,
                    starsClient = starsClient,
                ),
            )
            routing { billingRoutes() }
        }
    }

    private fun bearerFor(subject: String): String {
        val token = JWT.create().withSubject(subject).sign(Algorithm.HMAC256("test-secret"))
        return "Bearer $token"
    }

    private class FakeBillingService : BillingService {
        var listPlansResult: Result<List<BillingPlan>> = Result.success(emptyList())
        var createInvoiceResult: Result<String> = Result.success("https://t.me/pay/ok")
        var getMySubscriptionResult: Result<UserSubscription?> = Result.success(null)

        override suspend fun listPlans(): Result<List<BillingPlan>> = listPlansResult

        override suspend fun createInvoiceFor(
            userId: Long,
            tier: Tier,
        ): Result<String> = createInvoiceResult

        override suspend fun applySuccessfulPayment(
            userId: Long,
            tier: Tier,
            amountXtr: Long,
            providerPaymentId: String?,
            payload: String?,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun getMySubscription(userId: Long): Result<UserSubscription?> = getMySubscriptionResult
    }

    private class FakeBotStarBalancePort : BotStarBalancePort {
        var behavior: suspend () -> BotStarBalanceResult = {
            BotStarBalanceResult(
                balance = BotStarBalance(available = 10, pending = 0, updatedAtEpochSeconds = 1_700_000_000),
                cacheState = CacheState.MISS,
                cacheAgeSeconds = 0,
            )
        }

        override suspend fun getBotStarBalance(): BotStarBalanceResult = behavior()
    }

    private val testAuthPlugin =
        createApplicationPlugin(name = "TestAuth") {
            onCall { call ->
                val header = call.request.headers[HttpHeaders.Authorization]
                if (header != null && header.startsWith("Bearer ")) {
                    val token = header.removePrefix("Bearer ")
                    val decoded = JWT.decode(token)
                    call.authentication.principal(JWTPrincipal(decoded))
                }
            }
        }
}
