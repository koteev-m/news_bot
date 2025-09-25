package billing

import billing.model.Tier
import billing.service.BillingService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import org.slf4j.LoggerFactory

object StarsWebhookHandler {

    private val logger = LoggerFactory.getLogger(StarsWebhookHandler::class.java)

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class TgUpdate(val message: TgMessage? = null)

    @Serializable
    private data class TgMessage(val from: TgUser? = null, val successful_payment: TgSuccessfulPayment? = null)

    @Serializable
    private data class TgUser(val id: Long? = null)

    @Serializable
    private data class TgSuccessfulPayment(
        val currency: String,
        val total_amount: Long,
        val invoice_payload: String? = null,
        val provider_payment_charge_id: String? = null
    )

    /**
     * Возвращает true, если Update содержит успешный XTR-платёж и он обработан (или идемпотентно пропущен).
     * В любом случае отвечает 200 (быстрый ACK), чтобы Telegram не ретраил.
     */
    suspend fun handleIfStarsPayment(call: ApplicationCall, billing: BillingService): Boolean {
        val requestId = UUID.randomUUID().toString()
        val body = runCatching { call.receiveText() }.getOrElse {
            logger.warn("stars-webhook requestId={} read_body_failed", requestId)
            call.respond(HttpStatusCode.OK)
            return false
        }

        val update = runCatching { json.decodeFromString(TgUpdate.serializer(), body) }.getOrNull()
        val successfulPayment = update?.message?.successful_payment
        if (successfulPayment == null) {
            logger.debug("stars-webhook requestId={} no_successful_payment", requestId)
            call.respond(HttpStatusCode.OK)
            return false
        }

        if (!successfulPayment.currency.equals("XTR", ignoreCase = true)) {
            logger.info(
                "stars-webhook requestId={} currency_mismatch currency={}",
                requestId,
                successfulPayment.currency
            )
            call.respond(HttpStatusCode.OK)
            return true
        }

        val userId = update.message?.from?.id
        val providerPaymentId = successfulPayment.provider_payment_charge_id
        val payload = successfulPayment.invoice_payload
        val amountXtr = successfulPayment.total_amount

        val tier = runCatching { extractTierFromPayload(payload) }.getOrNull()

        if (userId == null || tier == null || amountXtr < 0) {
            logger.warn("stars-webhook requestId={} invalid_data", requestId)
            call.respond(HttpStatusCode.OK)
            return true
        }

        val applied = try {
            billing.applySuccessfulPayment(
                userId = userId,
                tier = tier,
                amountXtr = amountXtr,
                providerPaymentId = providerPaymentId,
                payload = payload
            )
        } catch (error: Throwable) {
            Result.failure(error)
        }

        if (applied.isFailure) {
            logger.error("stars-webhook requestId={} billing_failure", requestId, applied.exceptionOrNull())
        }

        call.respond(HttpStatusCode.OK)
        return true
    }

    /** payload формат: "<userId>:<TIER>:<uuid>" → берём TIER и парсим в enum */
    private fun extractTierFromPayload(payload: String?): Tier? {
        if (payload.isNullOrBlank()) return null
        val parts = payload.split(':')
        if (parts.size < 2) return null
        val tierStr = parts[1]
        return runCatching { Tier.valueOf(tierStr.uppercase()) }.getOrNull()
    }
}
