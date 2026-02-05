package news.rss

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

class RedisFeedStateStore(
    private val client: RedisClient,
    private val connection: StatefulRedisConnection<String, String> = client.connect(),
) : FeedStateStore {
    override suspend fun get(sourceId: String): FeedState? =
        withContext(Dispatchers.IO) {
            val data = connection.sync().hgetall(keyFor(sourceId))
            if (data.isEmpty()) {
                return@withContext null
            }
            val lastFetchedRaw = data[LAST_FETCHED_AT]?.toLongOrNull() ?: return@withContext null
            val lastSuccessAt = data[LAST_SUCCESS_AT]?.toLongOrNull()?.let { Instant.ofEpochMilli(it) }
            val cooldownUntil = data[COOLDOWN_UNTIL]?.toLongOrNull()?.let { Instant.ofEpochMilli(it) }
            FeedState(
                sourceId = sourceId,
                etag = data[ETAG],
                lastModified = data[LAST_MODIFIED],
                lastFetchedAt = Instant.ofEpochMilli(lastFetchedRaw),
                lastSuccessAt = lastSuccessAt,
                failureCount = data[FAILURE_COUNT]?.toIntOrNull() ?: 0,
                cooldownUntil = cooldownUntil,
            )
        }

    override suspend fun upsert(state: FeedState) =
        withContext(Dispatchers.IO) {
            val data = mutableMapOf<String, String>()
            state.etag?.let { data[ETAG] = it }
            state.lastModified?.let { data[LAST_MODIFIED] = it }
            data[LAST_FETCHED_AT] = state.lastFetchedAt.toEpochMilli().toString()
            state.lastSuccessAt?.let { data[LAST_SUCCESS_AT] = it.toEpochMilli().toString() }
            data[FAILURE_COUNT] = state.failureCount.toString()
            state.cooldownUntil?.let { data[COOLDOWN_UNTIL] = it.toEpochMilli().toString() }
            if (data.isNotEmpty()) {
                connection.sync().hset(keyFor(state.sourceId), data)
            }
        }

    fun close() {
        connection.close()
        client.shutdown()
    }

    companion object {
        private const val ETAG = "etag"
        private const val LAST_MODIFIED = "last_modified"
        private const val LAST_FETCHED_AT = "last_fetched_at"
        private const val LAST_SUCCESS_AT = "last_success_at"
        private const val FAILURE_COUNT = "failure_count"
        private const val COOLDOWN_UNTIL = "cooldown_until"

        private fun keyFor(sourceId: String): String = "feed_state:$sourceId"

        fun buildRedisUri(settings: RedisFeedStateSettings): RedisURI {
            val builder = RedisURI.Builder.redis(settings.host, settings.port).withDatabase(settings.db)
            settings.password?.let { builder.withPassword(it.toCharArray()) }
            return builder.build()
        }
    }
}

data class RedisFeedStateSettings(
    val host: String,
    val port: Int,
    val db: Int,
    val password: String?,
)
