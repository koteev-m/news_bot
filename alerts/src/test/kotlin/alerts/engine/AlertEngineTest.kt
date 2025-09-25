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
import alerts.model.AlertEvent
import alerts.model.PortfolioAlertEvent
import alerts.ports.MarketDataPort
import alerts.ports.MarketSnapshot
import alerts.ports.NotifierPort
import alerts.ports.PortfolioPort
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class AlertEngineTest {
    private val zone: ZoneId = ZoneOffset.UTC

    @org.junit.jupiter.api.Test
    fun hysteresisFastWindow() = runTest {
        val clock = MutableClock(Instant.parse("2023-01-01T00:00:00Z"))
        val market = FakeMarketDataPort()
        market.fastSnapshots[1L] = MarketSnapshot(InstrumentClass.MOEX_BLUE, 2.1, 2.0)
        market.daySnapshots[1L] = MarketSnapshot(InstrumentClass.MOEX_BLUE, 0.0, 1.0)
        val notifier = FakeNotifierPort()
        val engine = createEngine(
            clock = clock,
            market = market,
            notifier = notifier,
            cooldownMinutes = 0
        )

        engine.checkInstrument(1L)
        assertEquals(1, notifier.events.size)
        val firstEvent = notifier.events.first()
        assertTrue(firstEvent is AlertEvent.FastMove || firstEvent is AlertEvent.VolumeSpike)

        market.fastSnapshots[1L] = MarketSnapshot(InstrumentClass.MOEX_BLUE, 1.8, 2.0)
        engine.checkInstrument(1L)
        assertEquals(1, notifier.events.size)

        market.fastSnapshots[1L] = MarketSnapshot(InstrumentClass.MOEX_BLUE, 1.4, 2.0)
        engine.checkInstrument(1L)
        assertEquals(1, notifier.events.size)

        market.fastSnapshots[1L] = MarketSnapshot(InstrumentClass.MOEX_BLUE, 2.0, 2.0)
        engine.checkInstrument(1L)
        assertEquals(2, notifier.events.size)
    }

    @org.junit.jupiter.api.Test
    fun cooldownPreventsRapidRepeat() = runTest {
        val clock = MutableClock(Instant.parse("2023-01-01T00:00:00Z"))
        val market = FakeMarketDataPort()
        market.fastSnapshots[1L] = MarketSnapshot(InstrumentClass.MOEX_BLUE, 2.2, 2.0)
        market.daySnapshots[1L] = MarketSnapshot(InstrumentClass.MOEX_BLUE, 0.0, 1.0)
        val notifier = FakeNotifierPort()
        val engine = createEngine(
            clock = clock,
            market = market,
            notifier = notifier,
            cooldownMinutes = 60
        )

        engine.checkInstrument(1L)
        assertEquals(1, notifier.events.size)

        market.fastSnapshots[1L] = MarketSnapshot(InstrumentClass.MOEX_BLUE, 1.0, 1.0)
        engine.checkInstrument(1L)
        market.fastSnapshots[1L] = MarketSnapshot(InstrumentClass.MOEX_BLUE, 2.4, 2.0)
        engine.checkInstrument(1L)
        assertEquals(1, notifier.events.size)

        clock.advance(Duration.ofMinutes(61))
        engine.checkInstrument(1L)
        assertEquals(2, notifier.events.size)
    }

    @org.junit.jupiter.api.Test
    fun budgetLimiterBlocksAfterLimit() = runTest {
        val clock = MutableClock(Instant.parse("2023-01-01T00:00:00Z"))
        val market = FakeMarketDataPort()
        market.fastSnapshots[1L] = MarketSnapshot(InstrumentClass.MOEX_BLUE, 2.5, 2.0)
        market.daySnapshots[1L] = MarketSnapshot(InstrumentClass.MOEX_BLUE, 0.0, 1.0)
        val notifier = FakeNotifierPort()
        val engine = createEngine(
            clock = clock,
            market = market,
            notifier = notifier,
            cooldownMinutes = 0,
            budget = Budget(maxPushesPerDay = 2)
        )

        engine.checkInstrument(1L)
        market.fastSnapshots[1L] = MarketSnapshot(InstrumentClass.MOEX_BLUE, 1.0, 1.0)
        engine.checkInstrument(1L)
        market.fastSnapshots[1L] = MarketSnapshot(InstrumentClass.MOEX_BLUE, 2.6, 2.0)
        engine.checkInstrument(1L)
        market.fastSnapshots[1L] = MarketSnapshot(InstrumentClass.MOEX_BLUE, 1.0, 1.0)
        engine.checkInstrument(1L)
        market.fastSnapshots[1L] = MarketSnapshot(InstrumentClass.MOEX_BLUE, 2.7, 2.0)
        engine.checkInstrument(1L)
        market.fastSnapshots[1L] = MarketSnapshot(InstrumentClass.MOEX_BLUE, 1.0, 1.0)
        engine.checkInstrument(1L)
        assertEquals(2, notifier.events.size)

        clock.advance(Duration.ofDays(1))
        market.fastSnapshots[1L] = MarketSnapshot(InstrumentClass.MOEX_BLUE, 2.8, 2.0)
        engine.checkInstrument(1L)
        assertEquals(3, notifier.events.size)
    }

    @org.junit.jupiter.api.Test
    fun quietHoursSuppressNotifications() = runTest {
        val clock = MutableClock(Instant.parse("2023-01-01T01:00:00Z"), zone)
        val market = FakeMarketDataPort()
        market.fastSnapshots[1L] = MarketSnapshot(InstrumentClass.MOEX_BLUE, 2.2, 2.5)
        market.daySnapshots[1L] = MarketSnapshot(InstrumentClass.MOEX_BLUE, 0.0, 1.0)
        val notifier = FakeNotifierPort()
        val quiet = QuietHoursGuard(QuietHours(start = LocalTime.of(0, 0), end = LocalTime.of(5, 0)), zone)
        val engine = createEngine(
            clock = clock,
            market = market,
            notifier = notifier,
            cooldownMinutes = 0,
            quietGuard = quiet
        )

        engine.checkInstrument(1L)
        assertTrue(notifier.events.isEmpty())
    }

    @org.junit.jupiter.api.Test
    fun portfolioAlertsTriggered() = runTest {
        val clock = MutableClock(Instant.parse("2023-01-01T10:00:00Z"))
        val market = FakeMarketDataPort()
        market.fastSnapshots[1L] = MarketSnapshot(InstrumentClass.MOEX_BLUE, 0.0, 1.0)
        market.daySnapshots[1L] = MarketSnapshot(InstrumentClass.MOEX_BLUE, 0.0, 1.0)
        val notifier = FakeNotifierPort()
        val portfolioPort = FakePortfolioPort(dayChange = 2.2, drawdown = 6.0)
        val engine = createEngine(
            clock = clock,
            market = market,
            notifier = notifier,
            cooldownMinutes = 0,
            portfolio = portfolioPort
        )

        val portfolioId = UUID.randomUUID()
        engine.checkPortfolio(portfolioId)
        assertEquals(1, notifier.portfolioEvents.size)
        assertEquals(PortfolioAlertEvent.Type.DAY_MOVE, notifier.portfolioEvents.first().type)

        engine.checkPortfolio(portfolioId)
        assertEquals(1, notifier.portfolioEvents.size)

        portfolioPort.dayChange = 0.5
        portfolioPort.drawdown = 5.2
        engine.checkPortfolio(portfolioId)
        assertEquals(2, notifier.portfolioEvents.size)
        assertEquals(PortfolioAlertEvent.Type.DRAWDOWN, notifier.portfolioEvents.last().type)

        portfolioPort.drawdown = 1.0
        engine.checkPortfolio(portfolioId)
        portfolioPort.drawdown = 6.0
        engine.checkPortfolio(portfolioId)
        assertEquals(3, notifier.portfolioEvents.size)
    }

    private fun createEngine(
        clock: MutableClock,
        market: FakeMarketDataPort,
        notifier: FakeNotifierPort,
        cooldownMinutes: Int,
        portfolio: PortfolioPort = FakePortfolioPort(),
        budget: Budget = Budget(maxPushesPerDay = 10),
        quietGuard: QuietHoursGuard = QuietHoursGuard(QuietHours(LocalTime.of(23, 0), LocalTime.of(23, 30)), zone)
    ): AlertEngine {
        val matrix = MatrixV11(
            perClass = mapOf(
                InstrumentClass.MOEX_BLUE to Thresholds(pctFast = 2.0, pctDay = 4.0, volMultFast = 1.5)
            ),
            hysteresis = Hysteresis(enterPct = 2.0, exitPct = 1.5),
            portfolioDayPct = 2.0,
            portfolioDrawdownPct = 5.0
        )
        val config = AlertsConfig(
            quiet = QuietHours(LocalTime.of(23, 0), LocalTime.of(23, 30)),
            budget = budget,
            matrix = matrix,
            dynamic = DynamicScale(enabled = false),
            cooldownMinutes = cooldownMinutes
        )
        val cooldown = CooldownRegistry(clock, cooldownMinutes)
        val limiter = BudgetLimiter(budget, clock)
        return AlertEngine(
            cfg = config,
            market = market,
            portfolio = portfolio,
            notifier = notifier,
            cooldown = cooldown,
            budget = limiter,
            quiet = quietGuard,
            clock = clock
        )
    }

    private class FakeMarketDataPort : MarketDataPort {
        val fastSnapshots: MutableMap<Long, MarketSnapshot> = mutableMapOf()
        val daySnapshots: MutableMap<Long, MarketSnapshot> = mutableMapOf()
        val atr: MutableMap<Long, Double> = mutableMapOf()
        val sigma: MutableMap<Long, Double> = mutableMapOf()

        override suspend fun fastWindow(instrumentId: Long): MarketSnapshot {
            return fastSnapshots[instrumentId] ?: MarketSnapshot(InstrumentClass.MOEX_BLUE, 0.0, 1.0)
        }

        override suspend fun dayWindow(instrumentId: Long): MarketSnapshot {
            return daySnapshots[instrumentId] ?: MarketSnapshot(InstrumentClass.MOEX_BLUE, 0.0, 1.0)
        }

        override suspend fun atr14(instrumentId: Long): Double? {
            return atr[instrumentId]
        }

        override suspend fun sigma30d(instrumentId: Long): Double? {
            return sigma[instrumentId]
        }
    }

    private class FakeNotifierPort : NotifierPort {
        val events: MutableList<AlertEvent> = mutableListOf()
        val portfolioEvents: MutableList<PortfolioAlertEvent> = mutableListOf()

        override suspend fun push(instrumentId: Long, event: AlertEvent) {
            events.add(event)
        }

        override suspend fun pushPortfolio(portfolioId: UUID, event: PortfolioAlertEvent) {
            portfolioEvents.add(event)
        }
    }

    private class FakePortfolioPort(
        var dayChange: Double = 0.0,
        var drawdown: Double = 0.0
    ) : PortfolioPort {
        override suspend fun dayChangePct(portfolioId: UUID): Double {
            return dayChange
        }

        override suspend fun drawdownPct(portfolioId: UUID): Double {
            return drawdown
        }
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
