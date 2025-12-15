package alerts

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.minutes

class AlertsServicePropertyTest : FunSpec({
    test("daily pushes respect budget") {
        val counts = listOf(6, 7, 8, 10, 12)
        counts.forEach { count ->
            val registry = SimpleMeterRegistry()
            val repo = AlertsRepositoryMemory()
            val config = EngineConfig(
                thresholds = ThresholdMatrix(mapOf("breakout" to Thresholds(0.1, 0.1))),
                dailyBudgetPushMax = 6,
                quietHours = QuietHours(23, 7),
                confirmT = DurationRange(10.minutes, 15.minutes),
                cooldownT = DurationRange(60.minutes, 120.minutes),
                zoneId = ZoneOffset.UTC
            )
            val service = AlertsService(repo, config, registry)
            val baseTs = 1_700_000_000L
            val date = java.time.Instant.ofEpochSecond(baseTs).atZone(ZoneOffset.UTC).toLocalDate()
            repeat(count) { idx ->
                val snapshot = MarketSnapshot(
                    tsEpochSec = baseTs + idx,
                    userId = 42,
                    items = listOf(SignalItem("T", "breakout", "daily", pctMove = 1.0))
                )
                service.onSnapshot(snapshot)
            }
            repo.getDailyPushCount(42, date).shouldBeLessThanOrEqual(6)
        }
    }
})
