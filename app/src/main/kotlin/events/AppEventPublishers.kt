package events

import kafka.KafkaPublisher
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import java.time.Instant

@Serializable data class PaywallCtaPayload(val plan: String, val variant: String)

@Serializable data class PaymentSucceededPayload(val userId: Long, val plan: String)

class AppEventPublishers(private val publisher: KafkaPublisher) {
    fun publishPaywallCta(tenantId: Long, plan: String, variant: String) {
        val evt = DomainEvent(
            type = "paywall.cta",
            version = 1,
            id = java.util.UUID.randomUUID().toString(),
            tenantId = tenantId,
            occurredAt = Instant.now().toString(),
            payload = PaywallCtaPayload(plan, variant)
        )
        val json = EventCodec.encode(evt, PaywallCtaPayload.serializer())
        publisher.publish("app.paywall.cta", evt.id, json)
    }
    fun publishPaymentSucceeded(tenantId: Long, userId: Long, plan: String) {
        val evt = DomainEvent(
            type = "payment.succeeded",
            version = 1,
            id = java.util.UUID.randomUUID().toString(),
            tenantId = tenantId,
            occurredAt = Instant.now().toString(),
            payload = PaymentSucceededPayload(userId, plan)
        )
        val json = EventCodec.encode(evt, PaymentSucceededPayload.serializer())
        publisher.publish("app.payment.succeeded", evt.id, json)
    }
}
