package billing.subscriptions

import java.time.Instant

data class StarSubscriptionRow(
    val id: Long,
    val userId: Long,
    val plan: String,
    val status: String,
    val autoRenew: Boolean,
    val renewAt: Instant?,
    val trialUntil: Instant?,
    val createdAt: Instant,
)

interface StarSubscriptionsRepository {
    suspend fun findActiveByUser(userId: Long): StarSubscriptionRow?

    suspend fun upsertActive(
        userId: Long,
        plan: String,
        autoRenew: Boolean,
        renewAt: Instant?,
        trialUntil: Instant?,
    ): StarSubscriptionRow

    suspend fun cancelActive(userId: Long): Boolean

    suspend fun findDueRenew(now: Instant): List<StarSubscriptionRow>

    suspend fun countPaidActive(): Long
}
