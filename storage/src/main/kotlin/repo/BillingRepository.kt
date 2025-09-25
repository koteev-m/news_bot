package repo

import billing.model.BillingPlan
import billing.model.SubStatus
import billing.model.Tier
import billing.model.UserSubscription
import billing.model.Xtr
import db.DatabaseFactory.dbQuery
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

interface BillingRepository {
    suspend fun getActivePlans(): List<BillingPlan>

    suspend fun upsertSubscription(
        userId: Long,
        tier: Tier,
        status: SubStatus,
        expiresAt: Instant?,
        lastPaymentId: String?
    )

    suspend fun findSubscription(userId: Long): UserSubscription?

    suspend fun recordStarPaymentIfNew(
        userId: Long,
        tier: Tier,
        amountXtr: Long,
        providerPaymentId: String?,
        payload: String?,
        status: SubStatus
    ): Boolean
}

private const val PROVIDER_STARS = "STARS"
private const val SQLSTATE_UNIQUE_VIOLATION = "23505"

class BillingRepositoryImpl : BillingRepository {
    override suspend fun getActivePlans(): List<BillingPlan> = dbQuery {
        BillingPlansTable
            .selectAll()
            .where { BillingPlansTable.isActive eq true }
            .orderBy(BillingPlansTable.planId to SortOrder.ASC)
            .map { it.toBillingPlan() }
    }

    override suspend fun upsertSubscription(
        userId: Long,
        tier: Tier,
        status: SubStatus,
        expiresAt: Instant?,
        lastPaymentId: String?
    ) {
        dbQuery {
            val existing = UserSubscriptionsTable
                .selectAll()
                .where {
                    (UserSubscriptionsTable.userId eq userId) and
                        (UserSubscriptionsTable.provider eq PROVIDER_STARS)
                }
                .singleOrNull()

            if (existing == null) {
                val now = Instant.now()
                UserSubscriptionsTable.insert {
                    it[UserSubscriptionsTable.userId] = userId
                    it[UserSubscriptionsTable.provider] = PROVIDER_STARS
                    it[UserSubscriptionsTable.tier] = tier.name
                    it[UserSubscriptionsTable.status] = status.name
                    it[UserSubscriptionsTable.startedAt] = now.atOffset(ZoneOffset.UTC)
                    val expiresAtValue = expiresAt ?: now
                    it[UserSubscriptionsTable.expiresAt] = expiresAtValue.atOffset(ZoneOffset.UTC)
                    it[UserSubscriptionsTable.lastPaymentId] = lastPaymentId
                }
            } else {
                val currentExpires = existing[UserSubscriptionsTable.expiresAt]
                UserSubscriptionsTable.update({
                    (UserSubscriptionsTable.userId eq userId) and
                        (UserSubscriptionsTable.provider eq PROVIDER_STARS)
                }) {
                    it[UserSubscriptionsTable.tier] = tier.name
                    it[UserSubscriptionsTable.status] = status.name
                    val expiresAtValue = expiresAt?.atOffset(ZoneOffset.UTC) ?: currentExpires
                    it[UserSubscriptionsTable.expiresAt] = expiresAtValue
                    it[UserSubscriptionsTable.lastPaymentId] = lastPaymentId
                }
            }
        }
    }

    override suspend fun findSubscription(userId: Long): UserSubscription? = dbQuery {
        UserSubscriptionsTable
            .selectAll()
            .where {
                (UserSubscriptionsTable.userId eq userId) and
                    (UserSubscriptionsTable.provider eq PROVIDER_STARS)
            }
            .limit(1)
            .singleOrNull()
            ?.toUserSubscription()
    }

    override suspend fun recordStarPaymentIfNew(
        userId: Long,
        tier: Tier,
        amountXtr: Long,
        providerPaymentId: String?,
        payload: String?,
        status: SubStatus
    ): Boolean = dbQuery {
        val chargeId = providerPaymentId ?: UUID.randomUUID().toString()
        try {
            StarPaymentsTable.insert {
                it[StarPaymentsTable.userId] = userId
                it[StarPaymentsTable.tier] = tier.name
                it[StarPaymentsTable.amountXtr] = amountXtr
                it[StarPaymentsTable.providerPaymentChargeId] = chargeId
                it[StarPaymentsTable.invoicePayload] = payload
                it[StarPaymentsTable.createdAt] = Instant.now().atOffset(ZoneOffset.UTC)
                it[StarPaymentsTable.status] = status.name
            }
            true
        } catch (exception: ExposedSQLException) {
            if (providerPaymentId != null && exception.sqlState == SQLSTATE_UNIQUE_VIOLATION) {
                false
            } else {
                throw exception
            }
        }
    }
}

private fun ResultRow.toBillingPlan(): BillingPlan = BillingPlan(
    tier = Tier.parse(this[BillingPlansTable.tier]),
    title = this[BillingPlansTable.title],
    priceXtr = Xtr(this[BillingPlansTable.priceXtr]),
    isActive = this[BillingPlansTable.isActive]
)

private fun ResultRow.toUserSubscription(): UserSubscription = UserSubscription(
    userId = this[UserSubscriptionsTable.userId],
    tier = Tier.parse(this[UserSubscriptionsTable.tier]),
    status = SubStatus.parse(this[UserSubscriptionsTable.status]),
    startedAt = this[UserSubscriptionsTable.startedAt].toInstant(),
    expiresAt = this[UserSubscriptionsTable.expiresAt].toInstant()
)
