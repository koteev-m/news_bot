package alerts.antinoise

import alerts.config.Budget
import alerts.config.QuietHours
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

public class CooldownRegistry(private val clock: Clock, private val minutes: Int) {
    private val lastTriggered: MutableMap<Long, Instant> = mutableMapOf()

    public fun allow(instrumentId: Long): Boolean {
        if (minutes <= 0) {
            return true
        }
        val last = lastTriggered[instrumentId] ?: return true
        val now = clock.instant()
        val elapsed = Duration.between(last, now)
        return elapsed.toMinutes() >= minutes.toLong()
    }

    public fun markTriggered(instrumentId: Long) {
        lastTriggered[instrumentId] = clock.instant()
    }
}

public class QuietHoursGuard(private val quiet: QuietHours, private val zone: ZoneId) {
    public fun isQuiet(now: Instant): Boolean {
        val localTime = now.atZone(zone).toLocalTime()
        return if (quiet.start <= quiet.end) {
            !localTime.isBefore(quiet.start) && localTime.isBefore(quiet.end)
        } else {
            !localTime.isBefore(quiet.start) || localTime.isBefore(quiet.end)
        }
    }
}

public class BudgetLimiter(private val budget: Budget, private val clock: Clock) {
    private var lastDate: LocalDate? = null
    private var consumed: Int = 0

    public fun resetIfNewDay() {
        val today = LocalDate.from(clock.instant().atZone(ZoneId.of("UTC")))
        if (lastDate != today) {
            lastDate = today
            consumed = 0
        }
    }

    public fun tryConsume(): Boolean {
        resetIfNewDay()
        if (budget.maxPushesPerDay <= 0) {
            return true
        }
        if (consumed >= budget.maxPushesPerDay) {
            return false
        }
        consumed += 1
        return true
    }
}
