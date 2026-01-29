package db.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object PostStatsTable : Table("post_stats") {
    val postId = long("post_id").autoIncrement()
    val channelId = long("channel_id")
    val messageId = long("message_id")
    val clusterId = uuid(
        "cluster_id"
    ).references(NewsClustersTable.clusterId, onDelete = ReferenceOption.SET_NULL).nullable()
    val views = integer("views").default(0)
    val postedAt = timestampWithTimeZone("posted_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    val duplicateCount = integer("duplicate_count").default(0)
    val contentHash = text("content_hash").nullable()
    override val primaryKey = PrimaryKey(postId)
    init {
        uniqueIndex("uk_post_channel_message", channelId, messageId)
        uniqueIndex("uk_post_channel_cluster", channelId, clusterId)
        index("idx_post_stats_cluster", false, clusterId)
    }
}

object PublishedPostsTable : Table("published_posts") {
    val postKey = text("post_key")
    val publishedAt = timestampWithTimeZone("published_at")
    override val primaryKey = PrimaryKey(postKey)
}

object CtaClicksTable : Table("cta_clicks") {
    val clickId = long("click_id").autoIncrement()
    val postId = long("post_id").references(PostStatsTable.postId, onDelete = ReferenceOption.CASCADE).nullable()
    val clusterId = uuid(
        "cluster_id"
    ).references(NewsClustersTable.clusterId, onDelete = ReferenceOption.SET_NULL).nullable()
    val variant = text("variant")
    val redirectId = uuid("redirect_id")
    val clickedAt = timestampWithTimeZone("clicked_at")
    val userAgent = text("user_agent").nullable()
    override val primaryKey = PrimaryKey(clickId)
    init {
        index("idx_cta_clicks_post", false, postId, clickedAt)
    }
}

object BotStartsTable : Table("bot_starts") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").nullable()
    val payload = text("payload").nullable()
    val postId = long("post_id").references(PostStatsTable.postId, onDelete = ReferenceOption.SET_NULL).nullable()
    val clusterId = uuid(
        "cluster_id"
    ).references(NewsClustersTable.clusterId, onDelete = ReferenceOption.SET_NULL).nullable()
    val abVariant = text("ab_variant").nullable()
    val startedAt = timestampWithTimeZone("started_at")
    override val primaryKey = PrimaryKey(id)
    init {
        index("idx_bot_starts_payload", false, payload)
    }
}
