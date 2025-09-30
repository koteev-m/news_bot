package alerts.engine

import alerts.antinoise.BudgetLimiter
import alerts.antinoise.CooldownRegistry
import alerts.antinoise.QuietHoursGuard
import alerts.config.AlertsConfig
import alerts.config.InstrumentClass
import alerts.config.Thresholds
import alerts.metrics.AlertMetricsPort
import alerts.model.AlertEvent
import alerts.model.PortfolioAlertEvent
import alerts.ports.MarketDataPort
import alerts.ports.MarketSnapshot
import alerts.ports.NotifierPort
import alerts.ports.PortfolioPort
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

public class AlertEngine(
    private val cfg: AlertsConfig,
    private val market: MarketDataPort,
    private val portfolio: PortfolioPort,
    private val notifier: NotifierPort,
    private val cooldown: CooldownRegistry,
    private val budget: BudgetLimiter,
    private val quiet: QuietHoursGuard,
    private val clock: Clock,
    private val metrics: AlertMetricsPort = AlertMetricsPort.Noop
) {
    private data class InstrumentState(var fastActive: Boolean = false, var dayActive: Boolean = false)
    private data class PortfolioState(var dayActive: Boolean = false, var drawdownActive: Boolean = false)

    private val states: MutableMap<Long, InstrumentState> = ConcurrentHashMap()
    private val portfolioStates: MutableMap<UUID, PortfolioState> = ConcurrentHashMap()

    public suspend fun checkInstrument(instrumentId: Long) {
        val fastSnapshot = tryFetch { market.fastWindow(instrumentId) }
        val daySnapshot = tryFetch { market.dayWindow(instrumentId) }
        if (fastSnapshot == null && daySnapshot == null) {
            return
        }
        val snapshot = fastSnapshot ?: daySnapshot ?: return
        val thresholds = cfg.matrix.perClass[snapshot.classId] ?: return
        val atr = if (cfg.dynamic.enabled) tryFetch { market.atr14(instrumentId) } else null
        val sigma = if (cfg.dynamic.enabled) tryFetch { market.sigma30d(instrumentId) } else null
        val scale = computeScale(atr, sigma)
        val now = clock.instant()

        fastSnapshot?.let {
            processFast(instrumentId, it, thresholds, scale, now)
        }
        daySnapshot?.let {
            processDay(instrumentId, it, thresholds, scale, now)
        }
    }

    public suspend fun checkPortfolio(portfolioId: UUID) {
        val now = clock.instant()
        val dayChange = portfolio.dayChangePct(portfolioId)
        val drawdown = portfolio.drawdownPct(portfolioId)
        val exceedsDay = abs(dayChange) >= cfg.matrix.portfolioDayPct
        val exceedsDrawdown = drawdown >= cfg.matrix.portfolioDrawdownPct
        val state = portfolioStates.computeIfAbsent(portfolioId) { PortfolioState() }
        if (!exceedsDay) {
            state.dayActive = false
        }
        if (!exceedsDrawdown) {
            state.drawdownActive = false
        }
        val shouldSendDay = exceedsDay && !state.dayActive
        val shouldSendDrawdown = exceedsDrawdown && !state.drawdownActive && !state.dayActive
        if (!shouldSendDay && !shouldSendDrawdown) {
            return
        }
        if (quiet.isQuiet(now)) {
            metrics.incBudgetReject()
            return
        }
        val cooldownId = portfolioId.mostSignificantBits xor portfolioId.leastSignificantBits
        if (!cooldown.allow(cooldownId)) {
            metrics.incBudgetReject()
            return
        }
        if (!budget.tryConsume()) {
            metrics.incBudgetReject()
            return
        }
        val type = if (shouldSendDay) PortfolioAlertEvent.Type.DAY_MOVE else PortfolioAlertEvent.Type.DRAWDOWN
        val value = if (shouldSendDay) dayChange else drawdown
        notifier.pushPortfolio(
            portfolioId,
            PortfolioAlertEvent(
                type = type,
                valuePct = value,
                snapshotTime = now
            )
        )
        metrics.incPush()
        if (shouldSendDay) {
            state.dayActive = true
        } else {
            state.drawdownActive = true
        }
        cooldown.markTriggered(cooldownId)
    }

    private fun computeScale(atr: Double?, sigma: Double?): Double {
        if (!cfg.dynamic.enabled) {
            return 1.0
        }
        val ratio = when {
            atr != null && sigma != null && sigma > 0.0 -> atr / sigma
            else -> 1.0
        }
        if (!ratio.isFinite() || ratio <= 0.0) {
            return 1.0
        }
        return clamp(ratio, cfg.dynamic.min, cfg.dynamic.max)
    }

    private suspend fun processFast(
        instrumentId: Long,
        snapshot: MarketSnapshot,
        thresholds: Thresholds,
        scale: Double,
        now: Instant
    ) {
        val state = states.computeIfAbsent(instrumentId) { InstrumentState() }
        val enterThreshold = thresholds.pctFast * scale
        val exitThreshold = enterThreshold * exitRatio()
        val absChange = abs(snapshot.pctChange)
        if (state.fastActive) {
            if (absChange < exitThreshold) {
                state.fastActive = false
            }
            return
        }
        if (absChange < enterThreshold) {
            return
        }
        if (thresholds.volMultFast > 0.0 && snapshot.volumeMult < thresholds.volMultFast) {
            return
        }
        if (!cooldown.allow(instrumentId)) {
            metrics.incBudgetReject()
            return
        }
        if (quiet.isQuiet(now)) {
            metrics.incBudgetReject()
            return
        }
        if (!budget.tryConsume()) {
            metrics.incBudgetReject()
            return
        }
        val hysteresisState = AlertEvent.HysteresisState.ENTER
        val event = if (snapshot.classId == InstrumentClass.STABLECOIN) {
            AlertEvent.StablecoinDepeg(
                instrumentId = instrumentId,
                pct = snapshot.pctChange,
                snapshotTime = now,
                classId = snapshot.classId,
                hysteresisState = hysteresisState
            )
        } else if (thresholds.volMultFast > 0.0 && snapshot.volumeMult >= thresholds.volMultFast) {
            AlertEvent.VolumeSpike(
                instrumentId = instrumentId,
                pct = snapshot.pctChange,
                snapshotTime = now,
                classId = snapshot.classId,
                hysteresisState = hysteresisState,
                volumeMultiplier = snapshot.volumeMult
            )
        } else {
            AlertEvent.FastMove(
                instrumentId = instrumentId,
                pct = snapshot.pctChange,
                snapshotTime = now,
                classId = snapshot.classId,
                hysteresisState = hysteresisState,
                volumeMultiplier = snapshot.volumeMult
            )
        }
        notifier.push(instrumentId, event)
        metrics.incPush()
        cooldown.markTriggered(instrumentId)
        state.fastActive = true
    }

    private suspend fun processDay(
        instrumentId: Long,
        snapshot: MarketSnapshot,
        thresholds: Thresholds,
        scale: Double,
        now: Instant
    ) {
        val state = states.computeIfAbsent(instrumentId) { InstrumentState() }
        val enterThreshold = thresholds.pctDay * scale
        val exitThreshold = enterThreshold * exitRatio()
        val absChange = abs(snapshot.pctChange)
        if (state.dayActive) {
            if (absChange < exitThreshold) {
                state.dayActive = false
            }
            return
        }
        if (absChange < enterThreshold) {
            return
        }
        if (!cooldown.allow(instrumentId)) {
            metrics.incBudgetReject()
            return
        }
        if (quiet.isQuiet(now)) {
            metrics.incBudgetReject()
            return
        }
        if (!budget.tryConsume()) {
            metrics.incBudgetReject()
            return
        }
        val hysteresisState = AlertEvent.HysteresisState.ENTER
        val event = if (snapshot.classId == InstrumentClass.STABLECOIN) {
            AlertEvent.StablecoinDepeg(
                instrumentId = instrumentId,
                pct = snapshot.pctChange,
                snapshotTime = now,
                classId = snapshot.classId,
                hysteresisState = hysteresisState
            )
        } else {
            AlertEvent.DayMove(
                instrumentId = instrumentId,
                pct = snapshot.pctChange,
                snapshotTime = now,
                classId = snapshot.classId,
                hysteresisState = hysteresisState
            )
        }
        notifier.push(instrumentId, event)
        metrics.incPush()
        cooldown.markTriggered(instrumentId)
        state.dayActive = true
    }

    private fun exitRatio(): Double {
        val enter = cfg.matrix.hysteresis.enterPct
        val exit = cfg.matrix.hysteresis.exitPct
        if (enter <= 0.0) {
            return 1.0
        }
        return max(0.0, exit / enter)
    }

    private fun clamp(value: Double, minValue: Double, maxValue: Double): Double {
        return max(minValue, min(maxValue, value))
    }

    private suspend fun <T> tryFetch(block: suspend () -> T): T? {
        return try {
            block()
        } catch (ex: Exception) {
            null
        }
    }
}
