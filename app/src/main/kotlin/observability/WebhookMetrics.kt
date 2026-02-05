package observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.atomic.AtomicInteger

class WebhookMetrics private constructor(
    private val registry: MeterRegistry,
    val queueSize: AtomicInteger,
    val dropped: Counter,
    val processed: Counter,
    val handleTimer: Timer,
) {
    fun startSample(): Timer.Sample = Timer.start(registry)

    companion object {
        fun create(registry: MeterRegistry): WebhookMetrics {
            val queueSize = AtomicInteger(0)
            Gauge.builder("webhook_queue_size", queueSize) { it.get().toDouble() }.register(registry)
            val dropped = Counter.builder("webhook_queue_dropped_total").register(registry)
            val processed = Counter.builder("webhook_queue_processed_total").register(registry)
            val handleTimer = Timer.builder("webhook_handle_seconds").register(registry)
            return WebhookMetrics(registry, queueSize, dropped, processed, handleTimer)
        }
    }
}
