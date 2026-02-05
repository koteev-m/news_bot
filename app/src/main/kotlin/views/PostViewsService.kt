package views

import interfaces.ChannelViewsClient
import io.micrometer.core.instrument.MeterRegistry
import java.util.Collections
import java.util.Locale
import kotlin.math.max

class PostViewsService(
    private val client: ChannelViewsClient,
    private val registry: MeterRegistry,
) {
    private val lastSeen =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, Long>(MAX_ENTRIES, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
                    size > MAX_ENTRIES
            },
        )

    suspend fun sync(
        channel: String,
        ids: List<Int>,
    ): Map<Int, Long> {
        val normalizedChannel = normalizeChannelKey(channel)
        val views = client.getViews(normalizedChannel, ids, increment = false)
        views.forEach { (id, count) ->
            val key = "$normalizedChannel:$id"
            val delta =
                synchronized(lastSeen) {
                    val last = lastSeen[key] ?: 0L
                    val safeCount = max(0L, count)
                    if (safeCount >= last) {
                        val diff = safeCount - last
                        lastSeen[key] = safeCount
                        diff
                    } else {
                        0L
                    }
                }
            if (delta > 0L) {
                registry.counter(METRIC_NAME, LABEL_POST_ID, id.toString()).increment(delta.toDouble())
            }
        }
        return views
    }

    private fun normalizeChannelKey(channel: String): String {
        val trimmed = channel.trim()
        val withPrefix = if (trimmed.startsWith("@")) trimmed else "@$trimmed"
        return withPrefix.lowercase(Locale.ROOT)
    }

    private companion object {
        private const val METRIC_NAME = "post_views_total"
        private const val LABEL_POST_ID = "post_id"
        private const val MAX_ENTRIES = 50_000
    }
}
