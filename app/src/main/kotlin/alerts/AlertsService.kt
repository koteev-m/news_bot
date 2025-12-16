package alerts

import alerts.metrics.AlertMetrics
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.abs
import kotlinx.serialization.Serializable

@Serializable
data class MarketSnapshot(
    val tsEpochSec: Long,
    val userId: Long,
    val items: List<SignalItem>,
    val portfolio: PortfolioSnapshot? = null
)

@Serializable
data class SignalItem(
    val ticker: String,
    val classId: String,
    val window: String,
    val pctMove: Double,
    val atr: Double? = null,
    val sigma: Double? = null,
    val volume: Double? = null,
    val avgVolume: Double? = null
)

@Serializable
data class PortfolioSnapshot(
    val totalChangePctDay: Double? = null,
    val drawdownPct: Double? = null
)

/**
 * Alert service implementing the anti-noise FSM with quiet hours, cooldowns, budgets, hysteresis, volume gate and
 * portfolio summary triggers. State is tracked per user via the repository.
 */
class AlertsService(
    private val repo: AlertsRepository,
    private val config: EngineConfig,
    meterRegistry: MeterRegistry,
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val metrics = AlertMetrics(meterRegistry)
    private val volumeGate = VolumeGate(config.volumeGateK)
    private val portfolioSummaryByDay = java.util.concurrent.ConcurrentHashMap<Long, LocalDate>()

    fun getState(userId: Long): FsmState = repo.getState(userId)

    private fun isQuiet(instant: Instant): Boolean {
        val localTime = LocalDateTime.ofInstant(instant, config.zoneId).toLocalTime()
        val start = LocalTime.of(config.quietHours.startHour, 0)
        val end = LocalTime.of(config.quietHours.endHour, 0)
        return if (start < end) {
            !localTime.isBefore(start) && localTime.isBefore(end)
        } else {
            !localTime.isBefore(start) || localTime.isBefore(end)
        }
    }

    private fun deliver(userId: Long, alert: PendingAlert, reason: String, date: LocalDate) {
        metrics.fire(alert.classId, alert.ticker, alert.window)
        metrics.delivered(reason)
        repo.incDailyPushCount(userId, date)
    }

    private fun cooldownUntil(nowEpoch: Long): Long = nowEpoch + config.cooldownT.min.inWholeSeconds

    fun onSnapshot(snapshot: MarketSnapshot): TransitionResult {
        val userId = snapshot.userId
        val nowInstant = Instant.ofEpochSecond(snapshot.tsEpochSec)
        val today = LocalDateTime.ofInstant(nowInstant, config.zoneId).toLocalDate()
        var state = repo.getState(userId)
        val emitted = mutableListOf<EmittedAlert>()
        val suppressedReasons = linkedSetOf<String>()

        if (state is FsmState.COOLDOWN && snapshot.tsEpochSec >= state.untilEpochSec) {
            state = FsmState.IDLE
        }
        val budgetCount = repo.getDailyPushCount(userId, today)
        if (state is FsmState.BUDGET_EXHAUSTED && budgetCount < config.dailyBudgetPushMax) {
            state = FsmState.IDLE
        }

        val quietNow = isQuiet(nowInstant)

        fun addSuppressed(reason: String) {
            if (suppressedReasons.add(reason)) {
                metrics.suppressed(reason)
            }
        }

        if (state is FsmState.QUIET && !quietNow && state.buffer.isNotEmpty()) {
            var deliveredAny = false
            var remainingBudget = config.dailyBudgetPushMax - repo.getDailyPushCount(userId, today)

            state.buffer.forEach { alert ->
                if (remainingBudget > 0) {
                    deliver(userId, alert, "quiet_hours_flush", today)
                    emitted.add(EmittedAlert(alert, "quiet_hours_flush"))
                    deliveredAny = true
                    remainingBudget--
                } else {
                    addSuppressed("budget")
                }
            }

            val budgetExhausted = repo.getDailyPushCount(userId, today) >= config.dailyBudgetPushMax
            state = when {
                deliveredAny && !budgetExhausted -> FsmState.COOLDOWN(cooldownUntil(snapshot.tsEpochSec))
                deliveredAny -> FsmState.BUDGET_EXHAUSTED
                else -> FsmState.BUDGET_EXHAUSTED
            }
        }

        fun bufferAlert(alert: PendingAlert): FsmState {
            val currentBuffer = (state as? FsmState.QUIET)?.buffer ?: emptyList()
            val exists = currentBuffer.any { it.classId == alert.classId && it.ticker == alert.ticker && it.window == alert.window }
            return if (exists) {
                addSuppressed("duplicate")
                FsmState.QUIET(currentBuffer)
            } else {
                FsmState.QUIET(currentBuffer + alert)
            }
        }

        fun attemptPush(alert: PendingAlert, deliveredReason: String = "direct"): Boolean {
            val currentBudget = repo.getDailyPushCount(userId, today)
            if (currentBudget >= config.dailyBudgetPushMax) {
                state = FsmState.BUDGET_EXHAUSTED
                addSuppressed("budget")
                return false
            }
            if (state is FsmState.COOLDOWN && snapshot.tsEpochSec < (state as FsmState.COOLDOWN).untilEpochSec) {
                addSuppressed("cooldown")
                return false
            }
            deliver(userId, alert, deliveredReason, today)
            emitted.add(EmittedAlert(alert, deliveredReason))
            state = FsmState.COOLDOWN(cooldownUntil(snapshot.tsEpochSec))
            return true
        }

        val portfolio = snapshot.portfolio
        val summaryNeeded = portfolio != null && (
            (portfolio.totalChangePctDay != null && abs(portfolio.totalChangePctDay) >= 2.0) ||
                (portfolio.drawdownPct != null && portfolio.drawdownPct >= 5.0)
            )
        if (summaryNeeded) {
            val last = portfolioSummaryByDay[userId]
            if (last != today) {
                val alert = PendingAlert(
                    classId = "portfolio_summary",
                    ticker = "",
                    window = "daily",
                    score = 0.0,
                    pctMove = portfolio.totalChangePctDay ?: portfolio.drawdownPct ?: 0.0,
                    ts = snapshot.tsEpochSec
                )
                portfolioSummaryByDay[userId] = today
                if (quietNow) {
                    addSuppressed("quiet_hours")
                    state = bufferAlert(alert)
                } else {
                    attemptPush(alert, "portfolio_summary")
                }
            }
        }

        data class ItemCheck(
            val item: SignalItem,
            val threshold: Double,
            val hysteresisLevel: Double,
            val score: Double,
            val meets: Boolean
        )

        val checks = snapshot.items.mapNotNull { item ->
            val thresholdBase = config.thresholds.getThreshold(item.classId, item.window) ?: return@mapNotNull null
            val volumeAllowed = volumeGate.allows(item.volume, item.avgVolume)
            if (!volumeAllowed) {
                addSuppressed("no_volume")
                return@mapNotNull null
            }
            val thr = thresholdBase * proMultiplier(item.atr, item.sigma)
            val score = item.pctMove - thr
            ItemCheck(item, thr, thr * config.hysteresisExitFactor, score, item.pctMove >= thr)
        }

        fun windowPriority(window: String): Int = when (window.lowercase()) {
            "daily" -> 0
            "fast" -> 1
            else -> 2
        }

        val candidate = checks
            .filter { it.meets }
            .sortedWith(
                compareByDescending<ItemCheck> { it.score }
                    .thenBy { windowPriority(it.item.window) }
                    .thenBy { it.item.classId }
                    .thenBy { it.item.ticker }
            )
            .firstOrNull()

        if (candidate == null) {
            if (checks.isNotEmpty()) {
                addSuppressed("below_threshold")
            }
            if (state is FsmState.ARMED) {
                val belowExit = checks.all { it.item.pctMove < it.hysteresisLevel }
                if (belowExit) {
                    state = FsmState.IDLE
                }
            }
            repo.setState(userId, state)
            return TransitionResult(state, emitted, suppressedReasons.toList())
        }

        val pendingAlert = PendingAlert(
            classId = candidate.item.classId,
            ticker = candidate.item.ticker,
            window = candidate.item.window,
            score = candidate.score,
            pctMove = candidate.item.pctMove,
            ts = snapshot.tsEpochSec
        )

        if (quietNow) {
            addSuppressed("quiet_hours")
            state = bufferAlert(pendingAlert)
            repo.setState(userId, state)
            return TransitionResult(state, emitted, suppressedReasons.toList())
        }

        when (state) {
            is FsmState.COOLDOWN -> {
                addSuppressed("cooldown")
            }
            is FsmState.BUDGET_EXHAUSTED -> {
                addSuppressed("budget")
            }
            is FsmState.ARMED -> {
                val armedState = state as FsmState.ARMED
                val elapsed = snapshot.tsEpochSec - armedState.armedAtEpochSec
                val confirmed = candidate.item.window.lowercase() != "fast" || elapsed >= config.confirmT.min.inWholeSeconds
                if (confirmed) {
                    attemptPush(pendingAlert)
                }
            }
            else -> {
                if (candidate.item.window.lowercase() == "daily") {
                    attemptPush(pendingAlert)
                } else {
                    state = FsmState.ARMED(snapshot.tsEpochSec)
                }
            }
        }

        repo.setState(userId, state)
        return TransitionResult(state, emitted, suppressedReasons.toList())
    }
}
