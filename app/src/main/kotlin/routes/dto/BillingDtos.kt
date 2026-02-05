package routes.dto

import billing.model.BillingPlan
import billing.model.UserSubscription
import kotlinx.serialization.Serializable

@Serializable
data class BillingPlanDto(
    val tier: String,
    val title: String,
    val priceXtr: Long,
    val isActive: Boolean,
)

@Serializable
data class UserSubscriptionDto(
    val userId: Long,
    val tier: String,
    val status: String,
    val startedAt: String,
    val expiresAt: String?,
)

fun BillingPlan.toDto(): BillingPlanDto =
    BillingPlanDto(
        tier = this.tier.name,
        title = this.title,
        priceXtr = this.priceXtr.value,
        isActive = this.isActive,
    )

fun UserSubscription.toDto(): UserSubscriptionDto =
    UserSubscriptionDto(
        userId = this.userId,
        tier = this.tier.name,
        status = this.status.name,
        startedAt = this.startedAt.toString(),
        expiresAt = this.expiresAt?.toString(),
    )
