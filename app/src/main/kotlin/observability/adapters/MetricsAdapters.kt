package observability.adapters

import alerts.metrics.AlertMetricsPort
import news.metrics.NewsMetricsPort
import news.metrics.NewsPublishResult
import news.metrics.NewsPublishType
import news.model.EventType
import news.moderation.ModerationStatus
import news.pipeline.PublishJobStatus
import news.routing.DropReason
import news.routing.EventRoute
import news.rss.RssFetchMetrics
import observability.DomainMetrics
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.util.AtomicDouble
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class AlertMetricsAdapter(private val metrics: DomainMetrics) : AlertMetricsPort {
    override fun incPush() {
        metrics.alertsPush.increment()
    }

    override fun incBudgetReject() {
        metrics.alertsBudgetReject.increment()
    }
}

class NewsMetricsAdapter(private val metrics: DomainMetrics) : NewsMetricsPort {
    private val dedupRatio = AtomicDouble(0.0)

    init {
        metrics.meterRegistry.gauge("dedup_ratio", dedupRatio)
    }

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

    override fun incPublishJobStatus(status: PublishJobStatus, count: Int) {
        if (count <= 0) return
        metrics.meterRegistry.counter(
            "publish_jobs_total",
            "status",
            status.name.lowercase(),
        ).increment(count.toDouble())
    }

    override fun incModerationQueueStatus(status: ModerationStatus, count: Int) {
        if (count <= 0) return
        metrics.meterRegistry.counter(
            "moderation_queue_total",
            "status",
            status.name.lowercase(),
        ).increment(count.toDouble())
    }

    override fun setDedupRatio(ratio: Double) {
        dedupRatio.set(ratio.coerceIn(0.0, 1.0))
    }
}

class RssFetchMetricsAdapter(private val metrics: DomainMetrics) : RssFetchMetrics {
    private val cooldownActive = ConcurrentHashMap<String, AtomicInteger>()

    override fun incCooldownTotal(sourceId: String) {
        metrics.meterRegistry.counter(
            "feed_cooldown_total",
            "src",
            sourceId,
        ).increment()
    }

    override fun markCooldownActive(sourceId: String, active: Boolean) {
        val gauge = cooldownActive.computeIfAbsent(sourceId) { key ->
            val holder = AtomicInteger(0)
            metrics.meterRegistry.gauge("feed_cooldown_active", Tags.of("src", key), holder)
            holder
        }
        gauge.set(if (active) 1 else 0)
    }
}
