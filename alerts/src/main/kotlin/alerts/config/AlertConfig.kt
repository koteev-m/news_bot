package alerts.config

import java.time.LocalTime

public data class QuietHours(val start: LocalTime, val end: LocalTime)

public data class Budget(val maxPushesPerDay: Int)

public data class Hysteresis(val enterPct: Double, val exitPct: Double)

public enum class InstrumentClass {
    MOEX_BLUE,
    MOEX_SECOND,
    OFZ,
    INDEX,
    FX,
    CRYPTO_MAJOR,
    CRYPTO_MID,
    STABLECOIN
}

public data class Thresholds(
    val pctFast: Double,
    val pctDay: Double,
    val volMultFast: Double = 0.0
)

public data class MatrixV11(
    val perClass: Map<InstrumentClass, Thresholds>,
    val hysteresis: Hysteresis,
    val portfolioDayPct: Double = 2.0,
    val portfolioDrawdownPct: Double = 5.0
)

public data class DynamicScale(
    val enabled: Boolean,
    val min: Double = 0.7,
    val max: Double = 1.3
)

public data class AlertsConfig(
    val quiet: QuietHours,
    val budget: Budget,
    val matrix: MatrixV11,
    val dynamic: DynamicScale,
    val cooldownMinutes: Int = 60
)

public object AlertDefaults {
    public fun matrix(): AlertsConfig {
        val thresholds = mapOf(
            InstrumentClass.MOEX_BLUE to Thresholds(pctFast = 2.0, pctDay = 4.0, volMultFast = 1.8),
            InstrumentClass.MOEX_SECOND to Thresholds(pctFast = 3.0, pctDay = 6.0, volMultFast = 2.2),
            InstrumentClass.OFZ to Thresholds(pctFast = 0.3, pctDay = 0.6),
            InstrumentClass.INDEX to Thresholds(pctFast = 0.7, pctDay = 1.5),
            InstrumentClass.FX to Thresholds(pctFast = 1.0, pctDay = 2.0),
            InstrumentClass.CRYPTO_MAJOR to Thresholds(pctFast = 2.0, pctDay = 4.0, volMultFast = 2.0),
            InstrumentClass.CRYPTO_MID to Thresholds(pctFast = 4.0, pctDay = 8.0, volMultFast = 2.5),
            InstrumentClass.STABLECOIN to Thresholds(pctFast = 0.5, pctDay = 0.8)
        )
        val matrix = MatrixV11(
            perClass = thresholds,
            hysteresis = Hysteresis(enterPct = 2.0, exitPct = 1.5),
            portfolioDayPct = 2.0,
            portfolioDrawdownPct = 5.0
        )
        return AlertsConfig(
            quiet = QuietHours(start = LocalTime.of(22, 0), end = LocalTime.of(7, 0)),
            budget = Budget(maxPushesPerDay = 50),
            matrix = matrix,
            dynamic = DynamicScale(enabled = true, min = 0.7, max = 1.3),
            cooldownMinutes = 60
        )
    }
}
