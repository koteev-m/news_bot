package news.publisher.store

import java.util.UUID

data class PublishedPost(
    val messageId: Long,
    val contentHash: String?,
    val duplicateCount: Int,
)

interface PostStatsStore {
    suspend fun findByCluster(channelId: Long, clusterId: UUID): PublishedPost?

    suspend fun recordNew(
        channelId: Long,
        clusterId: UUID,
        messageId: Long,
        contentHash: String,
    )

    suspend fun recordEdit(
        channelId: Long,
        clusterId: UUID,
        contentHash: String,
    )

    suspend fun recordDuplicate(
        channelId: Long,
        clusterId: UUID,
    )
}
