package db.tables

import org.jetbrains.exposed.sql.CustomFunction
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import java.math.BigDecimal
import java.util.UUID

private fun genRandomUuid() = CustomFunction<UUID>("gen_random_uuid", org.jetbrains.exposed.sql.UUIDColumnType())

object NewsSourcesTable : Table("news_sources") {
    val sourceId = long("source_id").autoIncrement()
    val name = text("name")
    val type = text("type")
    val domain = text("domain").uniqueIndex()
    val rssUrl = text("rss_url").nullable()
    val weight = short("weight").default(10)
    val isPrimary = bool("is_primary").default(false)
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(sourceId)
}

object NewsItemsTable : Table("news_items") {
    val itemId = long("item_id").autoIncrement()
    val sourceId = long("source_id").references(NewsSourcesTable.sourceId, onDelete = ReferenceOption.CASCADE)
    val url = text("url").uniqueIndex()
    val title = text("title")
    val body = text("body").nullable()
    val publishedAt = timestampWithTimeZone("published_at")
    val language = char("language", 2)
    val topics = text("topics").default("{}")
    val tickers = text("tickers").default("{}")
    val hashFast = char("hash_fast", 32).nullable()
    val hashSimhash = long("hash_simhash").nullable()
    val shingleMinhash = binary("shingle_minhash").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(itemId)
    init {
        index("idx_news_items_pub", false, publishedAt)
        index("idx_news_items_hash", false, hashFast)
    }
}

object NewsClustersTable : Table("news_clusters") {
    val clusterId = uuid("cluster_id").defaultExpression(genRandomUuid())
    val canonicalItemId = long("canonical_item_id").references(NewsItemsTable.itemId, onDelete = ReferenceOption.SET_NULL).nullable()
    val canonicalUrl = text("canonical_url").uniqueIndex().nullable()
    val score = decimal("score", 10, 4).default(BigDecimal("0"))
    val firstSeen = timestampWithTimeZone("first_seen")
    val lastSeen = timestampWithTimeZone("last_seen")
    val topics = text("topics").default("{}")
    val tickers = text("tickers").default("{}")
    val size = integer("size").default(1)
    val clusterKey = text("cluster_key").uniqueIndex()
    override val primaryKey = PrimaryKey(clusterId)
    init {
        index("idx_news_clusters_last_seen", false, lastSeen)
    }
}

object NewsPipelineStateTable : Table("news_pipeline_state") {
    val key = text("key")
    val lastPublishedEpochSeconds = long("last_published_epoch_seconds").default(0)
    val leaseUntilEpochSeconds = long("lease_until_epoch_seconds").default(0)
    val leaseOwner = text("lease_owner").nullable()
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(key)
}
