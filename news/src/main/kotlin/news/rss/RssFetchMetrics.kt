package news.rss

interface RssFetchMetrics {
    fun incCooldownTotal(sourceId: String)
    fun markCooldownActive(sourceId: String, active: Boolean)

    companion object {
        val Noop: RssFetchMetrics = object : RssFetchMetrics {
            override fun incCooldownTotal(sourceId: String) = Unit
            override fun markCooldownActive(sourceId: String, active: Boolean) = Unit
        }
    }
}
