package observability.funnel

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToLong

class FunnelMetrics(registry: MeterRegistry) {
    private val maxBreakingLatency: Duration = Duration.ofHours(24)
    private val postViews = registry.counter("post_views_total")
    private val ctaClicks = registry.counter("cta_click_total")
    private val botStarts = registry.counter("bot_start_total")
    private val sloTimer: Timer = Timer.builder("breaking_publish_latency_seconds")
        .publishPercentileHistogram(true)
        .serviceLevelObjectives(
            Duration.ofSeconds(30),
            Duration.ofSeconds(60),
            Duration.ofSeconds(120),
            Duration.ofSeconds(300),
            Duration.ofSeconds(600),
            Duration.ofSeconds(900),
            Duration.ofSeconds(1200),
        )
        .register(registry)

    fun recordPostView() {
        postViews.increment()
    }

    fun recordCtaClick() {
        ctaClicks.increment()
    }

    fun recordBotStart() {
        botStarts.increment()
    }

    fun recordBreakingLatency(startTs: Instant, sourceTs: Instant) {
        val latency = Duration.between(sourceTs, startTs)
        recordDurationSafe(latency)
    }

    fun recordBreakingLatency(seconds: Double) {
        if (seconds.isNaN() || seconds.isInfinite()) {
            return
        }
        val normalizedSeconds = seconds.coerceAtLeast(0.0).coerceAtMost(MAX_BREAKING_LATENCY_SECONDS)
        val nanos = (normalizedSeconds * NANOS_PER_SECOND).roundToLong()
        recordDurationSafe(Duration.ofNanos(nanos))
    }

    private fun recordDurationSafe(duration: Duration) {
        val normalized = when {
            duration.isNegative -> Duration.ZERO
            duration > maxBreakingLatency -> maxBreakingLatency
            else -> duration
        }
        sloTimer.record(normalized)
    }

    private companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val MAX_BREAKING_LATENCY_SECONDS = 24 * 60 * 60.0
    }
}
