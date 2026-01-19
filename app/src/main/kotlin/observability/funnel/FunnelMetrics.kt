package observability.funnel

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.time.Instant

class FunnelMetrics(registry: MeterRegistry) {
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
        val safe = if (latency.isNegative) Duration.ZERO else latency
        sloTimer.record(safe)
    }

    fun recordBreakingLatency(seconds: Double) {
        val millis = (seconds * 1000.0).toLong()
        sloTimer.record(Duration.ofMillis(millis))
    }
}
