package repo

import analytics.AnalyticsPort
import db.DatabaseFactory.dbQuery
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.json.jsonb

object EventsTable : Table("events") {
    val eventId = long("event_id").autoIncrement()
    val ts = timestampWithTimeZone("ts")
    val userId = long("user_id").nullable()
    val type = text("type")
    val eventSource = text("source").nullable()
    val sessionId = text("session_id").nullable()
    val props = jsonb<JsonObject>("props", Json, JsonObject.serializer())
    override val primaryKey = PrimaryKey(eventId)
}

class AnalyticsRepository : AnalyticsPort {
    private fun mapToJson(props: Map<String, Any?>): JsonObject {
        return buildJsonObject {
            props.forEach { (key, value) ->
                when (value) {
                    null -> put(key, JsonNull)
                    is Number -> put(key, JsonPrimitive(value))
                    is Boolean -> put(key, JsonPrimitive(value))
                    is String -> put(key, JsonPrimitive(value))
                    is Instant -> put(key, JsonPrimitive(value.toString()))
                    is Enum<*> -> put(key, JsonPrimitive(value.name))
                    else -> put(key, JsonPrimitive(value.toString()))
                }
            }
        }
    }

    override suspend fun track(
        type: String,
        userId: Long?,
        source: String?,
        sessionId: String?,
        props: Map<String, Any?>,
        ts: Instant
    ) {
        val jsonProps = mapToJson(props)
        dbQuery {
            EventsTable.insert {
                it[EventsTable.ts] = ts.atOffset(ZoneOffset.UTC)
                it[EventsTable.userId] = userId
                it[EventsTable.type] = type
                it[EventsTable.eventSource] = source
                it[EventsTable.sessionId] = sessionId
                it[EventsTable.props] = jsonProps
            }
        }
    }
}
