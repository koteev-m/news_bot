package repo

import db.DatabaseFactory.dbQuery
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.update

object PrivacyErasureQueue : Table("privacy_erasure_queue") {
    val userId = long("user_id")
    val requestedAt = timestampWithTimeZone("requested_at")
    val status = text("status")
    val lastError = text("last_error").nullable()
    val processedAt = timestampWithTimeZone("processed_at").nullable()
    override val primaryKey = PrimaryKey(userId)
}

object PrivacyErasureLog : Table("privacy_erasure_log") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val action = text("action")
    val tableNameColumn = text("table_name")
    val affected = long("affected")
    val ts = timestampWithTimeZone("ts")
    override val primaryKey = PrimaryKey(id)
}

class PrivacyRepository(private val clock: Clock = Clock.systemUTC()) {
    suspend fun upsertRequest(userId: Long) = dbQuery {
        PrivacyErasureQueue.insertIgnore {
            it[PrivacyErasureQueue.userId] = userId
            it[status] = "PENDING"
            it[lastError] = null
            it[processedAt] = null
            it[requestedAt] = Instant.now(clock).atOffset(ZoneOffset.UTC)
        }
    }

    suspend fun markResult(userId: Long, statusValue: String, error: String?) = dbQuery {
        PrivacyErasureQueue.update({ PrivacyErasureQueue.userId eq userId }) {
            it[status] = statusValue
            it[lastError] = error
            it[processedAt] = Instant.now(clock).atOffset(ZoneOffset.UTC)
        }
    }

    suspend fun audit(userId: Long, table: String, actionValue: String, affectedCount: Long) = dbQuery {
        PrivacyErasureLog.insert {
            it[PrivacyErasureLog.userId] = userId
            it[action] = actionValue
            it[tableNameColumn] = table
            it[affected] = affectedCount
            it[ts] = Instant.now(clock).atOffset(ZoneOffset.UTC)
        }
    }
}
