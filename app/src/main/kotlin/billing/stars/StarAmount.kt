package billing.stars

import kotlinx.serialization.Serializable

@Serializable
data class StarAmount(
    val amount: Long,
    val nano: Long? = null,
)
