package news.pipeline

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import news.config.AntiNoiseConfig

enum class BreakingSkipReason {
    DailyLimit,
    MinInterval
}

data class BreakingDecision(
    val allowed: Boolean,
    val reason: BreakingSkipReason? = null,
    val nextAllowedAt: Instant? = null,
)

interface BreakingHistory {
    suspend fun lastBreakingPublishedAt(): Instant?
    suspend fun countBreakingPublishedSince(since: Instant): Int
}

class AntiNoisePolicy(
    private val config: AntiNoiseConfig,
    private val history: BreakingHistory,
    private val clock: Clock = Clock.systemUTC(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun allowBreaking(): BreakingDecision {
        val now = clock.instant()
        val minIntervalMinutes = config.minIntervalBreakingMinutes
        if (minIntervalMinutes > 0) {
            val lastPublished = history.lastBreakingPublishedAt()
            if (lastPublished != null) {
                val nextAllowed = lastPublished.plusSeconds(minIntervalMinutes * 60)
                if (nextAllowed.isAfter(now)) {
                    return BreakingDecision(
                        allowed = false,
                        reason = BreakingSkipReason.MinInterval,
                        nextAllowedAt = nextAllowed,
                    )
                }
            }
        }
        val maxPerDay = config.maxPostsPerDay
        if (maxPerDay > 0) {
            val dayStart = now.atZone(zoneId).toLocalDate().atStartOfDay(zoneId).toInstant()
            val count = history.countBreakingPublishedSince(dayStart)
            if (count >= maxPerDay) {
                return BreakingDecision(allowed = false, reason = BreakingSkipReason.DailyLimit)
            }
        }
        return BreakingDecision(allowed = true)
    }
}
