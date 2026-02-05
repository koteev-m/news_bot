package alerts

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class AlertsServiceUnitTest :
    FunSpec({
        val registry = SimpleMeterRegistry()
        val baseConfig =
            EngineConfig(
                quietHours = QuietHours(23, 7),
                thresholds = ThresholdMatrix(mapOf("breakout" to Thresholds(0.5, 0.5))),
                volumeGateK = 1.2,
                confirmT = DurationRange(10.minutes, 15.minutes),
                cooldownT = DurationRange(60.minutes, 120.minutes),
                zoneId = ZoneOffset.UTC,
            )

        test("cooldown suppresses duplicates") {
            val repo = AlertsRepositoryMemory()
            val service = AlertsService(repo, baseConfig, registry)
            val ts = 1_700_000_000L
            repo.setState(1, FsmState.Cooldown(ts + 600))
            val snapshot =
                MarketSnapshot(
                    tsEpochSec = ts,
                    userId = 1,
                    items = listOf(SignalItem("A", "breakout", "fast", pctMove = 1.0)),
                )
            val result = service.onSnapshot(snapshot)
            result.emitted.shouldBe(emptyList())
            result.suppressedReasons.shouldContain(AlertSuppressionReasons.COOLDOWN)
            repo.getState(1).shouldBe(FsmState.Cooldown(ts + 600))
        }

        test("hysteresis exits armed when below threshold") {
            val repo = AlertsRepositoryMemory()
            val service = AlertsService(repo, baseConfig, registry)
            val armed = FsmState.Armed(100)
            repo.setState(2, armed)
            val snapshot =
                MarketSnapshot(
                    tsEpochSec = 200,
                    userId = 2,
                    items = listOf(SignalItem("B", "breakout", "fast", pctMove = 0.2)),
                )
            val result = service.onSnapshot(snapshot)
            result.newState.shouldBe(FsmState.Idle)
        }

        test("quiet hours buffer and flush once") {
            val repo = AlertsRepositoryMemory()
            val service = AlertsService(repo, baseConfig, registry)
            val quietTs = 1_700_003_600L // 23:13 UTC
            val activeTs = quietTs + 8 * 3600 // after quiet hours
            val snapshotQuiet =
                MarketSnapshot(
                    tsEpochSec = quietTs,
                    userId = 3,
                    items = listOf(SignalItem("C", "breakout", "fast", pctMove = 1.0)),
                )
            val first = service.onSnapshot(snapshotQuiet)
            first.newState.shouldBe(FsmState.Quiet(listOf(PendingAlert("breakout", "C", "fast", 0.5, 1.0, quietTs))))
            val duplicate = service.onSnapshot(snapshotQuiet)
            duplicate.newState.shouldBe(
                FsmState.Quiet(listOf(PendingAlert("breakout", "C", "fast", 0.5, 1.0, quietTs))),
            )
            duplicate.suppressedReasons.shouldContain(AlertSuppressionReasons.DUPLICATE)

            val flushSnapshot = MarketSnapshot(tsEpochSec = activeTs, userId = 3, items = emptyList())
            val flushed = service.onSnapshot(flushSnapshot)
            flushed.emitted.shouldHaveSize(1)
            flushed.emitted
                .first()
                .reason
                .shouldBe(AlertDeliveryReasons.QUIET_HOURS_FLUSH)
        }

        test("volume gate suppresses low volume") {
            val repo = AlertsRepositoryMemory()
            val service = AlertsService(repo, baseConfig.copy(volumeGateK = 1.5), registry)
            val snapshot =
                MarketSnapshot(
                    tsEpochSec = 1,
                    userId = 4,
                    items =
                    listOf(
                        SignalItem(
                            ticker = "V",
                            classId = "breakout",
                            window = "fast",
                            pctMove = 1.0,
                            volume = 90.0,
                            avgVolume = 100.0,
                        ),
                    ),
                )
            val result = service.onSnapshot(snapshot)
            result.emitted.shouldBe(emptyList())
            result.suppressedReasons.shouldContain(AlertSuppressionReasons.NO_VOLUME)
            service.getState(4).shouldBe(FsmState.Idle)
        }

        test("pro multiplier scales thresholds") {
            val repo = AlertsRepositoryMemory()
            val service = AlertsService(repo, baseConfig, registry)
            val snapshot =
                MarketSnapshot(
                    tsEpochSec = 1_700_043_200L,
                    userId = 5,
                    items =
                    listOf(
                        SignalItem(
                            ticker = "P",
                            classId = "breakout",
                            window = "fast",
                            pctMove = 1.1,
                            atr = 2.0,
                            sigma = 1.0,
                        ),
                    ),
                )
            val result = service.onSnapshot(snapshot)
            result.emitted.shouldBe(emptyList())
            service.getState(5).shouldBe(FsmState.Armed(1_700_043_200L))
        }

        test("portfolio summary only once per day and flushes after quiet") {
            val repo = AlertsRepositoryMemory()
            val service = AlertsService(repo, baseConfig, registry)
            val quietTs = 1_700_003_600L
            val flushTs = quietTs + 9 * 3600
            val snapshot =
                MarketSnapshot(
                    tsEpochSec = quietTs,
                    userId = 6,
                    items = emptyList(),
                    portfolio = PortfolioSnapshot(totalChangePctDay = 2.5),
                )
            val first = service.onSnapshot(snapshot)
            first.newState.shouldBe(
                FsmState.Quiet(listOf(PendingAlert("portfolio_summary", "", "daily", 0.0, 2.5, quietTs))),
            )
            val second = service.onSnapshot(snapshot)
            second.newState.shouldBe(first.newState)

            val flush = service.onSnapshot(snapshot.copy(tsEpochSec = flushTs, items = emptyList()))
            flush.emitted.shouldHaveSize(1)
            flush.emitted
                .first()
                .alert.classId
                .shouldBe("portfolio_summary")
            flush.newState.shouldBe(FsmState.PortfolioSummary(flushTs))
        }

        test("portfolio summary outside quiet becomes PORTFOLIO_SUMMARY") {
            val repo = AlertsRepositoryMemory()
            val service = AlertsService(repo, baseConfig, registry)
            val ts = LocalDateTime.of(2024, 1, 2, 12, 0).toEpochSecond(ZoneOffset.UTC)

            val result =
                service.onSnapshot(
                    MarketSnapshot(
                        tsEpochSec = ts,
                        userId = 16,
                        items = emptyList(),
                        portfolio = PortfolioSnapshot(totalChangePctDay = 2.0),
                    ),
                )

            result.emitted.shouldHaveSize(1)
            result.newState.shouldBe(FsmState.PortfolioSummary(ts))
        }

        test("quiet hours boundaries are start-inclusive and end-exclusive") {
            fun tsAt(hour: Int): Long = LocalDateTime.of(2024, 1, 1, hour, 0).toEpochSecond(ZoneOffset.UTC)

            val serviceStart = AlertsService(AlertsRepositoryMemory(), baseConfig, SimpleMeterRegistry())
            val startSnapshot =
                MarketSnapshot(
                    tsEpochSec = tsAt(23),
                    userId = 7,
                    items = listOf(SignalItem("Q1", "breakout", "daily", pctMove = 1.0)),
                )
            val startResult = serviceStart.onSnapshot(startSnapshot)
            startResult.newState.shouldBe(
                FsmState.Quiet(listOf(PendingAlert("breakout", "Q1", "daily", 0.5, 1.0, startSnapshot.tsEpochSec))),
            )

            val serviceInside = AlertsService(AlertsRepositoryMemory(), baseConfig, SimpleMeterRegistry())
            val insideSnapshot =
                MarketSnapshot(
                    tsEpochSec = tsAt(2),
                    userId = 8,
                    items = listOf(SignalItem("Q2", "breakout", "fast", pctMove = 1.0)),
                )
            val insideResult = serviceInside.onSnapshot(insideSnapshot)
            insideResult.newState.shouldBe(
                FsmState.Quiet(listOf(PendingAlert("breakout", "Q2", "fast", 0.5, 1.0, insideSnapshot.tsEpochSec))),
            )

            val serviceEnd = AlertsService(AlertsRepositoryMemory(), baseConfig, SimpleMeterRegistry())
            val endSnapshot =
                MarketSnapshot(
                    tsEpochSec = tsAt(7),
                    userId = 9,
                    items = listOf(SignalItem("Q3", "breakout", "daily", pctMove = 1.0)),
                )
            val endResult = serviceEnd.onSnapshot(endSnapshot)
            endResult.emitted.shouldHaveSize(1)
            endResult.suppressedReasons.shouldBe(emptyList())
            endResult.newState.shouldBe(FsmState.Pushed(endSnapshot.tsEpochSec))

            val serviceOutside = AlertsService(AlertsRepositoryMemory(), baseConfig, SimpleMeterRegistry())
            val outsideSnapshot =
                MarketSnapshot(
                    tsEpochSec = tsAt(12),
                    userId = 10,
                    items = listOf(SignalItem("Q4", "breakout", "daily", pctMove = 1.0)),
                )
            val outsideResult = serviceOutside.onSnapshot(outsideSnapshot)
            outsideResult.emitted.shouldHaveSize(1)
            outsideResult.suppressedReasons.shouldBe(emptyList())
            outsideResult.newState.shouldBe(FsmState.Pushed(outsideSnapshot.tsEpochSec))
        }

        test("quiet hours flush increments metrics and budget per alert") {
            val repo = AlertsRepositoryMemory()
            val registry = SimpleMeterRegistry()
            val service = AlertsService(repo, baseConfig, registry)
            val quietTs = LocalDateTime.of(2024, 1, 1, 23, 30).toEpochSecond(ZoneOffset.UTC)
            val flushTs = quietTs + 9 * 3600

            val first =
                MarketSnapshot(
                    tsEpochSec = quietTs,
                    userId = 11,
                    items = listOf(SignalItem("F1", "breakout", "daily", pctMove = 1.0)),
                )
            val second =
                MarketSnapshot(
                    tsEpochSec = quietTs + 60,
                    userId = 11,
                    items = listOf(SignalItem("F2", "breakout", "fast", pctMove = 1.0)),
                )

            service.onSnapshot(first)
            service.onSnapshot(second)

            val flush = service.onSnapshot(MarketSnapshot(tsEpochSec = flushTs, userId = 11, items = emptyList()))
            flush.emitted.shouldHaveSize(2)
            flush.emitted.all { it.reason == AlertDeliveryReasons.QUIET_HOURS_FLUSH }.shouldBe(true)
            flush.newState.shouldBe(FsmState.Pushed(flushTs))

            val flushDate = LocalDateTime.ofEpochSecond(flushTs, 0, ZoneOffset.UTC).toLocalDate()
            repo.getDailyPushCount(11, flushDate).shouldBe(2)
            registry
                .counter(
                    "alert_delivered_total",
                    "reason",
                    AlertDeliveryReasons.QUIET_HOURS_FLUSH,
                ).count()
                .shouldBe(2.0)
        }

        test("below-threshold suppression increments once and allows hysteresis exit") {
            val repo = AlertsRepositoryMemory()
            val registry = SimpleMeterRegistry()
            val service = AlertsService(repo, baseConfig, registry)
            val armedState = FsmState.Armed(50)
            repo.setState(12, armedState)

            val snapshot =
                MarketSnapshot(
                    tsEpochSec = 100,
                    userId = 12,
                    items =
                    listOf(
                        SignalItem("S1", "breakout", "fast", pctMove = 0.2),
                        SignalItem("S2", "breakout", "fast", pctMove = 0.3),
                    ),
                )

            val result = service.onSnapshot(snapshot)
            result.suppressedReasons.shouldBe(listOf(AlertSuppressionReasons.BELOW_THRESHOLD))
            registry
                .counter(
                    "alert_suppressed_total",
                    "reason",
                    AlertSuppressionReasons.BELOW_THRESHOLD,
                ).count()
                .shouldBe(1.0)
            result.newState.shouldBe(FsmState.Idle)
        }

        test("quiet flush honors budget and suppresses leftovers") {
            val repo = AlertsRepositoryMemory()
            val registry = SimpleMeterRegistry()
            val config = baseConfig.copy(dailyBudgetPushMax = 1)
            val service = AlertsService(repo, config, registry)
            val quietTs = LocalDateTime.of(2024, 1, 1, 23, 15).toEpochSecond(ZoneOffset.UTC)
            val flushTs = quietTs + 9 * 3600

            val first =
                MarketSnapshot(
                    tsEpochSec = quietTs,
                    userId = 13,
                    items = listOf(SignalItem("B1", "breakout", "daily", pctMove = 1.0)),
                )
            val second =
                first.copy(
                    tsEpochSec = quietTs + 60,
                    items = listOf(SignalItem("B2", "breakout", "fast", pctMove = 1.0)),
                )

            service.onSnapshot(first)
            service.onSnapshot(second)

            val flush = service.onSnapshot(MarketSnapshot(tsEpochSec = flushTs, userId = 13, items = emptyList()))
            flush.emitted.shouldHaveSize(1)
            flush.emitted
                .first()
                .reason
                .shouldBe(AlertDeliveryReasons.QUIET_HOURS_FLUSH)
            flush.suppressedReasons.shouldBe(listOf(AlertSuppressionReasons.BUDGET))
            flush.newState.shouldBe(FsmState.BudgetExhausted)

            val flushDate = LocalDateTime.ofEpochSecond(flushTs, 0, ZoneOffset.UTC).toLocalDate()
            repo.getDailyPushCount(13, flushDate).shouldBe(1)
            registry
                .counter(
                    "alert_delivered_total",
                    "reason",
                    AlertDeliveryReasons.QUIET_HOURS_FLUSH,
                ).count()
                .shouldBe(1.0)
            registry.counter("alert_suppressed_total", "reason", AlertSuppressionReasons.BUDGET).count().shouldBe(1.0)
        }

        test("candidate selection uses deterministic tie-breaking") {
            val registry = SimpleMeterRegistry()
            val config =
                baseConfig.copy(
                    thresholds =
                    ThresholdMatrix(
                        mapOf(
                            "a" to Thresholds(fast = 0.5, daily = 0.5),
                            "b" to Thresholds(fast = 0.5, daily = 0.5),
                        ),
                    ),
                )
            config.thresholds.getThreshold("a", "daily").shouldBe(0.5)
            config.thresholds.getThreshold("b", "fast").shouldBe(0.5)
            val repo = AlertsRepositoryMemory()
            val service = AlertsService(repo, config, registry)
            val ts = LocalDateTime.of(2024, 1, 2, 12, 0).toEpochSecond(ZoneOffset.UTC)
            val snapshot =
                MarketSnapshot(
                    tsEpochSec = ts,
                    userId = 14,
                    items =
                    listOf(
                        SignalItem("Tfast", "b", "fast", pctMove = 1.0),
                        SignalItem("TdailyB", "b", "daily", pctMove = 1.0),
                        SignalItem("ZZZ", "a", "daily", pctMove = 1.0),
                        SignalItem("AAA", "a", "daily", pctMove = 1.0),
                    ),
                )

            val result = service.onSnapshot(snapshot)
            result.suppressedReasons.shouldBe(emptyList())
            result.emitted.shouldHaveSize(1)
            result.emitted.first().alert.shouldBe(
                PendingAlert("a", "AAA", "daily", score = 0.5, pctMove = 1.0, ts = ts),
            )
            result.newState.shouldBe(FsmState.Pushed(ts))
        }

        test("cooldown expiry allows new delivery") {
            val repo = AlertsRepositoryMemory()
            val registry = SimpleMeterRegistry()
            val service = AlertsService(repo, baseConfig, registry)
            val until = LocalDateTime.of(2024, 1, 2, 8, 0).toEpochSecond(ZoneOffset.UTC)
            repo.setState(15, FsmState.Cooldown(until))

            val snapshot =
                MarketSnapshot(
                    tsEpochSec = until,
                    userId = 15,
                    items = listOf(SignalItem("CD", "breakout", "daily", pctMove = 1.0)),
                )

            val result = service.onSnapshot(snapshot)
            result.suppressedReasons.shouldBe(emptyList())
            result.emitted.shouldHaveSize(1)
            result.emitted
                .first()
                .reason
                .shouldBe(AlertDeliveryReasons.DIRECT)
            result.newState.shouldBe(FsmState.Pushed(until))
            registry.counter("alert_delivered_total", "reason", AlertDeliveryReasons.DIRECT).count().shouldBe(1.0)
        }

        test("pushed cooldown expires on exact boundary") {
            val repo = AlertsRepositoryMemory()
            val registry = SimpleMeterRegistry()
            val config =
                baseConfig.copy(
                    cooldownT = DurationRange(10.minutes, 10.minutes),
                    confirmT = DurationRange(0.minutes, 0.minutes),
                    zoneId = ZoneOffset.UTC,
                )
            val service = AlertsService(repo, config, registry)
            val t0 = LocalDateTime.of(2024, 1, 2, 12, 0).toEpochSecond(ZoneOffset.UTC)
            val snapshot =
                MarketSnapshot(
                    tsEpochSec = t0,
                    userId = 17,
                    items = listOf(SignalItem("CD", "breakout", "daily", pctMove = 1.0)),
                )

            val first = service.onSnapshot(snapshot)
            first.emitted.shouldHaveSize(1)
            first.newState.shouldBe(FsmState.Pushed(t0))
            val date = LocalDateTime.ofEpochSecond(t0, 0, ZoneOffset.UTC).toLocalDate()
            repo.getDailyPushCount(17, date).shouldBe(1)

            val beforeCooldownEnd = service.onSnapshot(snapshot.copy(tsEpochSec = t0 + 10.minutes.inWholeSeconds - 1))
            beforeCooldownEnd.emitted.shouldBe(emptyList())
            beforeCooldownEnd.suppressedReasons.shouldContain(AlertSuppressionReasons.COOLDOWN)
            repo.getDailyPushCount(17, date).shouldBe(1)

            val t2 = t0 + 10.minutes.inWholeSeconds
            val afterCooldownEnd = service.onSnapshot(snapshot.copy(tsEpochSec = t2))
            afterCooldownEnd.emitted.shouldHaveSize(1)
            afterCooldownEnd.newState.shouldBe(FsmState.Pushed(t2))
            repo.getDailyPushCount(17, date).shouldBe(2)
        }

        test("engine config enforces hysteresis exit factor bounds") {
            shouldThrow<IllegalArgumentException> {
                baseConfig.copy(hysteresisExitFactor = 0.0)
            }
            shouldThrow<IllegalArgumentException> {
                baseConfig.copy(hysteresisExitFactor = 1.0)
            }
            shouldThrow<IllegalArgumentException> {
                baseConfig.copy(hysteresisExitFactor = 1.5)
            }
        }

        test("quiet hours require distinct start and end") {
            shouldThrow<IllegalArgumentException> {
                baseConfig.copy(quietHours = QuietHours(5, 5))
            }
        }

        test("engine config validates ranges and budgets") {
            shouldThrow<IllegalArgumentException> {
                baseConfig.copy(dailyBudgetPushMax = 0)
            }
            shouldThrow<IllegalArgumentException> {
                baseConfig.copy(volumeGateK = -0.1)
            }
            shouldThrow<IllegalArgumentException> {
                baseConfig.copy(confirmT = DurationRange((-1).minutes, 1.minutes))
            }
            shouldThrow<IllegalArgumentException> {
                baseConfig.copy(confirmT = DurationRange(5.minutes, 1.minutes))
            }
            shouldThrow<IllegalArgumentException> {
                baseConfig.copy(cooldownT = DurationRange((-30).seconds, 1.minutes))
            }
            shouldThrow<IllegalArgumentException> {
                baseConfig.copy(cooldownT = DurationRange(10.minutes, 5.minutes))
            }
        }
    })
