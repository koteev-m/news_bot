package db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object ModerationQueueTable : Table("moderation_queue") {
    val moderationId = uuid("moderation_id")
    val clusterId = uuid("cluster_id")
    val clusterKey = text("cluster_key")
    val suggestedMode = text("suggested_mode")
    val score = decimal("score", 10, 4).default(0.0.toBigDecimal())
    val confidence = decimal("confidence", 6, 4).default(0.0.toBigDecimal())
    val links = text("links")
    val sourceDomain = text("source_domain")
    val entityHashes = text("entity_hashes")
    val primaryEntityHash = text("primary_entity_hash").nullable()
    val title = text("title")
    val summary = text("summary").nullable()
    val topics = text("topics").default("{}")
    val deepLink = text("deep_link")
    val createdAt = timestampWithTimeZone("created_at")
    val status = text("status")
    val actionId = text("action_id").nullable()
    val actionAt = timestampWithTimeZone("action_at").nullable()
    val editRequestedAt = timestampWithTimeZone("edit_requested_at").nullable()
    val adminChatId = long("admin_chat_id").nullable()
    val adminThreadId = long("admin_thread_id").nullable()
    val adminMessageId = long("admin_message_id").nullable()
    val publishedChannelId = long("published_channel_id").nullable()
    val publishedMessageId = long("published_message_id").nullable()
    val publishedAt = timestampWithTimeZone("published_at").nullable()
    val editedText = text("edited_text").nullable()
    override val primaryKey = PrimaryKey(moderationId)
    init {
        uniqueIndex("uk_moderation_queue_cluster", clusterKey)
        uniqueIndex("uk_moderation_queue_action", actionId)
        index("idx_moderation_queue_status", false, status)
        index("idx_moderation_queue_created_at", false, createdAt)
    }
}

object MuteSourceTable : Table("mute_source") {
    val sourceDomain = text("source_domain")
    val mutedUntil = timestampWithTimeZone("muted_until")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(sourceDomain)
}

object MuteEntityTable : Table("mute_entity") {
    val entityHash = text("entity_hash")
    val mutedUntil = timestampWithTimeZone("muted_until")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(entityHash)
}
