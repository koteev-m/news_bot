package billing.subscriptions

import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import common.rethrowIfFatal
import common.runCatchingNonFatal

class SubscriptionsService(
    private val repo: StarSubscriptionsRepository,
    private val meter: MeterRegistry? = null,
) {
    private val CNT_RENEW_ATTEMPT = "renew_attempt_total"
    private val CNT_TX = "transactions_total"
    private val GAUGE_PAID_ACTIVE = "paid_active_gauge"

    init {
        meter?.gauge(GAUGE_PAID_ACTIVE, this) { svc ->
            runCatchingNonFatal { runBlocking { repo.countPaidActive().toDouble() } }.getOrDefault(0.0)
        }
    }

    suspend fun activate(
        userId: Long,
        plan: String,
        autoRenew: Boolean,
        renewAfter: Duration?,
        trialUntil: Instant?,
    ): StarSubscriptionRow {
        val renewAt = renewAfter?.let { Instant.now().plus(it) }
        try {
            val row = repo.upsertActive(userId, plan, autoRenew, renewAt, trialUntil)
            meter?.counter(CNT_TX, "result", "activated")?.increment()
            return row
        } catch (t: Throwable) {
            rethrowIfFatal(t)
            meter?.counter(CNT_TX, "result", "error")?.increment()
            throw t
        }
    }

    suspend fun cancel(userId: Long): Boolean {
        try {
            val ok = repo.cancelActive(userId)
            meter?.counter(CNT_TX, "result", if (ok) "canceled" else "noop")?.increment()
            return ok
        } catch (t: Throwable) {
            rethrowIfFatal(t)
            meter?.counter(CNT_TX, "result", "error")?.increment()
            throw t
        }
    }

    suspend fun renewDue(now: Instant, period: Duration): RenewStats {
        val due = repo.findDueRenew(now)
        var ok = 0
        var skipped = 0
        due.forEach { sub ->
            try {
                val next = sub.renewAt?.plus(period) ?: now.plus(period)
                repo.upsertActive(sub.userId, sub.plan, true, next, sub.trialUntil)
                meter?.counter(CNT_RENEW_ATTEMPT, "result", "success")?.increment()
                ok++
            } catch (t: Throwable) {
                rethrowIfFatal(t)
                meter?.counter(CNT_RENEW_ATTEMPT, "result", "error")?.increment()
                skipped++
            }
        }
        return RenewStats(ok = ok, failed = skipped)
    }
}

data class RenewStats(val ok: Int, val failed: Int)
