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
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.receiveText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StarsWebhookHandlerTest {

    @Test
    fun `successful payment invokes billing service`() = testApplication {
        val service = RecordingBillingService()
        application { installParsedRoute("/test/webhook", service) }

        val response = postWebhook(successfulPaymentJson())

        assertEquals(HttpStatusCode.OK, response.status)
        val call = service.calls.single()
        assertEquals(7446417641L, call.userId)
        assertEquals(Tier.PRO, call.tier)
        assertEquals(123L, call.amountXtr)
        assertEquals("pmt_1", call.providerPaymentId)
        assertEquals("7446417641:PRO:abc123", call.payload)
    }

    @Test
    fun `payload user mismatch prevents processing`() = testApplication {
        val service = RecordingBillingService()
        application { installParsedRoute("/test/webhook", service) }

        val response = postWebhook(successfulPaymentJson(invoicePayload = "1:PRO:abc123"))

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(service.calls.isEmpty())
    }

    @Test
    fun `handleParsed uses provided update without re-reading body`() = testApplication {
        val service = RecordingBillingService()
        val receiveCounter = AtomicInteger(0)
        application {
            val app = this
            app.install(
                createApplicationPlugin(name = "ReceiveCounter") {
                    onCallReceive { _, _ -> receiveCounter.incrementAndGet() }
                }
            )
            routing {
                post("/test/parsed") {
                    val raw = call.receiveText()
                    val update = StarsWebhookHandler.json.decodeFromString(TgUpdate.serializer(), raw)
                    StarsWebhookHandler.handleParsed(call, update, service)
                }
            }
        }

        val response = postWebhook(successfulPaymentJson(), path = "/test/parsed")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, service.calls.size)
        assertEquals(1, receiveCounter.get())
    }

    @Test
    fun `provider payment id null is passed to billing`() = testApplication {
        val service = RecordingBillingService()
        application { installParsedRoute("/test/webhook", service) }

        val response = postWebhook(successfulPaymentJson(providerChargeId = null))

        assertEquals(HttpStatusCode.OK, response.status)
        val call = service.calls.single()
        assertEquals(null, call.providerPaymentId)
    }

    private suspend fun ApplicationTestBuilder.postWebhook(
        body: String,
        path: String = "/test/webhook"
    ): HttpResponse {
        return client.post(path) {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(body)
        }
    }

    private fun Application.installParsedRoute(path: String, service: BillingService) {
        routing {
            post(path) {
                val raw = call.receiveText()
                val update = StarsWebhookHandler.json.decodeFromString(TgUpdate.serializer(), raw)
                StarsWebhookHandler.handleParsed(call, update, service)
            }
        }
    }

    private fun successfulPaymentJson(
        currency: String = "XTR",
        invoicePayload: String = "7446417641:PRO:abc123",
        providerChargeId: String? = "pmt_1"
    ): String {
        val providerField = providerChargeId?.let { "\"provider_payment_charge_id\":\"$it\"" }
            ?: "\"provider_payment_charge_id\":null"
        return """
            {"message":{"from":{"id":7446417641},"successful_payment":{"currency":"$currency","total_amount":123,"invoice_payload":"$invoicePayload",$providerField}}}
        """.trimIndent()
    }

    private class RecordingBillingService : BillingService {
        val calls = mutableListOf<ApplyCall>()

        override suspend fun applySuccessfulPayment(
            userId: Long,
            tier: Tier,
            amountXtr: Long,
            providerPaymentId: String?,
            payload: String?
        ): Result<Unit> {
            calls += ApplyCall(userId, tier, amountXtr, providerPaymentId, payload)
            return Result.success(Unit)
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
