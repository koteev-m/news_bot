package news.pipeline

import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class DigestSlotScheduler(
    private val slots: List<LocalTime>,
    private val fallbackIntervalSeconds: Long,
    private val clock: Clock = Clock.systemUTC(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    fun nextSlot(after: Instant = clock.instant()): Instant {
        if (slots.isEmpty()) {
            return after.plusSeconds(fallbackIntervalSeconds.coerceAtLeast(0))
        }
        val sorted = slots.sorted()
        val localDateTime = after.atZone(zoneId)
        val today = localDateTime.toLocalDate()
        for (slot in sorted) {
            val candidate = today.atTime(slot).atZone(zoneId).toInstant()
            if (candidate.isAfter(after)) {
                return candidate
            }
        }
        val nextDaySlot = today.plusDays(1).atTime(sorted.first()).atZone(zoneId).toInstant()
        return nextDaySlot.truncatedTo(ChronoUnit.SECONDS)
    }
}
