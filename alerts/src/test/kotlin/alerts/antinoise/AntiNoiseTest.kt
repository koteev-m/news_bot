package alerts.antinoise

import alerts.config.Budget
import alerts.config.QuietHours
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class AntiNoiseTest {
    @Test
    fun cooldownRegistryRespectsWindow() {
        val clock = MutableClock(Instant.parse("2023-01-01T00:00:00Z"))
        val registry = CooldownRegistry(clock, minutes = 60)
        assertTrue(registry.allow(1L))
        registry.markTriggered(1L)
        assertFalse(registry.allow(1L))
        clock.advance(Duration.ofMinutes(30))
        assertFalse(registry.allow(1L))
        clock.advance(Duration.ofMinutes(31))
        assertTrue(registry.allow(1L))
    }

    @Test
    fun budgetLimiterResetsDaily() {
        val clock = MutableClock(Instant.parse("2023-01-01T00:00:00Z"))
        val limiter = BudgetLimiter(Budget(maxPushesPerDay = 2), clock)
        assertTrue(limiter.tryConsume())
        assertTrue(limiter.tryConsume())
        assertFalse(limiter.tryConsume())
        clock.advance(Duration.ofDays(1))
        assertTrue(limiter.tryConsume())
    }

    @Test
    fun quietHoursHandlesWrapAround() {
        val guard = QuietHoursGuard(QuietHours(LocalTime.of(22, 0), LocalTime.of(6, 0)), ZoneOffset.UTC)
        val insideLate = Instant.parse("2023-01-01T23:00:00Z")
        val insideEarly = Instant.parse("2023-01-01T03:00:00Z")
        val outside = Instant.parse("2023-01-01T12:00:00Z")
        assertTrue(guard.isQuiet(insideLate))
        assertTrue(guard.isQuiet(insideEarly))
        assertFalse(guard.isQuiet(outside))
    }

    private class MutableClock(
        private var current: Instant,
        private val zoneId: ZoneId = ZoneOffset.UTC
    ) : Clock() {
        override fun withZone(zone: ZoneId?): Clock {
            return MutableClock(current, zone ?: zoneId)
        }

        override fun getZone(): ZoneId {
            return zoneId
        }

        override fun instant(): Instant {
            return current
        }

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }
}
