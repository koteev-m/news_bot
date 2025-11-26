package billing.service

import billing.model.SubStatus
import billing.model.Tier
import java.time.Instant

data class Entitlement(
    val tier: Tier,
    val status: SubStatus,
    val expiresAt: Instant?,
)

class EntitlementsService(
    private val billingService: BillingService,
) {
    suspend fun getEntitlement(userId: Long): Result<Entitlement> {
        return billingService.getMySubscription(userId).map { subscription ->
            if (subscription == null) {
                Entitlement(Tier.FREE, SubStatus.EXPIRED, null)
            } else {
                val status = subscription.status
                if (status == SubStatus.ACTIVE) {
                    Entitlement(subscription.tier, status, subscription.expiresAt)
                } else {
                    Entitlement(Tier.FREE, status, subscription.expiresAt)
                }
            }
        }
    }
}
