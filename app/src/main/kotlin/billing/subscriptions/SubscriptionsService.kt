package billing.subscriptions

import common.rethrowIfFatal
import common.runCatchingNonFatal
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant

class SubscriptionsService(
    private val repo: StarSubscriptionsRepository,
    private val meter: MeterRegistry? = null,
) {
    private val cntRenewAttempt = "renew_attempt_total"
    private val cntTx = "transactions_total"
    private val gaugePaidActive = "paid_active_gauge"

    init {
        meter?.gauge(gaugePaidActive, this) { svc ->
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
            meter?.counter(cntTx, "result", "activated")?.increment()
            return row
        } catch (t: Throwable) {
            rethrowIfFatal(t)
            meter?.counter(cntTx, "result", "error")?.increment()
            throw t
        }
    }

    suspend fun cancel(userId: Long): Boolean {
        try {
            val ok = repo.cancelActive(userId)
            meter?.counter(cntTx, "result", if (ok) "canceled" else "noop")?.increment()
            return ok
        } catch (t: Throwable) {
            rethrowIfFatal(t)
            meter?.counter(cntTx, "result", "error")?.increment()
            throw t
        }
    }

    suspend fun renewDue(
        now: Instant,
        period: Duration,
    ): RenewStats {
        val due = repo.findDueRenew(now)
        var ok = 0
        var skipped = 0
        due.forEach { sub ->
            try {
                val next = sub.renewAt?.plus(period) ?: now.plus(period)
                repo.upsertActive(sub.userId, sub.plan, true, next, sub.trialUntil)
                meter?.counter(cntRenewAttempt, "result", "success")?.increment()
                ok++
            } catch (t: Throwable) {
                rethrowIfFatal(t)
                meter?.counter(cntRenewAttempt, "result", "error")?.increment()
                skipped++
            }
        }
        return RenewStats(ok = ok, failed = skipped)
    }
}

data class RenewStats(
    val ok: Int,
    val failed: Int,
)
