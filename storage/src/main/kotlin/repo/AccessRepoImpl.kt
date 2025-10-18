package repo

import access.AccessRepo
import access.SodPolicy
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ArrayColumnType
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.StringColumnType
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import db.DatabaseFactory.dbQuery
import java.time.Instant
import tenancy.Role

object AccessReviews : LongIdTable("access_reviews", "review_id") {
    val tenantId = long("tenant_id")
    val reviewerId = long("reviewer_id")
    val dueAt = timestampWithTimeZone("due_at")
    val createdAt = timestampWithTimeZone("created_at")
    val status = text("status")
}
object AccessReviewItems : LongIdTable("access_review_items", "item_id") {
    val reviewId = long("review_id").references(AccessReviews.id, onDelete = ReferenceOption.CASCADE)
    val userId = long("user_id")
    val role = text("role")
    val decision = text("decision")
    val decidedAt = timestampWithTimeZone("decided_at").nullable()
}
object SodPolicies : LongIdTable("sod_policies", "policy_id") {
    val tenantId = long("tenant_id")
    val name = text("name")
    val rolesConflict = registerColumn<Array<String>>("roles_conflict", ArrayColumnType(StringColumnType()))
    val enabled = bool("enabled")
    val createdAt = timestampWithTimeZone("created_at")
}
object PamSessions : LongIdTable("pam_sessions", "session_id") {
    val tenantId = long("tenant_id")
    val userId = long("user_id")
    val reason = text("reason")
    val grantedRoles = registerColumn<Array<String>>("granted_roles", ArrayColumnType(StringColumnType()))
    val startedAt = timestampWithTimeZone("started_at")
    val expiresAt = timestampWithTimeZone("expires_at")
    val approvedBy = long("approved_by").nullable()
    val status = text("status")
}
object ReviewSchedules : Table("review_schedules") {
    val tenantId = long("tenant_id")
    val freq = text("freq")
    val nextDueAt = timestampWithTimeZone("next_due_at")
    override val primaryKey = PrimaryKey(tenantId)
}

class AccessRepoImpl : AccessRepo {
    override suspend fun createReview(tenantId: Long, reviewerId: Long, dueAt: Instant): Long = dbQuery {
        AccessReviews.insertAndGetId {
            it[AccessReviews.tenantId] = tenantId
            it[AccessReviews.reviewerId] = reviewerId
            it[AccessReviews.dueAt] = dueAt
            it[status] = "OPEN"
            it[createdAt] = Instant.now()
        }.value
    }

    override suspend fun addItem(reviewId: Long, userId: Long, role: String): Long = dbQuery {
        AccessReviewItems.insertAndGetId {
            it[AccessReviewItems.reviewId] = reviewId
            it[AccessReviewItems.userId] = userId
            it[AccessReviewItems.role] = role
            it[decision] = "PENDING"
        }.value
    }

    override suspend fun setDecision(itemId: Long, decision: String, decidedAt: Instant) = dbQuery {
        AccessReviewItems.update({ AccessReviewItems.id eq itemId }) {
            it[AccessReviewItems.decision] = decision
            it[AccessReviewItems.decidedAt] = decidedAt
        }
    }

    override suspend fun listUserRoles(tenantId: Long): List<Pair<Long, Set<Role>>> = dbQuery {
        emptyList()
    }

    override suspend fun listSodPolicies(tenantId: Long): List<SodPolicy> = dbQuery {
        SodPolicies.select { SodPolicies.tenantId eq tenantId }
            .map {
                SodPolicy(
                    policyId = it[SodPolicies.id].value,
                    tenantId = tenantId,
                    name = it[SodPolicies.name],
                    rolesConflict = it[SodPolicies.rolesConflict].toList(),
                    enabled = it[SodPolicies.enabled]
                )
            }
    }

    override suspend fun createPamRequest(tenantId: Long, userId: Long, reason: String, roles: List<String>, expiresAt: Instant): Long = dbQuery {
        PamSessions.insertAndGetId {
            it[PamSessions.tenantId] = tenantId
            it[PamSessions.userId] = userId
            it[PamSessions.reason] = reason
            it[PamSessions.grantedRoles] = roles.toTypedArray()
            it[startedAt] = Instant.now()
            it[PamSessions.expiresAt] = expiresAt
            it[status] = "REQUESTED"
        }.value
    }

    override suspend fun approvePam(sessionId: Long, approverId: Long) = dbQuery {
        PamSessions.update({ PamSessions.id eq sessionId }) {
            it[approvedBy] = approverId
            it[status] = "APPROVED"
        }
    }

    override suspend fun revokePam(sessionId: Long, revokerId: Long?) = dbQuery {
        PamSessions.update({ PamSessions.id eq sessionId }) {
            it[status] = "REVOKED"
        }
    }
}
