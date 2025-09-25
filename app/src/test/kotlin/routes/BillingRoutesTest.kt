package routes

import billing.model.BillingPlan
import billing.model.SubStatus
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
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class BillingRoutesTest {

    @Test
    fun `GET plans returns sorted plans`() = testApplication {
        val service = FakeBillingService().apply {
            listPlansResult = Result.success(
                listOf(
                    BillingPlan(Tier.VIP, "VIP", Xtr(5500), isActive = true),
                    BillingPlan(Tier.PRO, "Pro", Xtr(1500), isActive = true)
                )
            )
        }
        configure(service)

        val response = client.get("/api/billing/plans")

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = Json.parseToJsonElement(response.bodyAsText())
        assertTrue(payload is JsonArray)
        val tiers = payload.jsonArray.map { element -> element.jsonObject["tier"]!!.jsonPrimitive.content }
        assertEquals(listOf("VIP", "PRO"), tiers)
    }

    @Test
    fun `POST invoice requires authentication`() = testApplication {
        configure(FakeBillingService())

        val response = client.post("/api/billing/stars/invoice") {
            contentType(ContentType.Application.Json)
            setBody("""{"tier":"PRO"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST invoice validates tier`() = testApplication {
        configure(FakeBillingService())

        val response = client.post("/api/billing/stars/invoice") {
            header(HttpHeaders.Authorization, bearerFor("42"))
            contentType(ContentType.Application.Json)
            setBody("""{"tier":"FOO"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText())
        assertEquals("bad_request", body.jsonObject["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST invoice returns link`() = testApplication {
        val service = FakeBillingService().apply {
            createInvoiceResult = Result.success("https://t.me/pay/invoice")
        }
        configure(service)

        val response = client.post("/api/billing/stars/invoice") {
            header(HttpHeaders.Authorization, bearerFor("99"))
            contentType(ContentType.Application.Json)
            setBody("""{"tier":"PRO"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText())
        assertEquals("https://t.me/pay/invoice", body.jsonObject["invoiceLink"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST invoice handles missing plan`() = testApplication {
        val service = FakeBillingService().apply {
            createInvoiceResult = Result.failure(NoSuchElementException("plan not found"))
        }
        configure(service)

        val response = client.post("/api/billing/stars/invoice") {
            header(HttpHeaders.Authorization, bearerFor("50"))
            contentType(ContentType.Application.Json)
            setBody("""{"tier":"PRO"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST invoice propagates internal errors`() = testApplication {
        val service = FakeBillingService().apply {
            createInvoiceResult = Result.failure(RuntimeException("boom"))
        }
        configure(service)

        val response = client.post("/api/billing/stars/invoice") {
            header(HttpHeaders.Authorization, bearerFor("51"))
            contentType(ContentType.Application.Json)
            setBody("""{"tier":"PRO"}""")
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
    }

    @Test
    fun `GET my subscription requires authentication`() = testApplication {
        configure(FakeBillingService())

        val response = client.get("/api/billing/stars/me")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET my subscription returns subscription`() = testApplication {
        val service = FakeBillingService().apply {
            getMySubscriptionResult = Result.success(
                UserSubscription(
                    userId = 77L,
                    tier = Tier.PRO_PLUS,
                    status = SubStatus.ACTIVE,
                    startedAt = java.time.Instant.parse("2024-01-01T00:00:00Z"),
                    expiresAt = java.time.Instant.parse("2024-02-01T00:00:00Z")
                )
            )
        }
        configure(service)

        val response = client.get("/api/billing/stars/me") {
            header(HttpHeaders.Authorization, bearerFor("77"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText())
        assertEquals("PRO_PLUS", body.jsonObject["tier"]?.jsonPrimitive?.content)
        assertEquals("ACTIVE", body.jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `GET my subscription falls back to free`() = testApplication {
        val service = FakeBillingService().apply {
            getMySubscriptionResult = Result.success(null)
        }
        configure(service)

        val response = client.get("/api/billing/stars/me") {
            header(HttpHeaders.Authorization, bearerFor("70"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText())
        assertEquals("FREE", body.jsonObject["tier"]?.jsonPrimitive?.content)
        assertEquals("NONE", body.jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `GET plans returns internal error on failure`() = testApplication {
        val service = FakeBillingService().apply {
            listPlansResult = Result.failure(RuntimeException("down"))
        }
        configure(service)

        val response = client.get("/api/billing/plans")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
    }

    private fun ApplicationTestBuilder.configure(service: BillingService) {
        application {
            install(ContentNegotiation) { json() }
            install(testAuthPlugin)
            attributes.put(BillingRouteServicesKey, BillingRouteServices(service))
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
            val header = call.request.headers[HttpHeaders.Authorization]
            if (header != null && header.startsWith("Bearer ")) {
                val token = header.removePrefix("Bearer ")
                val decoded = JWT.decode(token)
                call.authentication.principal(JWTPrincipal(decoded))
            }
        }
    }
}
