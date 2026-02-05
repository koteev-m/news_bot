package billing.model

import kotlinx.serialization.Serializable
import java.time.Instant

enum class Tier {
    FREE,
    PRO,
    PRO_PLUS,
    VIP,
    ;

    fun level(): Int =
        when (this) {
            FREE -> 0
            PRO -> 1
            PRO_PLUS -> 2
            VIP -> 3
        }

    fun meets(required: Tier): Boolean = level() >= required.level()

    companion object {
        fun parse(value: String): Tier {
            val trimmed = value.trim()
            return entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown tier: $value")
        }
    }
}

enum class SubStatus {
    ACTIVE,
    EXPIRED,
    CANCELLED,
    PENDING,
    ;

    companion object {
        fun parse(value: String): SubStatus {
            val trimmed = value.trim()
            return entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown subscription status: $value")
        }
    }
}

@JvmInline
value class Xtr(
    val value: Long,
) {
    init {
        require(value >= 0) { "XTR must be >= 0" }
    }

    override fun toString(): String = value.toString()
}

data class BillingPlan(
    val tier: Tier,
    val title: String,
    val priceXtr: Xtr,
    val isActive: Boolean,
) {
    init {
        require(title.isNotBlank()) { "Billing plan title must not be blank" }
    }
}

data class UserSubscription(
    val userId: Long,
    val tier: Tier,
    val status: SubStatus,
    val startedAt: Instant,
    val expiresAt: Instant?,
)

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
        tier = tier.name,
        title = title,
        priceXtr = priceXtr.value,
        isActive = isActive,
    )

fun BillingPlanDto.toDomain(): BillingPlan =
    BillingPlan(
        tier = Tier.parse(tier),
        title = title,
        priceXtr = Xtr(priceXtr),
        isActive = isActive,
    )

fun UserSubscription.toDto(): UserSubscriptionDto =
    UserSubscriptionDto(
        userId = userId,
        tier = tier.name,
        status = status.name,
        startedAt = startedAt.toIsoString(),
        expiresAt = expiresAt?.toIsoString(),
    )

fun UserSubscriptionDto.toDomain(): UserSubscription =
    UserSubscription(
        userId = userId,
        tier = Tier.parse(tier),
        status = SubStatus.parse(status),
        startedAt = startedAt.toInstantUnsafe(),
        expiresAt = expiresAt?.toInstantUnsafe(),
    )

fun Instant.toIsoString(): String = toString()

fun String.toInstantUnsafe(): Instant = Instant.parse(this)
