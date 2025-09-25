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
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class DynamicScaleTest {
    @org.junit.jupiter.api.Test
    fun thresholdsScaleUpWithAtr() = runTest {
        val clock = MutableClock(Instant.parse("2023-01-01T00:00:00Z"))
        val market = FakeMarket()
        market.fastSnapshots[1L] = MarketSnapshot(InstrumentClass.MOEX_BLUE, 2.3, 2.0)
        market.daySnapshots[1L] = MarketSnapshot(InstrumentClass.MOEX_BLUE, 0.0, 1.0)
        market.atr[1L] = 1.2
        market.sigma[1L] = 1.0
        val notifier = CollectingNotifier()
        val engine = createEngine(clock, market, notifier, dynamic = DynamicScale(enabled = true))

        engine.checkInstrument(1L)
        assertEquals(0, notifier.events.size)

        market.fastSnapshots[1L] = MarketSnapshot(InstrumentClass.MOEX_BLUE, 2.5, 2.0)
        engine.checkInstrument(1L)
        assertEquals(1, notifier.events.size)
        assertEquals(AlertEvent.HysteresisState.ENTER, notifier.events.first().hysteresisState)
    }

    @org.junit.jupiter.api.Test
    fun thresholdsClampWhenAtrLow() = runTest {
        val clock = MutableClock(Instant.parse("2023-01-01T00:00:00Z"))
        val market = FakeMarket()
        market.fastSnapshots[1L] = MarketSnapshot(InstrumentClass.MOEX_BLUE, 1.3, 2.0)
        market.daySnapshots[1L] = MarketSnapshot(InstrumentClass.MOEX_BLUE, 0.0, 1.0)
        market.atr[1L] = 0.5
        market.sigma[1L] = 1.0
        val notifier = CollectingNotifier()
        val engine = createEngine(clock, market, notifier, dynamic = DynamicScale(enabled = true, min = 0.7, max = 1.3))

        engine.checkInstrument(1L)
        assertEquals(0, notifier.events.size)

        market.fastSnapshots[1L] = MarketSnapshot(InstrumentClass.MOEX_BLUE, 1.5, 2.0)
        engine.checkInstrument(1L)
        assertEquals(1, notifier.events.size)
    }

    private fun createEngine(
        clock: MutableClock,
        market: FakeMarket,
        notifier: CollectingNotifier,
        dynamic: DynamicScale
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
            quiet = QuietHours(LocalTime.of(22, 0), LocalTime.of(22, 30)),
            budget = Budget(maxPushesPerDay = 10),
            matrix = matrix,
            dynamic = dynamic,
            cooldownMinutes = 0
        )
        return AlertEngine(
            cfg = config,
            market = market,
            portfolio = object : PortfolioPort {
                override suspend fun dayChangePct(portfolioId: UUID): Double = 0.0
                override suspend fun drawdownPct(portfolioId: UUID): Double = 0.0
            },
            notifier = notifier,
            cooldown = CooldownRegistry(clock, 0),
            budget = BudgetLimiter(config.budget, clock),
            quiet = QuietHoursGuard(config.quiet, ZoneOffset.UTC),
            clock = clock
        )
    }

    private class FakeMarket : MarketDataPort {
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

    private class CollectingNotifier : NotifierPort {
        val events: MutableList<AlertEvent> = mutableListOf()

        override suspend fun push(instrumentId: Long, event: AlertEvent) {
            events.add(event)
        }

        override suspend fun pushPortfolio(portfolioId: UUID, event: alerts.model.PortfolioAlertEvent) {
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
    }
}
