package views

import interfaces.ChannelViewsClient
import io.micrometer.core.instrument.MeterRegistry
import java.util.Collections
import kotlin.math.max

class PostViewsService(
    private val client: ChannelViewsClient,
    private val registry: MeterRegistry
) {
    private val lastSeen = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                return size > MAX_ENTRIES
            }
        }
    )

    suspend fun sync(channel: String, ids: List<Int>): Map<Int, Long> {
        val normalizedChannel = channel.trim().lowercase()
        val views = client.getViews(channel, ids, increment = false)
        views.forEach { (id, count) ->
            val key = "$normalizedChannel:$id"
            val delta = synchronized(lastSeen) {
                val last = lastSeen[key] ?: 0L
                val diff = max(0L, count - last)
                lastSeen[key] = count
                diff
            }
            if (delta > 0L) {
                registry.counter(METRIC_NAME, LABEL_POST_ID, id.toString()).increment(delta.toDouble())
            }
        }
        return views
    }

    private companion object {
        private const val METRIC_NAME = "post_views_total"
        private const val LABEL_POST_ID = "post_id"
        private const val MAX_ENTRIES = 50_000
    }
}
