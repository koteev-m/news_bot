package news.metrics

enum class NewsPublishType {
    BREAKING,
    DIGEST,
}

enum class NewsPublishResult {
    CREATED,
    EDITED,
    SKIPPED,
    FAILED,
}

interface NewsMetricsPort {
    fun incPublish(type: NewsPublishType, result: NewsPublishResult)
    fun incEdit()
    fun incCandidatesReceived(sourceId: String, count: Int)
    fun incClustersCreated(eventType: news.model.EventType)
    fun incRouted(route: news.routing.EventRoute)
    fun incDropped(reason: news.routing.DropReason)
    fun incPublishJobStatus(status: news.pipeline.PublishJobStatus, count: Int = 1)
    fun incModerationQueueStatus(status: news.moderation.ModerationStatus, count: Int = 1)
    fun setDedupRatio(ratio: Double)

    companion object {
        val Noop: NewsMetricsPort = object : NewsMetricsPort {
            override fun incPublish(type: NewsPublishType, result: NewsPublishResult) {
            }

            override fun incEdit() {
            }

            override fun incCandidatesReceived(sourceId: String, count: Int) {
            }

            override fun incClustersCreated(eventType: news.model.EventType) {
            }

            override fun incRouted(route: news.routing.EventRoute) {
            }

            override fun incDropped(reason: news.routing.DropReason) {
            }

            override fun incPublishJobStatus(status: news.pipeline.PublishJobStatus, count: Int) {
            }

            override fun incModerationQueueStatus(status: news.moderation.ModerationStatus, count: Int) {
            }

            override fun setDedupRatio(ratio: Double) {
            }
        }
    }
}
