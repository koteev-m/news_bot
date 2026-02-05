package access

import tenancy.Role
import tenancy.TenantContext
import java.time.Instant

interface AccessRepo {
    suspend fun createReview(
        tenantId: Long,
        reviewerId: Long,
        dueAt: Instant,
    ): Long

    suspend fun addItem(
        reviewId: Long,
        userId: Long,
        role: String,
    ): Long

    suspend fun setDecision(
        itemId: Long,
        decision: String,
        decidedAt: Instant,
    )

    suspend fun listUserRoles(tenantId: Long): List<Pair<Long, Set<Role>>>

    suspend fun listSodPolicies(tenantId: Long): List<SodPolicy>

    suspend fun createPamRequest(
        tenantId: Long,
        userId: Long,
        reason: String,
        roles: List<String>,
        expiresAt: Instant,
    ): Long

    suspend fun approvePam(
        sessionId: Long,
        approverId: Long,
    )

    suspend fun revokePam(
        sessionId: Long,
        revokerId: Long?,
    )
}

class AccessReviewService(
    private val repo: AccessRepo,
) {
    suspend fun startReview(
        ctx: TenantContext,
        dueAt: Instant,
    ): Long = repo.createReview(ctx.tenant.tenantId, ctx.userId ?: error("user required"), dueAt)

    suspend fun populateReview(
        reviewId: Long,
        tenantId: Long,
    ) {
        repo.listUserRoles(tenantId).forEach { (userId, roles) ->
            roles.forEach { role -> repo.addItem(reviewId, userId, role.name) }
        }
    }

    suspend fun decide(
        itemId: Long,
        keep: Boolean,
    ) = repo.setDecision(itemId, if (keep) "KEEP" else "REVOKE", Instant.now())
}

class SodService(
    private val repo: AccessRepo,
) {
    suspend fun checkSod(
        tenantId: Long,
        userRoles: Set<Role>,
    ): Boolean {
        val policies = repo.listSodPolicies(tenantId).filter { it.enabled }
        return policies.none { pol ->
            val names = userRoles.map { it.name }.toSet()
            pol.rolesConflict.toSet().let { conflict -> conflict.intersect(names).size == conflict.size }
        }
    }
}

class PamService(
    private val repo: AccessRepo,
) {
    suspend fun request(
        ctx: TenantContext,
        reason: String,
        roles: List<String>,
        ttlMinutes: Long,
    ): Long {
        val until = Instant.now().plusSeconds(ttlMinutes * 60)
        return repo.createPamRequest(ctx.tenant.tenantId, ctx.userId ?: error("user required"), reason, roles, until)
    }

    suspend fun approve(
        sessionId: Long,
        approverId: Long,
    ) = repo.approvePam(sessionId, approverId)

    suspend fun revoke(
        sessionId: Long,
        by: Long?,
    ) = repo.revokePam(sessionId, by)
}
