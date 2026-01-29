package news.publisher.store

import db.DatabaseFactory
import db.tables.PublishedPostsTable
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.select

class DbIdempotencyStore(
    private val clock: Clock = Clock.systemUTC(),
) : IdempotencyStore {
    override suspend fun seen(key: String): Boolean {
        return DatabaseFactory.dbQuery {
            PublishedPostsTable.select { PublishedPostsTable.postKey eq key }
                .limit(1)
                .any()
        }
    }

    override suspend fun mark(key: String) {
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        DatabaseFactory.dbQuery {
            PublishedPostsTable.insertIgnore {
                it[postKey] = key
                it[publishedAt] = now
            }
        }
    }
}
