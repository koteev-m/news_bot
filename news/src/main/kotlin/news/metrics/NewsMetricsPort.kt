package news.metrics

interface NewsMetricsPort {
    fun incPublish()

    companion object {
        val Noop: NewsMetricsPort = object : NewsMetricsPort {
            override fun incPublish() {
            }
        }
    }
}
