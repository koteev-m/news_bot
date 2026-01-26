package routes

import alerts.settings.AlertsConfig
import alerts.settings.AlertsOverridePatch
import alerts.settings.AlertsSettingsService
import alerts.settings.AlertsSettingsServiceImpl
import alerts.settings.Budget
import alerts.settings.DynamicScale
import alerts.settings.Hysteresis
import alerts.settings.MatrixV11
import alerts.settings.Percent
import alerts.settings.QuietHours
import alerts.settings.Thresholds
import app.Services
import billing.model.BillingPlan
import billing.model.SubStatus
import billing.model.Tier
import billing.model.UserSubscription
import billing.service.BillingService
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.response.BaseResponse
import deeplink.InMemoryDeepLinkStore
import features.FeatureFlags
import features.FeatureFlagsPatch
import features.FeatureFlagsService
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import errors.installErrorPages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import repo.AlertsSettingsRepository
import security.JwtConfig
import security.JwtSupport
import security.installSecurity
import java.time.Instant

class AlertsSettingsRoutesTest {
    private val jwtConfig = JwtConfig(
        issuer = "newsbot",
        audience = "newsbot-clients",
        realm = "newsbot-api",
        secret = "test-secret",
        accessTtlMinutes = 60
    )

    @Test
    fun `GET without JWT returns 401`() = testRoute { service, _ ->
        val response = client.get("/api/alerts/settings")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `PATCH without Pro plan returns 403`() = testRoute(billingTier = Tier.FREE) { _, config ->
        val token = JwtSupport.issueToken(config, subject = "123")
        val response = client.patch("/api/alerts/settings") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"cooldownMinutes":60}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `PATCH with invalid payload returns 400`() = testRoute { _, config ->
        val token = JwtSupport.issueToken(config, subject = "321")
        val response = client.patch("/api/alerts/settings") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"budgetPerDay":0,"hysteresis":{"enterPct":1.0,"exitPct":1.2}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("budgetPerDay"))
    }

    @Test
    fun `GET returns merged effective config`() = testRoute { service, config ->
        runBlocking { service.upsert(555L, AlertsOverridePatch(cooldownMinutes = 45)) }
        val token = JwtSupport.issueToken(config, subject = "555")
        val response = client.get("/api/alerts/settings") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val payload = Json.decodeFromString(AlertsConfig.serializer(), response.bodyAsText())
        assertEquals(45, payload.cooldownMinutes)
        assertEquals(defaultConfig().budget.maxPushesPerDay, payload.budget.maxPushesPerDay)
    }

    @Test
    fun `PATCH updates overrides immediately`() = testRoute { service, config ->
        val token = JwtSupport.issueToken(config, subject = "777")
        val response = client.patch("/api/alerts/settings") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"quietHours":{"start":"01:15"}}""")
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
        val effective = runBlocking { service.effectiveFor(777L) }
        assertEquals("01:15", effective.quiet.start)
    }

    private fun testRoute(
        billingTier: Tier = Tier.PRO,
        block: suspend ApplicationTestBuilder.(AlertsSettingsService, JwtConfig) -> Unit
    ) {
        testApplication {
            environment {
                config = MapApplicationConfig().apply {
                    put("security.jwtSecret", jwtConfig.secret)
                    put("security.issuer", jwtConfig.issuer)
                    put("security.audience", jwtConfig.audience)
                    put("security.realm", jwtConfig.realm)
                    put("security.accessTtlMinutes", jwtConfig.accessTtlMinutes.toString())
                }
            }

            val repository = InMemoryAlertsRepository()
            val service = AlertsSettingsServiceImpl(defaultConfig(), repository, CoroutineScope(Dispatchers.Unconfined))
            val billingService = FakeBillingService(billingTier)

            application {
                installErrorPages()
                installSecurity()
                attributes.put(
                    Services.Key,
                    Services(
                        billingService = billingService,
                        telegramBot = NoopTelegramBot(),
                        featureFlags = stubFeatureFlagsService(),
                        adminUserIds = emptySet(),
                        deepLinkStore = InMemoryDeepLinkStore(),
                        deepLinkTtl = 14.days,
                    )
                )
                attributes.put(AlertsSettingsDepsKey, AlertsSettingsRouteDeps(billingService, service))
                routing {
                    authenticate("auth-jwt") {
                        alertsSettingsRoutes()
                    }
                }
            }

            block(this, service, jwtConfig)
        }
    }

    private class InMemoryAlertsRepository : AlertsSettingsRepository {
        private val store = mutableMapOf<Long, String>()

        override suspend fun upsert(userId: Long, json: String) {
            store[userId] = json
        }

        override suspend fun find(userId: Long): String? = store[userId]
    }

    private class FakeBillingService(private val tier: Tier) : BillingService {
        override suspend fun listPlans(): Result<List<BillingPlan>> = Result.success(emptyList())
        override suspend fun createInvoiceFor(userId: Long, tier: Tier): Result<String> =
            Result.failure(UnsupportedOperationException("not supported"))
        override suspend fun applySuccessfulPayment(
            userId: Long,
            tier: Tier,
            amountXtr: Long,
            providerPaymentId: String?,
            payload: String?
        ): Result<Unit> = Result.failure(UnsupportedOperationException("not supported"))

        override suspend fun getMySubscription(userId: Long): Result<UserSubscription?> = Result.success(
            UserSubscription(
                userId = userId,
                tier = tier,
                status = SubStatus.ACTIVE,
                startedAt = Instant.EPOCH,
                expiresAt = Instant.EPOCH
            )
        )
    }

    private fun defaultConfig(): AlertsConfig = AlertsConfig(
        quiet = QuietHours(start = "23:00", end = "07:00"),
        budget = Budget(maxPushesPerDay = 6),
        hysteresis = Hysteresis(enterPct = Percent(2.0), exitPct = Percent(1.5)),
        cooldownMinutes = 60,
        dynamic = DynamicScale(enabled = false, min = 0.7, max = 1.3),
        matrix = MatrixV11(
            portfolioDayPct = Percent(2.0),
            portfolioDrawdown = Percent(5.0),
            perClass = mapOf(
                "MOEX_BLUE" to Thresholds(Percent(2.0), Percent(4.0), volMultFast = 1.8)
            )
        )
    )
}

private fun stubFeatureFlagsService(): FeatureFlagsService {
    val defaults = FeatureFlags(
        importByUrl = false,
        webhookQueue = true,
        newsPublish = true,
        alertsEngine = true,
        billingStars = true,
        miniApp = true
    )
    return object : FeatureFlagsService {
        private val flow = MutableStateFlow(0L)
        override val updatesFlow: StateFlow<Long> = flow
        override suspend fun defaults(): FeatureFlags = defaults
        override suspend fun effective(): FeatureFlags = defaults
        override suspend fun upsertGlobal(patch: FeatureFlagsPatch) {}
    }
}

private class NoopTelegramBot : TelegramBot("test-token") {
    @Suppress("UNCHECKED_CAST")
    override fun <T : BaseRequest<T, R>, R : BaseResponse> execute(request: BaseRequest<T, R>): R {
        val ctor = BaseResponse::class.java.getDeclaredConstructor()
        ctor.isAccessible = true
        return ctor.newInstance() as R
    }
}
