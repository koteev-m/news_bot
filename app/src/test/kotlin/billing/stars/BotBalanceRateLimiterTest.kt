package billing.stars

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.concurrent.TimeUnit

class BotBalanceRateLimiterTest {
    @Test
    fun `respects per-minute refill without over-permitting`() {
        var now = 0L
        val limiter = BotBalanceRateLimiter(
            capacity = 1,
            refillPerMinute = 1,
            nanoTimeProvider = { now },
        )

        assertEquals(RateLimitVerdict.Allowed, limiter.check(1))
        val denied = limiter.check(1)
        assertTrue(denied is RateLimitVerdict.Denied)

        // After 30 seconds, still not enough tokens for a full request
        now = TimeUnit.SECONDS.toNanos(30)
        val stillDenied = limiter.check(1)
        assertTrue(stillDenied is RateLimitVerdict.Denied)

        // After a full minute, the token refills
        now = TimeUnit.SECONDS.toNanos(60)
        assertEquals(RateLimitVerdict.Allowed, limiter.check(1))
    }

    @Test
    fun `cleans up stale buckets lazily`() {
        var now = 0L
        val limiter = BotBalanceRateLimiter(
            capacity = 1,
            refillPerMinute = 60,
            cleanupThreshold = 2,
            staleAfterNanos = 10,
            cleanupIntervalNanos = 0,
            nanoTimeProvider = { now },
        )

        limiter.check(1)
        now = 1
        limiter.check(2)
        now = 45
        limiter.check(2) // refresh lastSeen for subject 2
        assertEquals(1, limiter.bucketCount())

        // Advance past stale window for subject 1 and trigger cleanup on a new subject
        now = 50
        limiter.check(3)

        assertEquals(2, limiter.bucketCount())
    }
}
