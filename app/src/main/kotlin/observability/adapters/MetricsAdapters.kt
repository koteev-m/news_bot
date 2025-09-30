package observability.adapters

import alerts.metrics.AlertMetricsPort
import news.metrics.NewsMetricsPort
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
    override fun incPublish() {
        metrics.newsPublish.increment()
    }
}
