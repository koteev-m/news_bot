package billing

import billing.model.BillingPlan
import billing.model.UserSubscription
import billing.model.Tier
import billing.service.BillingService
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StarsWebhookHandlerTest {

    @Test
    fun `successful payment invokes billing service`() = testApplication {
        val service = RecordingBillingService()
        application { testRoute(service) }

        val response = postWebhook(successfulPaymentJson())

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, service.calls.size)
        val call = service.calls.first()
        assertEquals(7446417641L, call.userId)
        assertEquals(Tier.PRO, call.tier)
        assertEquals(123L, call.amountXtr)
        assertEquals("pmt_1", call.providerPaymentId)
        assertEquals("7446417641:PRO:abc123", call.payload)
    }

    @Test
    fun `duplicate payment is acknowledged`() = testApplication {
        val service = RecordingBillingService()
        application { testRoute(service) }

        val first = postWebhook(successfulPaymentJson())
        val second = postWebhook(successfulPaymentJson())

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(2, service.calls.size)
    }

    @Test
    fun `non XTR payment is ignored`() = testApplication {
        val service = RecordingBillingService()
        application { testRoute(service) }

        val response = postWebhook(successfulPaymentJson(currency = "USD"))

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(service.calls.isEmpty())
    }

    @Test
    fun `invalid payload prevents processing`() = testApplication {
        val service = RecordingBillingService()
        application { testRoute(service) }

        val response = postWebhook(successfulPaymentJson(invoicePayload = "7446417641::abc123"))

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(service.calls.isEmpty())
    }

    @Test
    fun `malformed json is still acknowledged`() = testApplication {
        val service = RecordingBillingService()
        application { testRoute(service) }

        val response = postWebhook(rawBody = "{ not json }")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(service.calls.isEmpty())
    }

    @Test
    fun `billing service failure is swallowed`() = testApplication {
        val service = RecordingBillingService(fail = true)
        application { testRoute(service) }

        val response = postWebhook(successfulPaymentJson())

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, service.calls.size)
    }

    private suspend fun ApplicationTestBuilder.postWebhook(body: String? = null, rawBody: String? = null): HttpResponse {
        val payload = rawBody ?: body ?: successfulPaymentJson()
        return client.post("/test/webhook") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(payload)
        }
    }

    private fun successfulPaymentJson(
        currency: String = "XTR",
        invoicePayload: String = "7446417641:PRO:abc123"
    ): String {
        return """
            {"message":{"from":{"id":7446417641},"successful_payment":{"currency":"$currency","total_amount":123,"invoice_payload":"$invoicePayload","provider_payment_charge_id":"pmt_1"}}}
        """.trimIndent()
    }

    private fun Application.testRoute(service: BillingService) {
        routing {
            post("/test/webhook") {
                StarsWebhookHandler.handleIfStarsPayment(call, service)
            }
        }
    }

    private class RecordingBillingService(private val fail: Boolean = false) : BillingService {
        val calls = mutableListOf<ApplyCall>()

        override suspend fun applySuccessfulPayment(
            userId: Long,
            tier: Tier,
            amountXtr: Long,
            providerPaymentId: String?,
            payload: String?
        ): Result<Unit> {
            val call = ApplyCall(userId, tier, amountXtr, providerPaymentId, payload)
            calls += call
            return if (fail) Result.failure(RuntimeException("boom")) else Result.success(Unit)
        }

        override suspend fun listPlans(): Result<List<BillingPlan>> {
            throw UnsupportedOperationException("not used")
        }

        override suspend fun createInvoiceFor(userId: Long, tier: Tier): Result<String> {
            throw UnsupportedOperationException("not used")
        }

        override suspend fun getMySubscription(userId: Long): Result<UserSubscription?> {
            throw UnsupportedOperationException("not used")
        }
    }

    private data class ApplyCall(
        val userId: Long,
        val tier: Tier,
        val amountXtr: Long,
        val providerPaymentId: String?,
        val payload: String?
    )
}
