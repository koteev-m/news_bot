package news.pipeline

import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class DigestSlotSchedulerTest {
    private val zoneId = ZoneId.of("UTC")

    @Test
    fun `picks next slot on same day`() {
        val now = Instant.parse("2024-07-01T08:00:00Z")
        val scheduler = DigestSlotScheduler(
            slots = listOf(LocalTime.of(9, 0), LocalTime.of(18, 0)),
            fallbackIntervalSeconds = 3600,
            clock = Clock.fixed(now, zoneId),
            zoneId = zoneId,
        )
        val next = scheduler.nextSlot()
        assertEquals(Instant.parse("2024-07-01T09:00:00Z"), next)
    }

    @Test
    fun `rolls to next day when slots passed`() {
        val now = Instant.parse("2024-07-01T20:00:00Z")
        val scheduler = DigestSlotScheduler(
            slots = listOf(LocalTime.of(9, 0), LocalTime.of(18, 0)),
            fallbackIntervalSeconds = 3600,
            clock = Clock.fixed(now, zoneId),
            zoneId = zoneId,
        )
        val next = scheduler.nextSlot()
        assertEquals(Instant.parse("2024-07-02T09:00:00Z"), next)
    }

    @Test
    fun `uses fallback interval when slots empty`() {
        val now = Instant.parse("2024-07-01T08:00:00Z")
        val scheduler = DigestSlotScheduler(
            slots = emptyList(),
            fallbackIntervalSeconds = 1800,
            clock = Clock.fixed(now, zoneId),
            zoneId = zoneId,
        )
        val next = scheduler.nextSlot()
        assertEquals(Instant.parse("2024-07-01T08:30:00Z"), next)
    }
}
