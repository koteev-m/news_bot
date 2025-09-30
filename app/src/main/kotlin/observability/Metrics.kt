package observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer

class DomainMetrics(private val registry: MeterRegistry) {
    // Billing / Webhook
    val webhookStarsSuccess: Counter = registry.counter("webhook_stars_success_total")
    val webhookStarsDuplicate: Counter = registry.counter("webhook_stars_duplicate_total")

    // Alerts
    val alertsPush: Counter = registry.counter("alerts_push_total")
    val alertsBudgetReject: Counter = registry.counter("alerts_budget_reject_total")

    // News
    val newsPublish: Counter = registry.counter("news_publish_total")

    // Optional processing timer for webhook item (already in P26 queue, but available here too)
    val webhookHandleTimer: Timer = registry.timer("webhook_handle_seconds")
}
