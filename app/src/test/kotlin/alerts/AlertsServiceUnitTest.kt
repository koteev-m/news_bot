package alerts

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.minutes

class AlertsServiceUnitTest : FunSpec({
    val registry = SimpleMeterRegistry()
    val baseConfig = EngineConfig(
        quietHours = QuietHours(23, 7),
        thresholds = ThresholdMatrix(mapOf("breakout" to Thresholds(0.5, 0.5))),
        volumeGateK = 1.2,
        confirmT = DurationRange(10.minutes, 15.minutes),
        cooldownT = DurationRange(60.minutes, 120.minutes),
        zoneId = ZoneOffset.UTC
    )

    test("cooldown suppresses duplicates") {
        val repo = AlertsRepositoryMemory()
        val service = AlertsService(repo, baseConfig, registry)
        val ts = 1_700_000_000L
        repo.setState(1, FsmState.COOLDOWN(ts + 600))
        val snapshot = MarketSnapshot(
            tsEpochSec = ts,
            userId = 1,
            items = listOf(SignalItem("A", "breakout", "fast", pctMove = 1.0))
        )
        val result = service.onSnapshot(snapshot)
        result.emitted.shouldBe(emptyList())
        result.suppressedReasons.shouldContain("cooldown")
        repo.getState(1).shouldBe(FsmState.COOLDOWN(ts + 600))
    }

    test("hysteresis exits armed when below threshold") {
        val repo = AlertsRepositoryMemory()
        val service = AlertsService(repo, baseConfig, registry)
        val armed = FsmState.ARMED(100)
        repo.setState(2, armed)
        val snapshot = MarketSnapshot(
            tsEpochSec = 200,
            userId = 2,
            items = listOf(SignalItem("B", "breakout", "fast", pctMove = 0.2))
        )
        val result = service.onSnapshot(snapshot)
        result.newState.shouldBe(FsmState.IDLE)
    }

    test("quiet hours buffer and flush once") {
        val repo = AlertsRepositoryMemory()
        val service = AlertsService(repo, baseConfig, registry)
        val quietTs = 1_700_003_600L // 23:13 UTC
        val activeTs = quietTs + 8 * 3600 // after quiet hours
        val snapshotQuiet = MarketSnapshot(
            tsEpochSec = quietTs,
            userId = 3,
            items = listOf(SignalItem("C", "breakout", "fast", pctMove = 1.0))
        )
        val first = service.onSnapshot(snapshotQuiet)
        first.newState.shouldBe(FsmState.QUIET(listOf(PendingAlert("breakout", "C", "fast", 0.5, 1.0, quietTs))))
        val duplicate = service.onSnapshot(snapshotQuiet)
        duplicate.newState.shouldBe(FsmState.QUIET(listOf(PendingAlert("breakout", "C", "fast", 0.5, 1.0, quietTs))))
        duplicate.suppressedReasons.shouldContain("duplicate")

        val flushSnapshot = MarketSnapshot(tsEpochSec = activeTs, userId = 3, items = emptyList())
        val flushed = service.onSnapshot(flushSnapshot)
        flushed.emitted.shouldHaveSize(1)
        flushed.emitted.first().reason.shouldBe("quiet_hours_flush")
    }

    test("volume gate suppresses low volume") {
        val repo = AlertsRepositoryMemory()
        val service = AlertsService(repo, baseConfig.copy(volumeGateK = 1.5), registry)
        val snapshot = MarketSnapshot(
            tsEpochSec = 1,
            userId = 4,
            items = listOf(
                SignalItem(
                    ticker = "V",
                    classId = "breakout",
                    window = "fast",
                    pctMove = 1.0,
                    volume = 90.0,
                    avgVolume = 100.0
                )
            )
        )
        val result = service.onSnapshot(snapshot)
        result.emitted.shouldBe(emptyList())
        result.suppressedReasons.shouldContain("no_volume")
        service.getState(4).shouldBe(FsmState.IDLE)
    }

    test("pro multiplier scales thresholds") {
        val repo = AlertsRepositoryMemory()
        val service = AlertsService(repo, baseConfig, registry)
        val snapshot = MarketSnapshot(
            tsEpochSec = 1_700_043_200L,
            userId = 5,
            items = listOf(
                SignalItem(
                    ticker = "P",
                    classId = "breakout",
                    window = "fast",
                    pctMove = 1.1,
                    atr = 2.0,
                    sigma = 1.0
                )
            )
        )
        val result = service.onSnapshot(snapshot)
        result.emitted.shouldBe(emptyList())
        service.getState(5).shouldBe(FsmState.ARMED(1_700_043_200L))
    }

    test("portfolio summary only once per day and flushes after quiet") {
        val repo = AlertsRepositoryMemory()
        val service = AlertsService(repo, baseConfig, registry)
        val quietTs = 1_700_003_600L
        val snapshot = MarketSnapshot(
            tsEpochSec = quietTs,
            userId = 6,
            items = emptyList(),
            portfolio = PortfolioSnapshot(totalChangePctDay = 2.5)
        )
        val first = service.onSnapshot(snapshot)
        first.newState.shouldBe(FsmState.QUIET(listOf(PendingAlert("portfolio_summary", "", "daily", 0.0, 2.5, quietTs))))
        val second = service.onSnapshot(snapshot)
        second.newState.shouldBe(first.newState)

        val flush = service.onSnapshot(snapshot.copy(tsEpochSec = quietTs + 9 * 3600, items = emptyList()))
        flush.emitted.shouldHaveSize(1)
        flush.emitted.first().alert.classId.shouldBe("portfolio_summary")
    }
})
