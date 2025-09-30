package alerts.engine

import alerts.antinoise.BudgetLimiter
import alerts.antinoise.CooldownRegistry
import alerts.antinoise.QuietHoursGuard
import alerts.config.AlertsConfig
import alerts.config.Budget
import alerts.config.DynamicScale
import alerts.config.Hysteresis
import alerts.config.InstrumentClass
import alerts.config.MatrixV11
import alerts.config.QuietHours
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
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class AlertMetricsTest {

    @Test
    fun `notifier push increments metrics`() = runTest {
        val metrics = RecordingAlertMetrics()
        val notifier = RecordingNotifier()
        val market = MutableMarketDataPort(
            MarketSnapshot(
                classId = InstrumentClass.MOEX_BLUE,
                pctChange = 3.0,
                volumeMult = 2.0
            )
        )
        val engine = createEngine(
            metrics = metrics,
            notifier = notifier,
            budget = Budget(maxPushesPerDay = 10),
            market = market
        )

        engine.checkInstrument(1L)

        assertEquals(1, metrics.pushes)
        assertEquals(1, notifier.instrumentEvents.size)
    }

    @Test
    fun `anti noise rejection increments metrics`() = runTest {
        val metrics = RecordingAlertMetrics()
        val notifier = RecordingNotifier()
        val market = MutableMarketDataPort(
            MarketSnapshot(
                classId = InstrumentClass.MOEX_BLUE,
                pctChange = 3.0,
                volumeMult = 2.0
            )
        )
        val engine = createEngine(
            metrics = metrics,
            notifier = notifier,
            budget = Budget(maxPushesPerDay = 1),
            market = market
        )

        engine.checkInstrument(1L)
        market.snapshot = MarketSnapshot(
            classId = InstrumentClass.MOEX_BLUE,
            pctChange = 0.5,
            volumeMult = 2.0
        )
        engine.checkInstrument(1L)
        market.snapshot = MarketSnapshot(
            classId = InstrumentClass.MOEX_BLUE,
            pctChange = 3.2,
            volumeMult = 2.0
        )
        engine.checkInstrument(1L)

        assertEquals(1, metrics.pushes)
        assertEquals(1, metrics.rejects)
        assertEquals(1, notifier.instrumentEvents.size)
    }

    private fun createEngine(
        metrics: AlertMetricsPort,
        notifier: RecordingNotifier,
        budget: Budget,
        market: MutableMarketDataPort
    ): AlertEngine {
        val clock = Clock.fixed(Instant.parse("2024-01-01T10:00:00Z"), ZoneOffset.UTC)
        val config = AlertsConfig(
            quiet = QuietHours(LocalTime.of(23, 0), LocalTime.of(23, 30)),
            budget = budget,
            matrix = MatrixV11(
                perClass = mapOf(
                    InstrumentClass.MOEX_BLUE to Thresholds(pctFast = 2.0, pctDay = 4.0, volMultFast = 1.0)
                ),
                hysteresis = Hysteresis(enterPct = 2.0, exitPct = 1.5),
                portfolioDayPct = 2.0,
                portfolioDrawdownPct = 5.0
            ),
            dynamic = DynamicScale(enabled = false),
            cooldownMinutes = 0
        )
        val portfolio = object : PortfolioPort {
            override suspend fun dayChangePct(portfolioId: UUID): Double = 0.0
            override suspend fun drawdownPct(portfolioId: UUID): Double = 0.0
        }
        val cooldown = CooldownRegistry(clock, config.cooldownMinutes)
        val limiter = BudgetLimiter(config.budget, clock)
        val quietGuard = QuietHoursGuard(config.quiet, ZoneId.of("UTC"))
        return AlertEngine(
            cfg = config,
            market = market,
            portfolio = portfolio,
            notifier = notifier,
            cooldown = cooldown,
            budget = limiter,
            quiet = quietGuard,
            clock = clock,
            metrics = metrics
        )
    }

    private class RecordingAlertMetrics : AlertMetricsPort {
        var pushes: Int = 0
        var rejects: Int = 0

        override fun incPush() {
            pushes += 1
        }

        override fun incBudgetReject() {
            rejects += 1
        }
    }

    private class RecordingNotifier : NotifierPort {
        val instrumentEvents: MutableList<AlertEvent> = mutableListOf()
        val portfolioEvents: MutableList<PortfolioAlertEvent> = mutableListOf()

        override suspend fun push(instrumentId: Long, event: AlertEvent) {
            instrumentEvents += event
        }

        override suspend fun pushPortfolio(portfolioId: UUID, event: PortfolioAlertEvent) {
            portfolioEvents += event
        }
    }

    private class MutableMarketDataPort(
        var snapshot: MarketSnapshot
    ) : MarketDataPort {
        override suspend fun fastWindow(instrumentId: Long): MarketSnapshot = snapshot
        override suspend fun dayWindow(instrumentId: Long): MarketSnapshot = snapshot
        override suspend fun atr14(instrumentId: Long): Double? = null
        override suspend fun sigma30d(instrumentId: Long): Double? = null
    }
}
