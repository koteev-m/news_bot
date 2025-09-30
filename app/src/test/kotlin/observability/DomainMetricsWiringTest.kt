package observability

import app.Services
import billing.StarsWebhookHandler
import billing.TgUpdate
import billing.service.ApplyPaymentOutcome
import billing.service.BillingServiceWithOutcome
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.request.receiveText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import observability.adapters.AlertMetricsAdapter
import observability.adapters.NewsMetricsAdapter

class DomainMetricsWiringTest {

    @Test
    fun `webhook counters reflect primary and duplicate deliveries`() = testApplication {
        val billing = FakeBillingService()

        application {
            val registry = Observability.install(this)
            val domainMetrics = DomainMetrics(registry)
            attributes.put(
                Services.Key,
                Services(
                    billingService = billing,
                    telegramBot = NoopTelegramBot(),
                    metrics = domainMetrics,
                    alertMetrics = AlertMetricsAdapter(domainMetrics),
                    newsMetrics = NewsMetricsAdapter(domainMetrics)
                )
            )
            installWebhookRoute(billing, domainMetrics)
        }

        val client = createClient { }
        val payload = paymentUpdateJson(userId = 1001L, providerId = "pid-1001")

        val first = client.post("/test/webhook") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        assertEquals(HttpStatusCode.OK, first.status)

        val duplicate = client.post("/test/webhook") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        assertEquals(HttpStatusCode.OK, duplicate.status)

        val metricsBody = client.get("/metrics").bodyAsText()
        assertEquals(1.0, metricValue(metricsBody, "webhook_stars_success_total"))
        assertEquals(1.0, metricValue(metricsBody, "webhook_stars_duplicate_total"))
    }

    private fun Application.installWebhookRoute(
        billing: FakeBillingService,
        metrics: DomainMetrics
    ) {
        routing {
            post("/test/webhook") {
                val raw = call.receiveText()
                val update = StarsWebhookHandler.json.decodeFromString(TgUpdate.serializer(), raw)
                StarsWebhookHandler.handleParsed(call, update, billing, metrics)
            }
        }
    }

    private fun paymentUpdateJson(userId: Long, providerId: String): String {
        val update = TgUpdate(
            message = billing.TgMessage(
                from = billing.TgUser(id = userId),
                successful_payment = billing.TgSuccessfulPayment(
                    currency = "XTR",
                    total_amount = 1000,
                    invoice_payload = "$userId:PRO:payload",
                    provider_payment_charge_id = providerId
                )
            )
        )
        return Json.encodeToString(update)
    }

    private fun metricValue(metrics: String, name: String): Double {
        val line = metrics.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(name) && !it.contains('{') }
            ?: return 0.0
        return line.substringAfter(' ').toDoubleOrNull() ?: 0.0
    }

    private class FakeBillingService : BillingServiceWithOutcome {
        private val processed = mutableSetOf<String>()

        override suspend fun applySuccessfulPaymentWithOutcome(
            userId: Long,
            tier: billing.model.Tier,
            amountXtr: Long,
            providerPaymentId: String?,
            payload: String?
        ): Result<ApplyPaymentOutcome> = runCatching {
            val key = providerPaymentId ?: "${userId}:${tier.name}:${payload ?: ""}"
            val isNew = processed.add(key)
            ApplyPaymentOutcome(duplicate = !isNew)
        }

        override suspend fun applySuccessfulPayment(
            userId: Long,
            tier: billing.model.Tier,
            amountXtr: Long,
            providerPaymentId: String?,
            payload: String?
        ): Result<Unit> = applySuccessfulPaymentWithOutcome(userId, tier, amountXtr, providerPaymentId, payload).map { }

        override suspend fun listPlans(): Result<List<billing.model.BillingPlan>> =
            Result.failure(UnsupportedOperationException("not used"))

        override suspend fun createInvoiceFor(userId: Long, tier: billing.model.Tier): Result<String> =
            Result.failure(UnsupportedOperationException("not used"))

        override suspend fun getMySubscription(userId: Long): Result<billing.model.UserSubscription?> =
            Result.failure(UnsupportedOperationException("not used"))
    }

    private class NoopTelegramBot : com.pengrad.telegrambot.TelegramBot("test-token")
}
