package observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer

class DomainMetrics(
    val meterRegistry: MeterRegistry,
) {
    // Billing / Webhook
    val webhookStarsSuccess: Counter = meterRegistry.counter("webhook_stars_success_total")
    val webhookStarsDuplicate: Counter = meterRegistry.counter("webhook_stars_duplicate_total")

    // Alerts
    val alertsPush: Counter = meterRegistry.counter("alerts_push_total")
    val alertsBudgetReject: Counter = meterRegistry.counter("alerts_budget_reject_total")

    // News
    val newsPublish: Counter = meterRegistry.counter("news_publish_total")

    // Optional processing timer for webhook item (already in P26 queue, but available here too)
    val webhookHandleTimer: Timer = meterRegistry.timer("webhook_handle_seconds")
}
