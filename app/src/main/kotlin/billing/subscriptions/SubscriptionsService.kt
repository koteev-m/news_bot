package billing.subscriptions

import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import common.runCatchingNonFatal

class SubscriptionsService(
    private val repo: StarSubscriptionsRepository,
    private val meter: MeterRegistry? = null,
) {
    private val CNT_RENEW_ATTEMPT = "stars_subscriptions_renew_attempt_total"
    private val CNT_TX = "stars_subscriptions_transactions_total"
    private val GAUGE_PAID_ACTIVE = "stars_subscriptions_paid_active_gauge"

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
        val row = repo.upsertActive(userId, plan, autoRenew, renewAt, trialUntil)
        meter?.counter(CNT_TX, "result", "activated")?.increment()
        return row
    }

    suspend fun cancel(userId: Long): Boolean {
        val ok = repo.cancelActive(userId)
        meter?.counter(CNT_TX, "result", if (ok) "canceled" else "noop")?.increment()
        return ok
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
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (err: Error) {
                throw err
            } catch (t: Throwable) {
                meter?.counter(CNT_RENEW_ATTEMPT, "result", "failed")?.increment()
                skipped++
            }
        }
        return RenewStats(ok = ok, failed = skipped)
    }
}

data class RenewStats(val ok: Int, val failed: Int)
