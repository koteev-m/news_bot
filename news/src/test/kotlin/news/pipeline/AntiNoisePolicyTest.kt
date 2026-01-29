package news.pipeline

import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

class AntiNoisePolicyTest {
    private val zoneId = ZoneId.of("UTC")

    @Test
    fun `blocks when daily limit reached`() = runTest {
        val now = Instant.parse("2024-07-01T10:00:00Z")
        val history = object : BreakingHistory {
            override suspend fun lastBreakingPublishedAt(): Instant? = null
            override suspend fun countBreakingPublishedSince(since: Instant): Int = 2
        }
        val policy = AntiNoisePolicy(
            config = news.config.AntiNoiseConfig(
                maxPostsPerDay = 2,
                minIntervalBreakingMinutes = 0,
                digestSlots = listOf(LocalTime.of(9, 0)),
            ),
            history = history,
            clock = Clock.fixed(now, zoneId),
            zoneId = zoneId,
        )
        val decision = policy.allowBreaking()
        assertFalse(decision.allowed)
        assertEquals(BreakingSkipReason.DailyLimit, decision.reason)
    }

    @Test
    fun `blocks when min interval not elapsed`() = runTest {
        val now = Instant.parse("2024-07-01T10:00:00Z")
        val history = object : BreakingHistory {
            override suspend fun lastBreakingPublishedAt(): Instant? = now.minusSeconds(10 * 60)
            override suspend fun countBreakingPublishedSince(since: Instant): Int = 0
        }
        val policy = AntiNoisePolicy(
            config = news.config.AntiNoiseConfig(
                maxPostsPerDay = 5,
                minIntervalBreakingMinutes = 30,
                digestSlots = listOf(LocalTime.of(9, 0)),
            ),
            history = history,
            clock = Clock.fixed(now, zoneId),
            zoneId = zoneId,
        )
        val decision = policy.allowBreaking()
        assertFalse(decision.allowed)
        assertEquals(BreakingSkipReason.MinInterval, decision.reason)
    }
}
