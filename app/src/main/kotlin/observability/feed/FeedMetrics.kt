package observability.feed

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.Tags
import java.util.concurrent.atomic.AtomicLong

open class FeedMetrics(
    private val registry: MeterRegistry,
    private val source: String,
) {
    private val tags: Tags = Tags.of("src", source)

    val pullSuccess: Counter = registry.counter("feed_pull_success_total", tags)
    val pullError: Counter = registry.counter("feed_pull_error_total", tags)
    val lastTimestampSeconds: AtomicLong = AtomicLong(0)
    val pullLatency: Timer = registry.timer("feed_pull_latency_seconds", tags)

    init {
        registry.gauge("feed_last_ts", tags, lastTimestampSeconds) { it.get().toDouble() }
    }

    fun updateLastTimestamp(epochSeconds: Long) {
        lastTimestampSeconds.set(epochSeconds)
    }
}

class Netflow2Metrics(registry: MeterRegistry) : FeedMetrics(registry, SOURCE) {
    companion object {
        const val SOURCE = "netflow2"
    }
}
