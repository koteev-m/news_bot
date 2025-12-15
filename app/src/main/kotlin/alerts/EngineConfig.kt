package alerts

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Configuration for the alert FSM.
 */
data class EngineConfig(
    val confirmT: DurationRange = DurationRange(10.minutes, 15.minutes),
    val cooldownT: DurationRange = DurationRange(60.minutes, 120.minutes),
    val quietHours: QuietHours = QuietHours(23, 7),
    val dailyBudgetPushMax: Int = 6,
    val hysteresisExitFactor: Double = 0.75,
    val volumeGateK: Double = 1.0,
    val thresholds: ThresholdMatrix = ThresholdMatrix(
        mapOf(
            "breakout" to Thresholds(fast = 1.2, daily = 2.5),
            "mean_revert" to Thresholds(fast = 0.8, daily = 1.8)
        )
    ),
    val zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault()
)

/**
 * Simple duration range helper.
 */
data class DurationRange(val min: Duration, val max: Duration)

/** Quiet hours expressed as start/end hours in local time (24h clock). */
data class QuietHours(val startHour: Int, val endHour: Int) {
    init {
        require(startHour in 0..23 && endHour in 0..23) { "hours must be within 0-23" }
    }
}
