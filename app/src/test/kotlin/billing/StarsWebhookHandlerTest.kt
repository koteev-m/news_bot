package billing

import billing.model.BillingPlan
import billing.model.Tier
import billing.model.UserSubscription
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
    fun `successful XTR payment triggers billing service`() = testApplication {
        val service = RecordingBillingService { Result.success(Unit) }
        application { configureTestRouting(service) }

        val response = postWebhook(successfulPaymentJson())

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, service.applyCalls.size)
        val call = service.applyCalls.first()
        assertEquals(7446417641L, call.userId)
        assertEquals(Tier.PRO, call.tier)
        assertEquals(123L, call.amountXtr)
        assertEquals("pmt_1", call.providerPaymentId)
        assertEquals("7446417641:PRO:abc123", call.payload)
    }

    @Test
    fun `duplicate payment is handled idempotently`() = testApplication {
        val service = RecordingBillingService { Result.success(Unit) }
        application { configureTestRouting(service) }

        val response1 = postWebhook(successfulPaymentJson())
        val response2 = postWebhook(successfulPaymentJson())

        assertEquals(HttpStatusCode.OK, response1.status)
        assertEquals(HttpStatusCode.OK, response2.status)
        assertEquals(2, service.applyCalls.size)
    }

    @Test
    fun `non XTR payment is ignored`() = testApplication {
        val service = RecordingBillingService { Result.success(Unit) }
        application { configureTestRouting(service) }

        val response = postWebhook(successfulPaymentJson(currency = "USD"))

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(service.applyCalls.isEmpty())
    }

    @Test
    fun `missing tier in payload prevents processing`() = testApplication {
        val service = RecordingBillingService { Result.success(Unit) }
        application { configureTestRouting(service) }

        val response = postWebhook(
            successfulPaymentJson(invoicePayload = "7446417641::abc123")
        )

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(service.applyCalls.isEmpty())
    }

    @Test
    fun `invalid JSON body is acknowledged`() = testApplication {
        val service = RecordingBillingService { Result.success(Unit) }
        application { configureTestRouting(service) }

        val response = postWebhook(rawBody = "{ not json }")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(service.applyCalls.isEmpty())
    }

    @Test
    fun `billing service exception does not break response`() = testApplication {
        val service = RecordingBillingService { throw RuntimeException("boom") }
        application { configureTestRouting(service) }

        val response = postWebhook(successfulPaymentJson())

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, service.applyCalls.size)
    }

    private suspend fun ApplicationTestBuilder.postWebhook(
        body: String? = null,
        rawBody: String? = null
    ): HttpResponse {
        val payload = rawBody ?: body ?: successfulPaymentJson()
        return client.post("/telegram/webhook") {
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

    private fun Application.configureTestRouting(billingService: BillingService) {
        routing {
            post("/telegram/webhook") {
                StarsWebhookHandler.handleIfStarsPayment(call, billingService)
            }
        }
    }

    private class RecordingBillingService(
        private val handler: suspend (ApplyCall) -> Result<Unit>
    ) : BillingService {

        val applyCalls = mutableListOf<ApplyCall>()

        override suspend fun applySuccessfulPayment(
            userId: Long,
            tier: Tier,
            amountXtr: Long,
            providerPaymentId: String?,
            payload: String?
        ): Result<Unit> {
            val call = ApplyCall(userId, tier, amountXtr, providerPaymentId, payload)
            applyCalls += call
            return handler(call)
        }

        override suspend fun listPlans(): Result<List<BillingPlan>> {
            throw UnsupportedOperationException("not needed")
        }

        override suspend fun createInvoiceFor(userId: Long, tier: Tier): Result<String> {
            throw UnsupportedOperationException("not needed")
        }

        override suspend fun getMySubscription(userId: Long): Result<UserSubscription?> {
            throw UnsupportedOperationException("not needed")
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
