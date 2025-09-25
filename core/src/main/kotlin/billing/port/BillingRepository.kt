package billing.port

import billing.model.BillingPlan
import billing.model.SubStatus
import billing.model.Tier
import billing.model.UserSubscription
import java.time.Instant

interface BillingRepository {
    suspend fun getActivePlans(): List<BillingPlan>

    suspend fun upsertSubscription(
        userId: Long,
        tier: Tier,
        status: SubStatus,
        expiresAt: Instant?,
        lastPaymentId: String?
    )

    suspend fun findSubscription(userId: Long): UserSubscription?

    suspend fun recordStarPaymentIfNew(
        userId: Long,
        tier: Tier,
        amountXtr: Long,
        providerPaymentId: String?,
        payload: String?,
        status: SubStatus
    ): Boolean
}
