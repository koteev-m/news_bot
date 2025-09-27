package observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer

class DomainMetrics(val registry: MeterRegistry) {
    val newsIngestTimer: Timer = registry.timer("news_ingest_seconds")
    val newsPublishCounter: Counter = registry.counter("news_publish_total")
    val alertsPushCounter: Counter = registry.counter("alerts_push_total")
    val alertsBudgetRejectCounter: Counter = registry.counter("alerts_budget_reject_total")
    val webhookStarsSuccess: Counter = registry.counter("webhook_stars_success_total")

    inline fun <T> timeIngest(block: () -> T): T {
        val sample = Timer.start(registry)
        return try {
            block()
        } finally {
            sample.stop(newsIngestTimer)
        }
    }
}
