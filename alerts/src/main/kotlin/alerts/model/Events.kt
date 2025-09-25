package alerts.model

import alerts.config.InstrumentClass
import java.time.Instant

public sealed class AlertEvent {
    public abstract val pct: Double
    public abstract val snapshotTime: Instant
    public abstract val classId: InstrumentClass
    public abstract val hysteresisState: HysteresisState
    public open val volumeMultiplier: Double? = null

    public enum class HysteresisState {
        ENTER,
        EXIT
    }

    public data class FastMove(
        val instrumentId: Long,
        override val pct: Double,
        override val snapshotTime: Instant,
        override val classId: InstrumentClass,
        override val hysteresisState: HysteresisState,
        override val volumeMultiplier: Double? = null
    ) : AlertEvent()

    public data class DayMove(
        val instrumentId: Long,
        override val pct: Double,
        override val snapshotTime: Instant,
        override val classId: InstrumentClass,
        override val hysteresisState: HysteresisState
    ) : AlertEvent()

    public data class VolumeSpike(
        val instrumentId: Long,
        override val pct: Double,
        override val snapshotTime: Instant,
        override val classId: InstrumentClass,
        override val hysteresisState: HysteresisState,
        override val volumeMultiplier: Double
    ) : AlertEvent()

    public data class StablecoinDepeg(
        val instrumentId: Long,
        override val pct: Double,
        override val snapshotTime: Instant,
        override val classId: InstrumentClass,
        override val hysteresisState: HysteresisState
    ) : AlertEvent()
}

public data class PortfolioAlertEvent(
    val type: Type,
    val valuePct: Double,
    val snapshotTime: Instant
) {
    public enum class Type {
        DAY_MOVE,
        DRAWDOWN
    }
}
