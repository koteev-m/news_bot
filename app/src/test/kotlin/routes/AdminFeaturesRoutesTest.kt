package routes

import app.Services
import billing.model.Tier
import billing.model.UserSubscription
import billing.service.BillingService
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.pengrad.telegrambot.TelegramBot
import deeplink.InMemoryDeepLinkStore
import features.FeatureFlags
import features.FeatureFlagsService
import features.FeatureFlagsServiceImpl
import features.FeatureOverridesRepository
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
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
import kotlin.time.Duration.Companion.days
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import errors.installErrorPages

class AdminFeaturesRoutesTest {

    @Test
    fun `GET requires authentication`() = testApplication {
        val repository = InMemoryFeatureOverridesRepository()
        val service = service(repository)
        configure(service)

        val response = client.get("/api/admin/features")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `PATCH requires authentication`() = testApplication {
        val repository = InMemoryFeatureOverridesRepository()
        val service = service(repository)
        configure(service)

        val response = client.patch("/api/admin/features") {
            contentType(ContentType.Application.Json)
            setBody("""{"importByUrl":true}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `PATCH rejects non admins`() = testApplication {
        val repository = InMemoryFeatureOverridesRepository()
        val service = service(repository)
        configure(service)

        val response = client.patch("/api/admin/features") {
            header(HttpHeaders.Authorization, bearerFor("100"))
            contentType(ContentType.Application.Json)
            setBody("""{"importByUrl":true}""")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET returns effective flags`() = testApplication {
        val repository = InMemoryFeatureOverridesRepository()
        val service = service(repository)
        configure(service)

        val response = client.get("/api/admin/features") {
            header(HttpHeaders.Authorization, bearerFor("200"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText())
        val json = body.jsonObject
        assertEquals(false, json["importByUrl"]?.jsonPrimitive?.boolean)
        assertEquals(true, json["webhookQueue"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `PATCH applies overrides immediately`() = testApplication {
        val repository = InMemoryFeatureOverridesRepository()
        val service = service(repository)
        configure(service)

        assertEquals(0L, service.updatesFlow.value)

        val patchResponse = client.patch("/api/admin/features") {
            header(HttpHeaders.Authorization, bearerFor(ADMIN_ID.toString()))
            contentType(ContentType.Application.Json)
            setBody("""{"importByUrl":true}""")
        }

        assertEquals(HttpStatusCode.NoContent, patchResponse.status)
        assertEquals(1L, service.updatesFlow.value)

        val response = client.get("/api/admin/features") {
            header(HttpHeaders.Authorization, bearerFor("300"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText())
        val json = body.jsonObject
        assertEquals(true, json["importByUrl"]?.jsonPrimitive?.boolean)
        assertEquals(true, json["newsPublish"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `PATCH validates payload`() = testApplication {
        val repository = InMemoryFeatureOverridesRepository()
        val service = service(repository)
        configure(service)

        val response = client.patch("/api/admin/features") {
            header(HttpHeaders.Authorization, bearerFor(ADMIN_ID.toString()))
            contentType(ContentType.Application.Json)
            setBody("""{"importByUrl":"yes"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    private fun ApplicationTestBuilder.configure(service: FeatureFlagsService) {
        application {
            installErrorPages()
            install(ContentNegotiation) { json() }
            install(testAuthPlugin)
            attributes.put(
                Services.Key,
                Services(
                    billingService = object : BillingService {
                        override suspend fun listPlans() = Result.success(emptyList<billing.model.BillingPlan>())
                        override suspend fun createInvoiceFor(
                            userId: Long,
                            tier: Tier
                        ) = Result.failure<String>(IllegalStateException("unused"))
                        override suspend fun applySuccessfulPayment(
                            userId: Long,
                            tier: Tier,
                            amountXtr: Long,
                            providerPaymentId: String?,
                            payload: String?
                        ) = Result.failure<Unit>(IllegalStateException("unused"))
                        override suspend fun getMySubscription(userId: Long) = Result.success<UserSubscription?>(null)
                    },
                    telegramBot = TelegramBot("test-token"),
                    featureFlags = service,
                    adminUserIds = setOf(ADMIN_ID),
                    deepLinkStore = InMemoryDeepLinkStore(),
                    deepLinkTtl = 14.days,
                )
            )
            routing { adminFeaturesRoutes() }
        }
    }

    private fun service(repository: FeatureOverridesRepository): FeatureFlagsService {
        val defaults = FeatureFlags(
            importByUrl = false,
            webhookQueue = true,
            newsPublish = true,
            alertsEngine = true,
            billingStars = true,
            miniApp = true
        )
        return FeatureFlagsServiceImpl(defaults, repository)
    }

    private fun bearerFor(subject: String): String {
        val token = JWT.create().withSubject(subject).sign(Algorithm.HMAC256("test-secret"))
        return "Bearer $token"
    }

    private class InMemoryFeatureOverridesRepository(
        private var stored: String = "{}"
    ) : FeatureOverridesRepository {
        override suspend fun upsertGlobal(json: String) {
            stored = json
        }

        override suspend fun findGlobal(): String = stored
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

    private companion object {
        private const val ADMIN_ID = 7446417641L
    }
}
