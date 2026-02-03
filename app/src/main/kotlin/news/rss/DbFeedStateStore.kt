package news.rss

import db.DatabaseFactory
import db.tables.FeedStateTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.time.ZoneOffset

class DbFeedStateStore : FeedStateStore {
    override suspend fun get(sourceId: String): FeedState? {
        return DatabaseFactory.dbQuery {
            FeedStateTable.select { FeedStateTable.sourceId eq sourceId }
                .limit(1)
                .map { it.toFeedState() }
                .singleOrNull()
        }
    }

    override suspend fun upsert(state: FeedState) {
        DatabaseFactory.dbQuery {
            val updated = FeedStateTable.update({ FeedStateTable.sourceId eq state.sourceId }) { row ->
                row[FeedStateTable.etag] = state.etag
                row[FeedStateTable.lastModified] = state.lastModified
                row[FeedStateTable.lastFetchedAt] = state.lastFetchedAt.toOffsetDateTime()
                row[FeedStateTable.lastSuccessAt] = state.lastSuccessAt?.toOffsetDateTime()
                row[FeedStateTable.failureCount] = state.failureCount
                row[FeedStateTable.cooldownUntil] = state.cooldownUntil?.toOffsetDateTime()
            }
            if (updated == 0) {
                FeedStateTable.insert { row ->
                    row[FeedStateTable.sourceId] = state.sourceId
                    row[FeedStateTable.etag] = state.etag
                    row[FeedStateTable.lastModified] = state.lastModified
                    row[FeedStateTable.lastFetchedAt] = state.lastFetchedAt.toOffsetDateTime()
                    row[FeedStateTable.lastSuccessAt] = state.lastSuccessAt?.toOffsetDateTime()
                    row[FeedStateTable.failureCount] = state.failureCount
                    row[FeedStateTable.cooldownUntil] = state.cooldownUntil?.toOffsetDateTime()
                }
            }
        }
    }
}

private fun ResultRow.toFeedState(): FeedState {
    return FeedState(
        sourceId = this[FeedStateTable.sourceId],
        etag = this[FeedStateTable.etag],
        lastModified = this[FeedStateTable.lastModified],
        lastFetchedAt = this[FeedStateTable.lastFetchedAt].toInstant(),
        lastSuccessAt = this[FeedStateTable.lastSuccessAt]?.toInstant(),
        failureCount = this[FeedStateTable.failureCount],
        cooldownUntil = this[FeedStateTable.cooldownUntil]?.toInstant(),
    )
}

private fun java.time.Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
