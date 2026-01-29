package observability.adapters

import alerts.metrics.AlertMetricsPort
import news.metrics.NewsMetricsPort
import news.metrics.NewsPublishResult
import news.metrics.NewsPublishType
import news.model.EventType
import news.routing.DropReason
import news.routing.EventRoute
import observability.DomainMetrics

class AlertMetricsAdapter(private val metrics: DomainMetrics) : AlertMetricsPort {
    override fun incPush() {
        metrics.alertsPush.increment()
    }

    override fun incBudgetReject() {
        metrics.alertsBudgetReject.increment()
    }
}

class NewsMetricsAdapter(private val metrics: DomainMetrics) : NewsMetricsPort {
    override fun incPublish(type: NewsPublishType, result: NewsPublishResult) {
        metrics.meterRegistry.counter(
            "news_publish_total",
            "type",
            type.name.lowercase(),
            "result",
            result.name.lowercase(),
        ).increment()
    }

    override fun incEdit() {
        metrics.meterRegistry.counter("news_edit_total").increment()
    }

    override fun incCandidatesReceived(sourceId: String, count: Int) {
        metrics.meterRegistry.counter(
            "candidates_received_total",
            "source_id",
            sourceId,
        ).increment(count.toDouble())
    }

    override fun incClustersCreated(eventType: EventType) {
        metrics.meterRegistry.counter(
            "clusters_created_total",
            "event_type",
            eventType.name.lowercase(),
        ).increment()
    }

    override fun incRouted(route: EventRoute) {
        metrics.meterRegistry.counter(
            "routed_total",
            "route",
            route.name.lowercase(),
        ).increment()
    }

    override fun incDropped(reason: DropReason) {
        metrics.meterRegistry.counter(
            "dropped_total",
            "reason",
            reason.name.lowercase(),
        ).increment()
    }
}
