package routes

import billing.model.BillingPlan
import billing.model.Tier
import billing.model.UserSubscription
import billing.model.Xtr
import billing.service.BillingService
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BillingRoutesTest {

    @Test
    fun `plans endpoint returns plans`() = testApplication {
        val service = FakeBillingService().apply {
            listPlansResult = Result.success(
                listOf(
                    BillingPlan(tier = Tier.PRO, title = "Pro", priceXtr = Xtr(100), isActive = true),
                    BillingPlan(tier = Tier.VIP, title = "Vip", priceXtr = Xtr(200), isActive = true)
                )
            )
        }

        configureTestApp(service)

        val response = client.get("/api/billing/plans")

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = Json.parseToJsonElement(response.bodyAsText())
        assertTrue(payload is JsonArray)
        assertEquals(2, payload.jsonArray.size)
    }

    @Test
    fun `create invoice requires auth`() = testApplication {
        val service = FakeBillingService()
        configureTestApp(service)

        val response = client.post("/api/billing/stars/invoice") {
            contentType(ContentType.Application.Json)
            setBody("""{"tier":"PRO"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `create invoice rejects unknown tier`() = testApplication {
        val service = FakeBillingService()
        configureTestApp(service)

        val response = client.post("/api/billing/stars/invoice") {
            header("X-Test-User", "12345")
            contentType(ContentType.Application.Json)
            setBody("""{"tier":"foo"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val payload = Json.parseToJsonElement(response.bodyAsText())
        assertEquals("bad_request", payload.jsonObject["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `create invoice returns link`() = testApplication {
        val service = FakeBillingService().apply {
            createInvoiceResult = Result.success("https://t.me/pay/invoice")
        }
        configureTestApp(service)

        val response = client.post("/api/billing/stars/invoice") {
            header("X-Test-User", "12345")
            contentType(ContentType.Application.Json)
            setBody("""{"tier":"PRO"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val payload = Json.parseToJsonElement(response.bodyAsText())
        assertEquals("https://t.me/pay/invoice", payload.jsonObject["invoiceLink"]?.jsonPrimitive?.content)
    }

    @Test
    fun `create invoice handles plan not found`() = testApplication {
        val service = FakeBillingService().apply {
            createInvoiceResult = Result.failure(NoSuchElementException("plan not found"))
        }
        configureTestApp(service)

        val response = client.post("/api/billing/stars/invoice") {
            header("X-Test-User", "12345")
            contentType(ContentType.Application.Json)
            setBody("""{"tier":"PRO"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `my subscription requires auth`() = testApplication {
        val service = FakeBillingService()
        configureTestApp(service)

        val response = client.get("/api/billing/stars/me")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `my subscription returns active subscription`() = testApplication {
        val subscription = UserSubscription(
            userId = 12345,
            tier = Tier.PRO,
            status = billing.model.SubStatus.ACTIVE,
            startedAt = java.time.Instant.parse("2023-09-01T00:00:00Z"),
            expiresAt = java.time.Instant.parse("2023-10-01T00:00:00Z")
        )
        val service = FakeBillingService().apply {
            getMySubscriptionResult = Result.success(subscription)
        }
        configureTestApp(service)

        val response = client.get("/api/billing/stars/me") {
            header("X-Test-User", "12345")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = Json.parseToJsonElement(response.bodyAsText())
        assertEquals("PRO", payload.jsonObject["tier"]?.jsonPrimitive?.content)
        assertEquals("ACTIVE", payload.jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `my subscription returns free when absent`() = testApplication {
        val service = FakeBillingService().apply {
            getMySubscriptionResult = Result.success(null)
        }
        configureTestApp(service)

        val response = client.get("/api/billing/stars/me") {
            header("X-Test-User", "12345")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = Json.parseToJsonElement(response.bodyAsText())
        assertEquals("FREE", payload.jsonObject["tier"]?.jsonPrimitive?.content)
        assertEquals("NONE", payload.jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `list plans handles internal errors`() = testApplication {
        val service = FakeBillingService().apply {
            listPlansResult = Result.failure(RuntimeException("boom"))
        }
        configureTestApp(service)

        val response = client.get("/api/billing/plans")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
    }

    private fun ApplicationTestBuilder.configureTestApp(service: BillingService) {
        application {
            install(ContentNegotiation) {
                json()
            }
            install(testAuthPlugin)
            attributes.put(BillingRouteServicesKey, BillingRouteServices(service))
            routing {
                billingRoutes()
            }
        }
    }

    private class FakeBillingService : BillingService {
        var listPlansResult: Result<List<BillingPlan>> = Result.success(emptyList())
        var createInvoiceResult: Result<String> = Result.success("")
        var getMySubscriptionResult: Result<UserSubscription?> = Result.success(null)

        override suspend fun listPlans(): Result<List<BillingPlan>> = listPlansResult

        override suspend fun createInvoiceFor(userId: Long, tier: Tier): Result<String> = createInvoiceResult

        override suspend fun applySuccessfulPayment(
            userId: Long,
            tier: Tier,
            amountXtr: Long,
            providerPaymentId: String?,
            payload: String?
        ): Result<Unit> = Result.success(Unit)

        override suspend fun getMySubscription(userId: Long): Result<UserSubscription?> = getMySubscriptionResult
    }

    private val testAuthPlugin = createApplicationPlugin(name = "TestAuth") {
        onCall { call ->
            val subject = call.request.headers["X-Test-User"]
            if (!subject.isNullOrBlank()) {
                val token = JWT.create().withSubject(subject).sign(Algorithm.HMAC256("secret"))
                val decoded = JWT.decode(token)
                call.authentication.principal(JWTPrincipal(decoded))
            }
        }
    }
}
