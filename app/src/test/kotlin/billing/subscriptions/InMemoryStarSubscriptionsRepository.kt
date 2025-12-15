package billing.subscriptions

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

class InMemoryStarSubscriptionsRepository : StarSubscriptionsRepository {
    private val idSeq = AtomicLong(1)
    private val rows = HashMap<Long, StarSubscriptionRow>()

    override suspend fun findActiveByUser(userId: Long): StarSubscriptionRow? =
        rows[userId]?.takeIf { it.status == "ACTIVE" }

    override suspend fun upsertActive(
        userId: Long,
        plan: String,
        autoRenew: Boolean,
        renewAt: Instant?,
        trialUntil: Instant?,
    ): StarSubscriptionRow {
        val prev = rows[userId]
        val now = Instant.now()
        val row = if (prev == null) {
            StarSubscriptionRow(
                id = idSeq.getAndIncrement(),
                userId = userId,
                plan = plan,
                status = "ACTIVE",
                autoRenew = autoRenew,
                renewAt = renewAt,
                trialUntil = trialUntil,
                createdAt = now,
            )
        } else {
            prev.copy(
                plan = plan,
                status = "ACTIVE",
                autoRenew = autoRenew,
                renewAt = renewAt ?: prev.renewAt,
                trialUntil = trialUntil ?: prev.trialUntil,
            )
        }
        rows[userId] = row
        return row
    }

    override suspend fun cancelActive(userId: Long): Boolean {
        val current = rows[userId] ?: return false
        if (current.status != "ACTIVE") return false
        rows[userId] = current.copy(status = "CANCELED")
        return true
    }

    override suspend fun findDueRenew(now: Instant): List<StarSubscriptionRow> =
        rows.values.filter { row ->
            row.status == "ACTIVE" && row.autoRenew && row.renewAt?.let { !it.isAfter(now) } == true
        }

    override suspend fun countPaidActive(): Long =
        rows.values.count { row -> row.status == "ACTIVE" && row.trialUntil?.isAfter(Instant.now()) != true }.toLong()
}
