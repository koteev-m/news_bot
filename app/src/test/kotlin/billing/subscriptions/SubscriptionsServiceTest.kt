package billing.subscriptions

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SubscriptionsServiceTest {
    @Test
    fun `activate idempotent and cancel`() =
        runBlocking {
            val repo = InMemoryStarSubscriptionsRepository()
            val meter = SimpleMeterRegistry()
            val svc = SubscriptionsService(repo, meter)

            val first = svc.activate(42, "PRO", autoRenew = true, renewAfter = Duration.ofDays(30), trialUntil = null)
            val second = svc.activate(42, "PRO", autoRenew = true, renewAfter = Duration.ofDays(30), trialUntil = null)

            assertEquals(first.userId, second.userId)
            assertEquals("ACTIVE", repo.findActiveByUser(42)?.status)
            assertEquals(1.0, meter.get("paid_active_gauge").gauge().value())

            val canceled = svc.cancel(42)
            assertTrue(canceled)
            assertEquals(null, repo.findActiveByUser(42))
            assertEquals(0.0, meter.get("paid_active_gauge").gauge().value())
            assertEquals(
                2.0,
                meter
                    .get("transactions_total")
                    .tag("result", "activated")
                    .counter()
                    .count(),
            )
            assertEquals(
                1.0,
                meter
                    .get("transactions_total")
                    .tag("result", "canceled")
                    .counter()
                    .count(),
            )
        }

    @Test
    fun `renew due moves renew_at forward`() =
        runBlocking {
            val repo = FailingUpsertRepository()
            val meter = SimpleMeterRegistry()
            val svc = SubscriptionsService(repo, meter)
            val now = Instant.parse("2024-01-01T00:00:00Z")

            repo.upsertActive(7, "PRO", autoRenew = true, renewAt = now, trialUntil = null)
            repo.upsertActive(13, "PRO", autoRenew = true, renewAt = now, trialUntil = null)
            val due = repo.findDueRenew(now)
            assertTrue(due.isNotEmpty())

            repo.failOnRenew = true
            val stats = svc.renewDue(now, Duration.ofDays(30))
            assertEquals(1, stats.ok)
            assertEquals(1, stats.failed)
            assertEquals(
                1.0,
                meter
                    .get("renew_attempt_total")
                    .tag("result", "success")
                    .counter()
                    .count(),
            )
            assertEquals(
                1.0,
                meter
                    .get("renew_attempt_total")
                    .tag("result", "error")
                    .counter()
                    .count(),
            )

            val after = repo.findDueRenew(now)
            assertEquals(1, after.size)
        }

    @Test
    fun `activate error increments transactions error counter`() =
        runBlocking {
            val repo =
                object : InMemoryStarSubscriptionsRepository() {
                    override suspend fun upsertActive(
                        userId: Long,
                        plan: String,
                        autoRenew: Boolean,
                        renewAt: Instant?,
                        trialUntil: Instant?,
                    ): StarSubscriptionRow = error("boom")
                }
            val meter = SimpleMeterRegistry()
            val svc = SubscriptionsService(repo, meter)

            assertFailsWith<IllegalStateException> {
                svc.activate(7, "PRO", autoRenew = true, renewAfter = Duration.ofDays(30), trialUntil = null)
            }

            assertEquals(
                1.0,
                meter
                    .get("transactions_total")
                    .tag("result", "error")
                    .counter()
                    .count(),
            )
        }

    @Test
    fun `cancel error increments transactions error counter`() =
        runBlocking {
            val repo =
                object : InMemoryStarSubscriptionsRepository() {
                    override suspend fun cancelActive(userId: Long): Boolean = error("boom")
                }
            val meter = SimpleMeterRegistry()
            val svc = SubscriptionsService(repo, meter)

            assertFailsWith<IllegalStateException> {
                svc.cancel(7)
            }

            assertEquals(
                1.0,
                meter
                    .get("transactions_total")
                    .tag("result", "error")
                    .counter()
                    .count(),
            )
        }

    @Test
    fun `activate cancellation does not increment transactions error counter`() =
        runBlocking {
            val repo =
                object : InMemoryStarSubscriptionsRepository() {
                    override suspend fun upsertActive(
                        userId: Long,
                        plan: String,
                        autoRenew: Boolean,
                        renewAt: Instant?,
                        trialUntil: Instant?,
                    ): StarSubscriptionRow = throw CancellationException("cancelled")
                }
            val meter = SimpleMeterRegistry()
            val svc = SubscriptionsService(repo, meter)

            assertFailsWith<CancellationException> {
                svc.activate(7, "PRO", autoRenew = true, renewAfter = Duration.ofDays(30), trialUntil = null)
            }

            assertEquals(null, meter.find("transactions_total").tag("result", "error").counter())
        }
}

private class FailingUpsertRepository : InMemoryStarSubscriptionsRepository() {
    var failOnRenew: Boolean = false

    override suspend fun upsertActive(
        userId: Long,
        plan: String,
        autoRenew: Boolean,
        renewAt: Instant?,
        trialUntil: Instant?,
    ): StarSubscriptionRow {
        if (failOnRenew && userId == 13L) {
            error("boom")
        }
        return super.upsertActive(userId, plan, autoRenew, renewAt, trialUntil)
    }
}
