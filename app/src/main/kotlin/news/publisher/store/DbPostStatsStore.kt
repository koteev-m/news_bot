package news.publisher.store

import db.DatabaseFactory
import db.tables.PostStatsTable
import java.sql.SQLException
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

class DbPostStatsStore(
    private val clock: Clock = Clock.systemUTC(),
) : PostStatsStore {
    private val logger = LoggerFactory.getLogger(DbPostStatsStore::class.java)

    override suspend fun findByCluster(channelId: Long, clusterId: UUID): PublishedPost? {
        return DatabaseFactory.dbQuery {
            PostStatsTable.select {
                (PostStatsTable.channelId eq channelId) and (PostStatsTable.clusterId eq clusterId)
            }
                .limit(1)
                .map {
                    PublishedPost(
                        messageId = it[PostStatsTable.messageId],
                        contentHash = it[PostStatsTable.contentHash],
                        duplicateCount = it[PostStatsTable.duplicateCount],
                    )
                }
                .singleOrNull()
        }
    }

    override suspend fun recordNew(channelId: Long, clusterId: UUID, messageId: Long, contentHash: String) {
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        try {
            DatabaseFactory.dbQuery {
                PostStatsTable.insert {
                    it[PostStatsTable.channelId] = channelId
                    it[PostStatsTable.messageId] = messageId
                    it[PostStatsTable.clusterId] = clusterId
                    it[PostStatsTable.contentHash] = contentHash
                    it[PostStatsTable.postedAt] = now
                    it[PostStatsTable.updatedAt] = now
                    it[PostStatsTable.duplicateCount] = 0
                }
            }
            return
        } catch (ex: ExposedSQLException) {
            if (!isUniqueViolation(ex)) {
                throw ex
            }
            logger.warn(
                "Unique violation while recording post stats for channel {} cluster {}",
                channelId,
                clusterId,
                ex
            )
        }
        val updated = DatabaseFactory.dbQuery {
            PostStatsTable.update(
                where = {
                    (PostStatsTable.channelId eq channelId) and (PostStatsTable.clusterId eq clusterId)
                },
            ) { update ->
                update[PostStatsTable.messageId] = messageId
                update[PostStatsTable.contentHash] = contentHash
                update[PostStatsTable.updatedAt] = now
            }
        }
        if (updated == 0) {
            logger.error(
                "Unique violation without matching post stats row for channel {} cluster {}",
                channelId,
                clusterId
            )
        }
    }

    override suspend fun recordEdit(channelId: Long, clusterId: UUID, contentHash: String) {
        updateExisting(channelId, clusterId, contentHash)
    }

    override suspend fun recordDuplicate(channelId: Long, clusterId: UUID) {
        updateExisting(channelId, clusterId, null)
    }

    private suspend fun updateExisting(channelId: Long, clusterId: UUID, contentHash: String?) {
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        DatabaseFactory.dbQuery {
            PostStatsTable.update(
                where = {
                    (PostStatsTable.channelId eq channelId) and (PostStatsTable.clusterId eq clusterId)
                },
            ) { update ->
                update[PostStatsTable.updatedAt] = now
                update[PostStatsTable.duplicateCount] = with(SqlExpressionBuilder) {
                    PostStatsTable.duplicateCount + 1
                }
                if (contentHash != null) {
                    update[PostStatsTable.contentHash] = contentHash
                }
            }
        }
    }

    private fun isUniqueViolation(ex: ExposedSQLException): Boolean {
        val sqlState = ex.sqlState ?: (ex.cause as? SQLException)?.sqlState
        return sqlState == "23505"
    }
}
