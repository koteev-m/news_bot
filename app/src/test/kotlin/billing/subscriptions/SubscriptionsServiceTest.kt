package billing.subscriptions

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubscriptionsServiceTest {
    @Test
    fun `activate idempotent and cancel`() = runBlocking {
        val repo = InMemoryStarSubscriptionsRepository()
        val svc = SubscriptionsService(repo, SimpleMeterRegistry())

        val first = svc.activate(42, "PRO", autoRenew = true, renewAfter = Duration.ofDays(30), trialUntil = null)
        val second = svc.activate(42, "PRO", autoRenew = true, renewAfter = Duration.ofDays(30), trialUntil = null)

        assertEquals(first.userId, second.userId)
        assertEquals("ACTIVE", repo.findActiveByUser(42)?.status)

        val canceled = svc.cancel(42)
        assertTrue(canceled)
        assertEquals(null, repo.findActiveByUser(42))
    }

    @Test
    fun `renew due moves renew_at forward`() = runBlocking {
        val repo = InMemoryStarSubscriptionsRepository()
        val svc = SubscriptionsService(repo, SimpleMeterRegistry())
        val now = Instant.parse("2024-01-01T00:00:00Z")

        repo.upsertActive(7, "PRO", autoRenew = true, renewAt = now, trialUntil = null)
        val due = repo.findDueRenew(now)
        assertTrue(due.isNotEmpty())

        val stats = svc.renewDue(now, Duration.ofDays(30))
        assertEquals(1, stats.ok)
        val after = repo.findDueRenew(now)
        assertTrue(after.isEmpty())
    }
}
