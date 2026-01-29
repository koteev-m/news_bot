package observability.adapters

import alerts.metrics.AlertMetricsPort
import news.metrics.NewsMetricsPort
import news.metrics.NewsPublishResult
import news.metrics.NewsPublishType
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
}
