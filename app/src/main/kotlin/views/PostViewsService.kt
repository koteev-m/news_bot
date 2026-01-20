package views

import interfaces.ChannelViewsClient
import io.micrometer.core.instrument.MeterRegistry

class PostViewsService(
    private val client: ChannelViewsClient,
    private val registry: MeterRegistry
) {
    suspend fun sync(channel: String, ids: List<Int>): Map<Int, Long> {
        val views = client.getViews(channel, ids, increment = false)
        views.forEach { (id, count) ->
            if (count > 0L) {
                registry.counter(METRIC_NAME, LABEL_POST_ID, id.toString()).increment(count.toDouble())
            }
        }
        return views
    }

    private companion object {
        private const val METRIC_NAME = "post_views_total"
        private const val LABEL_POST_ID = "post_id"
    }
}
