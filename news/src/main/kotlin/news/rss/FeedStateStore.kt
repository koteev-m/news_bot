package news.rss

import java.time.Instant

data class FeedState(
    val sourceId: String,
    val etag: String?,
    val lastModified: String?,
    val lastFetchedAt: Instant,
    val lastSuccessAt: Instant?,
    val failureCount: Int,
    val cooldownUntil: Instant?,
)

interface FeedStateStore {
    suspend fun get(sourceId: String): FeedState?
    suspend fun upsert(state: FeedState)

    companion object {
        val Noop: FeedStateStore = object : FeedStateStore {
            override suspend fun get(sourceId: String): FeedState? = null
            override suspend fun upsert(state: FeedState) = Unit
        }
    }
}
