package analytics

import analytics.AnalyticsPort
import billing.StarsWebhookHandler
import billing.TgMessage
import billing.TgSuccessfulPayment
import billing.TgUpdate
import billing.TgUser
import billing.model.BillingPlan
import billing.model.Tier
import billing.model.UserSubscription
import billing.service.ApplyPaymentOutcome
import billing.service.BillingServiceWithOutcome
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.request.receiveText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.ArrayDeque
import java.util.LinkedHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import referrals.ReferralCode
import referrals.ReferralsPort
import referrals.UTM
import routes.authRoutes
import routes.redirectRoutes
import security.installSecurity

class IntegrationEventsTest {
    @Test
    fun `critical flows emit analytics events`() = testApplication {
        val analytics = RecordingAnalyticsPort()
        val billing = StubBillingService()
        val referrals = RecordingReferralsPort()
        val botToken = "123456:ABCDEF"

        environment {
            config = MapApplicationConfig(
                "telegram.botToken" to botToken,
                "security.jwtSecret" to "supersecretkeysupersecretkey",
                "security.issuer" to "test-issuer",
                "security.audience" to "test-audience",
                "security.realm" to "test-realm",
                "security.accessTtlMinutes" to "60",
                "security.webappTtlMinutes" to "15",
            )
        }

        application {
            installSecurity()
            routing {
                authRoutes(analytics)
                redirectRoutes(analytics, referrals, "https://t.me/test_bot", 64)
                post("/telegram/webhook/test") {
                    val raw = call.receiveText()
                    val update = StarsWebhookHandler.json.decodeFromString<TgUpdate>(raw)
                    StarsWebhookHandler.handleParsed(call, update, billing, analytics = analytics)
                }
            }
        }

        val badResponse = client.post("/api/auth/telegram/verify") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("{}")
        }
        assertEquals(HttpStatusCode.BadRequest, badResponse.status)
        assertTrue(analytics.events.isEmpty())

