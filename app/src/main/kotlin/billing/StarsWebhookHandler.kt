package billing

import analytics.AnalyticsPort
import billing.model.Tier
import billing.service.ApplyPaymentOutcome
import billing.service.BillingService
import billing.service.applySuccessfulPaymentOutcome
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import observability.DomainMetrics
import java.util.UUID
import org.slf4j.LoggerFactory

@Serializable
data class TgUpdate(val message: TgMessage? = null)

@Serializable
data class TgMessage(
    val from: TgUser? = null,
    val successful_payment: TgSuccessfulPayment? = null
)

@Serializable
data class TgUser(val id: Long? = null)

@Serializable
data class TgSuccessfulPayment(
    val currency: String,
    val total_amount: Long,
    val invoice_payload: String? = null,
    val provider_payment_charge_id: String? = null
)

object StarsWebhookHandler {

    private val logger = LoggerFactory.getLogger(StarsWebhookHandler::class.java)

    internal val json = Json { ignoreUnknownKeys = true }

    /**
     * Возвращает true, если Update содержит успешный XTR-платёж и он обработан (или идемпотентно пропущен).
     * В любом случае отвечает 200 (быстрый ACK), чтобы Telegram не ретраил.
     */
    suspend fun handleIfStarsPayment(
        call: ApplicationCall,
        billing: BillingService,
        metrics: DomainMetrics? = null,
        analytics: AnalyticsPort = AnalyticsPort.Noop
    ): Boolean {
        val requestId = requestId()
        val body = runCatching { call.receiveText() }.getOrElse {
            call.respond(HttpStatusCode.OK)
            return false
        }

        val update = runCatching { json.decodeFromString(TgUpdate.serializer(), body) }.getOrElse {
            call.respond(HttpStatusCode.OK)
            return false
        }

        return handleParsed(call, update, billing, requestId, metrics, analytics)
    }

    suspend fun handleParsed(
        call: ApplicationCall,
        update: TgUpdate,
        billing: BillingService,
        metrics: DomainMetrics? = null,
        analytics: AnalyticsPort = AnalyticsPort.Noop
    ): Boolean {
        val requestId = requestId()
        return handleParsed(call, update, billing, requestId, metrics, analytics)
    }

    private suspend fun handleParsed(
        call: ApplicationCall,
        update: TgUpdate,
        billing: BillingService,
        requestId: String,
        metrics: DomainMetrics?,
        analytics: AnalyticsPort
    ): Boolean {
        val successfulPayment = update.message?.successful_payment
        if (successfulPayment == null) {
            call.respond(HttpStatusCode.OK)
            return false
        }

        if (!successfulPayment.currency.equals("XTR", ignoreCase = true)) {
            logger.info("stars-webhook requestId={} reason=currency_mismatch", requestId)
            call.respond(HttpStatusCode.OK)
            return true
        }

        val userId = update.message?.from?.id
        val payload = successfulPayment.invoice_payload
        val payloadData = parsePayload(payload)
        if (payloadData == null) {
            logger.info("stars-webhook requestId={} reason=payload_missing", requestId)
            call.respond(HttpStatusCode.OK)
            return true
        }
        val (payloadUserId, tier) = payloadData

        if (userId == null || payloadUserId != userId) {
            logger.info("stars-webhook requestId={} reason=user_mismatch", requestId)
            call.respond(HttpStatusCode.OK)
            return true
        }

        val outcomeResult = billing.applySuccessfulPaymentOutcome(
            userId = userId,
            tier = tier,
            amountXtr = successfulPayment.total_amount,
            providerPaymentId = successfulPayment.provider_payment_charge_id,
            payload = successfulPayment.invoice_payload
        )

        val outcome = outcomeResult.getOrElse { error ->
            logger.error(
                "stars-webhook requestId={} reason=applied_ok status=error",
                requestId,
                error
            )
            call.respond(HttpStatusCode.OK)
            return true
        }

        val eventType = if (outcome.duplicate) {
            "stars_payment_duplicate"
        } else {
            "stars_payment_succeeded"
        }
        analytics.track(
            type = eventType,
            userId = userId,
            source = "webhook",
            props = mapOf("tier" to tier.name)
        )

        handleSuccess(metrics, requestId, outcome)

        call.respond(HttpStatusCode.OK)
        return true
    }

    private fun handleSuccess(metrics: DomainMetrics?, requestId: String, outcome: ApplyPaymentOutcome) {
        if (outcome.duplicate) {
            metrics?.webhookStarsDuplicate?.increment()
            logger.info("stars-webhook requestId={} reason=duplicate", requestId)
        } else {
            metrics?.webhookStarsSuccess?.increment()
            logger.info("stars-webhook requestId={} reason=applied_ok", requestId)
        }
    }

    private fun parsePayload(payload: String?): Pair<Long, Tier>? {
        if (payload.isNullOrBlank()) return null
        val parts = payload.split(':')
        if (parts.size < 3) return null
        val userId = parts[0].toLongOrNull() ?: return null
        val tier = runCatching { Tier.valueOf(parts[1].uppercase()) }.getOrNull() ?: return null
        return userId to tier
    }

    private fun requestId(): String = UUID.randomUUID().toString()
}
