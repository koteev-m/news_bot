package access

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable data class AccessReview(
    val reviewId: Long, val tenantId: Long, val reviewerId: Long,
    val dueAt: Instant, val status: String
)
@Serializable data class AccessReviewItem(
    val itemId: Long, val reviewId: Long, val userId: Long, val role: String,
    val decision: String, val decidedAt: Instant?
)
@Serializable data class SodPolicy(
    val policyId: Long, val tenantId: Long, val name: String,
    val rolesConflict: List<String>, val enabled: Boolean
)
@Serializable data class PamSession(
    val sessionId: Long, val tenantId: Long, val userId: Long, val reason: String,
    val grantedRoles: List<String>, val startedAt: Instant, val expiresAt: Instant,
    val approvedBy: Long?, val status: String
)