        val initData = telegramInitData(botToken, userId = 777L, now = Instant.now())
        val authResponse = client.post("/api/auth/telegram/verify") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            val body = Json.encodeToString(mapOf("initData" to initData))
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, authResponse.status)
        assertEquals("miniapp_auth", analytics.events.firstOrNull()?.type)

        val redirectClient = client.config {
            followRedirects = false
        }
        val redirectResponse = redirectClient.get("/go/promo")
        assertEquals(HttpStatusCode.Found, redirectResponse.status)
        val location = redirectResponse.headers[HttpHeaders.Location]
        assertTrue(location!!.startsWith("https://t.me/test_bot?start="))

        billing.enqueueResult(Result.success(ApplyPaymentOutcome(duplicate = false)))
        val update = TgUpdate(
            message = TgMessage(
                from = billing.userStub,
                successful_payment = TgSuccessfulPayment(
                    currency = "XTR",
                    total_amount = 1234L,
                    invoice_payload = "${billing.userStub.id}:${Tier.PRO.name}:abc",
                    provider_payment_charge_id = "pid-1"
                )
            )
        )
        val updateJson = StarsWebhookHandler.json.encodeToString(TgUpdate.serializer(), update)
        val firstWebhook = client.post("/telegram/webhook/test") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(updateJson)
        }
        assertEquals(HttpStatusCode.OK, firstWebhook.status)

        billing.enqueueResult(Result.success(ApplyPaymentOutcome(duplicate = true)))
        val duplicateWebhook = client.post("/telegram/webhook/test") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(updateJson)
        }
        assertEquals(HttpStatusCode.OK, duplicateWebhook.status)

        val types = analytics.events.map { it.type }
        assertEquals(listOf("miniapp_auth", "cta_click", "stars_payment_succeeded", "stars_payment_duplicate"), types)

        val authEvent = analytics.events[0]
        assertEquals(777L, authEvent.userId)
        assertEquals(mapOf("auth" to "ok"), authEvent.props)

        val ctaEvent = analytics.events[1]
        assertNull(ctaEvent.userId)
        assertEquals(mapOf("id" to "promo", "utm_source" to "", "utm_medium" to "", "utm_campaign" to "", "ref" to "", "cta" to ""), ctaEvent.props)

        val successEvent = analytics.events[2]
        assertEquals(billing.userStub.id, successEvent.userId)
        assertEquals(mapOf("tier" to Tier.PRO.name), successEvent.props)

        val duplicateEvent = analytics.events[3]
        assertEquals(billing.userStub.id, duplicateEvent.userId)
        assertEquals(mapOf("tier" to Tier.PRO.name), duplicateEvent.props)
    }

    private fun telegramInitData(botToken: String, userId: Long, now: Instant): String {
        val authDate = now.epochSecond.toString()
        val userJson = """{"id":$userId,"username":"tester","first_name":"Test"}"""
        val rawParams = mapOf(
            "auth_date" to authDate,
            "user" to userJson,
        )
        val dataCheckString = rawParams.entries
            .sortedBy { it.key }
            .joinToString("\n") { (key, value) -> "$key=$value" }
        val secret = hmacSha256("WebAppData".toByteArray(StandardCharsets.UTF_8), botToken.toByteArray(StandardCharsets.UTF_8))
        val hash = hmacSha256(secret, dataCheckString.toByteArray(StandardCharsets.UTF_8)).toHex()
        val params = LinkedHashMap(rawParams)
        params["hash"] = hash
        return params.entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }
    }

    private fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        val unsigned = byte.toInt() and 0xff
        unsigned.toString(16).padStart(2, '0')
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private class RecordingAnalyticsPort : AnalyticsPort {
        val events = mutableListOf<TrackedEvent>()

        override suspend fun track(
            type: String,
            userId: Long?,
            source: String?,
            sessionId: String?,
            props: Map<String, Any?>,
            ts: Instant
        ) {
            events += TrackedEvent(type, userId, source, sessionId, props)
        }
    }

    private data class TrackedEvent(
        val type: String,
        val userId: Long?,
        val source: String?,
        val sessionId: String?,
        val props: Map<String, Any?>,
    )

    private class StubBillingService : BillingServiceWithOutcome {
        private val outcomes = ArrayDeque<Result<ApplyPaymentOutcome>>()
        val userStub = TgUser(id = 42L)

        fun enqueueResult(result: Result<ApplyPaymentOutcome>) {
            outcomes.addLast(result)
        }

        override suspend fun applySuccessfulPaymentWithOutcome(
            userId: Long,
            tier: Tier,
            amountXtr: Long,
            providerPaymentId: String?,
            payload: String?
        ): Result<ApplyPaymentOutcome> {
            check(outcomes.isNotEmpty()) { "No billing outcome enqueued" }
            return outcomes.removeFirst()
        }

        override suspend fun applySuccessfulPayment(
            userId: Long,
            tier: Tier,
            amountXtr: Long,
            providerPaymentId: String?,
            payload: String?
        ): Result<Unit> = applySuccessfulPaymentWithOutcome(userId, tier, amountXtr, providerPaymentId, payload).map { }

        override suspend fun listPlans(): Result<List<BillingPlan>> =
            throw UnsupportedOperationException("not used")

        override suspend fun createInvoiceFor(userId: Long, tier: Tier): Result<String> =
            throw UnsupportedOperationException("not used")

        override suspend fun getMySubscription(userId: Long): Result<UserSubscription?> =
            throw UnsupportedOperationException("not used")
    }
}

private class RecordingReferralsPort : ReferralsPort {
    override suspend fun create(ownerUserId: Long, code: String): ReferralCode = ReferralCode(code, ownerUserId)

    override suspend fun find(code: String): ReferralCode? = null

    override suspend fun recordVisit(code: String, tgUserId: Long?, utm: UTM) { }

    override suspend fun attachUser(code: String, tgUserId: Long) { }
}
