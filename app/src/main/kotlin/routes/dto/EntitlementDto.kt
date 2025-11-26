package routes.dto

import billing.service.Entitlement
import kotlinx.serialization.Serializable

@Serializable
data class EntitlementDto(
    val tier: String,
    val status: String,
    val expiresAt: String?,
)

fun Entitlement.toDto(): EntitlementDto = EntitlementDto(
    tier = tier.name,
    status = status.name,
    expiresAt = expiresAt?.toString(),
)
