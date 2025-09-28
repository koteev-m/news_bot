package webhook

import app.Services
import app.module
import billing.model.BillingPlan
import billing.model.Tier
import billing.model.UserSubscription
import billing.service.BillingService
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

class WebhookIntegrationTest {

    @Test
    fun `webhook acknowledges immediately and processes updates via queue`() = testApplication {
        val secret = "integration-secret"
        val billing = TestBillingService()

        environment {
            config = MapApplicationConfig().apply {
                put("telegram.webhookSecret", secret)
                put("webhook.queue.capacity", "3")
                put("webhook.queue.workers", "1")
                put("webhook.queue.overflow", "drop_latest")
                put("security.jwtSecret", "integration-test-secret")
                put("security.issuer", "test-issuer")
                put("security.audience", "test-audience")
                put("security.realm", "test-realm")
                put("security.accessTtlMinutes", "60")
            }
        }

        application {
            attributes.put(Services.Key, Services(billingService = billing, telegramBot = NoopTelegramBot()))
            module()
        }

        val client = createClient { }

        val warmupResponse = client.post("/telegram/webhook") {
            header("X-Telegram-Bot-Api-Secret-Token", secret)
            contentType(ContentType.Application.Json)
            setBody(paymentUpdateJson(999L))
        }
        assertEquals(HttpStatusCode.OK, warmupResponse.status)

        val firstAck = measureTime {
            val response = client.post("/telegram/webhook") {
                header("X-Telegram-Bot-Api-Secret-Token", secret)
                contentType(ContentType.Application.Json)
                setBody(paymentUpdateJson(1001L))
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
        assertTrue(firstAck < 200.milliseconds, "Expected quick ACK but was $firstAck")

        repeat(20) { index ->
            val response = client.post("/telegram/webhook") {
                header("X-Telegram-Bot-Api-Secret-Token", secret)
                contentType(ContentType.Application.Json)
                setBody(paymentUpdateJson(2000L + index))
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }

        withTimeout(5.seconds) {
            while (billing.applied.get() == 0) {
                delay(50.milliseconds)
            }
        }
        delay(500.milliseconds)

        val metricsBody = client.get("/metrics").bodyAsText()
        val processed = metricValue(metricsBody, "webhook_queue_processed_total")
        val dropped = metricValue(metricsBody, "webhook_queue_dropped_total")

        assertTrue(billing.applied.get().toDouble() <= processed + 0.001)
        assertTrue(dropped > 0.0)
    }
}

private class TestBillingService : BillingService {
    val applied: AtomicInteger = AtomicInteger(0)

    override suspend fun listPlans(): Result<List<BillingPlan>> =
        Result.failure(UnsupportedOperationException("not implemented"))

    override suspend fun createInvoiceFor(userId: Long, tier: Tier): Result<String> =
        Result.failure(UnsupportedOperationException("not implemented"))

    override suspend fun applySuccessfulPayment(
        userId: Long,
        tier: Tier,
        amountXtr: Long,
        providerPaymentId: String?,
        payload: String?
    ): Result<Unit> = runCatching {
        delay(200.milliseconds)
        applied.incrementAndGet()
    }

    override suspend fun getMySubscription(userId: Long): Result<UserSubscription?> =
        Result.failure(UnsupportedOperationException("not implemented"))
}

private class NoopTelegramBot : com.pengrad.telegrambot.TelegramBot("test-token")

private fun paymentUpdateJson(userId: Long): String = """
    {
      "message": {
        "from": {"id": $userId},
        "successful_payment": {
          "currency": "XTR",
          "total_amount": 1000,
          "invoice_payload": "$userId:PRO:payload",
          "provider_payment_charge_id": "pid-$userId"
        }
      }
    }
""".trimIndent()

private fun metricValue(metrics: String, name: String): Double {
    val line = metrics.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith(name) && !it.contains('{') }
        ?: return 0.0
    return line.substringAfter(' ').toDoubleOrNull() ?: 0.0
}
