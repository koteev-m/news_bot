@file:UseSerializers(common.serialization.InstantIso8601Serializer::class)

package billing

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
data class UsageEvent(
    val tenantId: Long,
    val projectId: Long? = null,
    val userId: Long? = null,
    val metric: String,
    val quantity: Double,
    val occurredAt: Instant = Instant.now(),
    val dedupKey: String? = null
)

@Serializable
data class RateItem(
    val metric: String,
    val unit: String,
    val pricePerUnit: Double,
    val tierFrom: Double = 0.0,
    val tierTo: Double? = null
)

@Serializable
data class RateCard(
    val rateId: Long,
    val name: String,
    val currency: String,
    val items: List<RateItem>
)

@Serializable
data class InvoiceLine(
    val metric: String,
    val quantity: Double,
    val unit: String,
    val unitPrice: Double,
    val amount: Double
)

@Serializable
data class InvoiceDraft(
    val tenantId: Long,
    val currency: String,
    val periodFrom: Instant,
    val periodTo: Instant,
    val lines: List<InvoiceLine>,
    val subtotal: Double,
    val tax: Double,
    val total: Double
)
