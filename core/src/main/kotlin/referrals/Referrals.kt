package referrals

import kotlinx.serialization.Serializable

@Serializable
data class ReferralCode(
    val code: String,
    val ownerUserId: Long,
)

interface ReferralsPort {
    suspend fun create(
        ownerUserId: Long,
        code: String,
    ): ReferralCode

    suspend fun find(code: String): ReferralCode?

    suspend fun recordVisit(
        code: String,
        tgUserId: Long?,
        utm: UTM,
    )

    suspend fun attachUser(
        code: String,
        tgUserId: Long,
    )
}

@Serializable
data class UTM(
    val source: String?,
    val medium: String?,
    val campaign: String?,
    val ctaId: String? = null,
)
