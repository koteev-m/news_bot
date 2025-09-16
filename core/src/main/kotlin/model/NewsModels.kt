package model

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class NewsItemDto(
    val itemId: Long,
    val sourceId: Long,
    val url: String,
    val title: String,
    val body: String?,
    @Contextual val publishedAt: Instant,
    val language: String,
    val topics: List<String>,
    val tickers: List<String>,
    val hashFast: String?,
    val hashSimhash: Long?,
    @Contextual val shingleMinhash: ByteArray?,
    @Contextual val createdAt: Instant
)

@Serializable
data class NewsClusterDto(
    @Contextual val clusterId: UUID,
    val canonicalItemId: Long?,
    val canonicalUrl: String?,
    @Contextual val score: BigDecimal,
    @Contextual val firstSeen: Instant,
    @Contextual val lastSeen: Instant,
    val topics: List<String>,
    val tickers: List<String>,
    val size: Int,
    val clusterKey: String
)
