package observability.funnel

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class FunnelMetrics(registry: MeterRegistry) {
    private val postViews = registry.counter("funnel_post_views")
    private val ctaClicks = registry.counter("funnel_cta_clicks")
    private val botStarts = registry.counter("funnel_bot_starts")
    private val sloTimer: Timer = registry.timer("funnel_breaking_latency_seconds")

    fun recordPostView() {
        postViews.increment()
    }

    fun recordCtaClick() {
        ctaClicks.increment()
    }

    fun recordBotStart() {
        botStarts.increment()
    }

    fun recordBreakingLatency(seconds: Double) {
        sloTimer.record((seconds.seconds).toJavaDuration())
    }
}
