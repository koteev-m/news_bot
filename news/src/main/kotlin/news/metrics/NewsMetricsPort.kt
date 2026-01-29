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

    companion object {
        val Noop: NewsMetricsPort = object : NewsMetricsPort {
            override fun incPublish(type: NewsPublishType, result: NewsPublishResult) {
            }

            override fun incEdit() {
            }
        }
    }
}
