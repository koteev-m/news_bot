package alerts

import io.kotest.common.ExperimentalKotest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.checkAll
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalKotest::class)
class AlertsServicePropertyTest : FunSpec({
    val baseConfig = EngineConfig(
        quietHours = QuietHours(23, 7),
        thresholds = ThresholdMatrix(mapOf("breakout" to Thresholds(0.5, 0.5))),
        volumeGateK = 1.0,
        confirmT = DurationRange(0.seconds, 0.seconds),
        cooldownT = DurationRange(60.minutes, 60.minutes),
        zoneId = ZoneOffset.UTC
    )

    test("budget limits deliveries within one day") {
        val config = PropTestConfig(seed = 1337, iterations = 20)
        checkAll(config, Arb.int(1..20)) { triggerCount ->
            val repo = AlertsRepositoryMemory()
            val service = AlertsService(
                repo,
                baseConfig.copy(cooldownT = DurationRange(0.seconds, 0.seconds)),
                SimpleMeterRegistry()
            )
            val baseTs = LocalDateTime.of(2024, 1, 2, 12, 0).toEpochSecond(ZoneOffset.UTC)
            repeat(triggerCount) { index ->
                service.onSnapshot(
                    MarketSnapshot(
                        tsEpochSec = baseTs + index,
                        userId = 101,
                        items = listOf(SignalItem("T$index", "breakout", "daily", pctMove = 1.0))
                    )
                )
            }
            val day = LocalDateTime.ofEpochSecond(baseTs, 0, ZoneOffset.UTC).toLocalDate()
            repo.getDailyPushCount(101, day) shouldBeLessThanOrEqual 6
        }
    }

    test("cooldown blocks repeated deliveries inside window") {
        val config = PropTestConfig(seed = 2024, iterations = 20)
        val cooldownSeconds = 10.minutes.inWholeSeconds
        checkAll(config, Arb.long(1L..(cooldownSeconds - 1))) { delta ->
            val repo = AlertsRepositoryMemory()
            val service = AlertsService(
                repo,
                baseConfig.copy(cooldownT = DurationRange(10.minutes, 10.minutes)),
                SimpleMeterRegistry()
            )
            val baseTs = LocalDateTime.of(2024, 1, 3, 12, 0).toEpochSecond(ZoneOffset.UTC)
            service.onSnapshot(
                MarketSnapshot(
                    tsEpochSec = baseTs,
                    userId = 202,
                    items = listOf(SignalItem("CD", "breakout", "daily", pctMove = 1.0))
                )
            )
            service.onSnapshot(
                MarketSnapshot(
                    tsEpochSec = baseTs + delta,
                    userId = 202,
                    items = listOf(SignalItem("CD", "breakout", "daily", pctMove = 1.0))
                )
            )
            val day = LocalDateTime.ofEpochSecond(baseTs, 0, ZoneOffset.UTC).toLocalDate()
            repo.getDailyPushCount(202, day).shouldBe(1)
        }
    }

    test("hysteresis exits armed when value drops below exit threshold") {
        val config = PropTestConfig(seed = 7077, iterations = 20)
        checkAll(config, Arb.double(0.0, 0.74)) { pctMove ->
            val repo = AlertsRepositoryMemory()
            val service = AlertsService(
                repo,
                baseConfig.copy(thresholds = ThresholdMatrix(mapOf("breakout" to Thresholds(1.0, 1.0)))),
                SimpleMeterRegistry()
            )
            repo.setState(303, FsmState.ARMED(100))
            val result = service.onSnapshot(
                MarketSnapshot(
                    tsEpochSec = 200,
                    userId = 303,
                    items = listOf(SignalItem("H", "breakout", "fast", pctMove = pctMove))
                )
            )
            result.newState.shouldBe(FsmState.IDLE)
        }
    }

    test("quiet hours buffer and flush portfolio summary once") {
        val config = PropTestConfig(seed = 9001, iterations = 10)
        checkAll(config, Arb.int(1..5)) { repeatCount ->
            val repo = AlertsRepositoryMemory()
            val service = AlertsService(repo, baseConfig, SimpleMeterRegistry())
            val quietTs = LocalDateTime.of(2024, 1, 4, 23, 30).toEpochSecond(ZoneOffset.UTC)
            repeat(repeatCount) {
                service.onSnapshot(
                    MarketSnapshot(
                        tsEpochSec = quietTs,
                        userId = 404,
                        items = emptyList(),
                        portfolio = PortfolioSnapshot(totalChangePctDay = 2.5)
                    )
                )
            }
            val flushTs = quietTs + 9 * 3600
            val flush = service.onSnapshot(MarketSnapshot(tsEpochSec = flushTs, userId = 404, items = emptyList()))
            flush.emitted.size.shouldBe(1)
            flush.emitted.first().alert.classId.shouldBe("portfolio_summary")
            val day = LocalDateTime.ofEpochSecond(flushTs, 0, ZoneOffset.UTC).toLocalDate()
            repo.getDailyPushCount(404, day).shouldBe(1)
        }
    }
})
