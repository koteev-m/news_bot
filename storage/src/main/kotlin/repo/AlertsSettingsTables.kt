package repo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.json.jsonb

object UserAlertOverridesTable : Table("user_alert_overrides") {
    val userId = long("user_id")
    val payload = jsonb("payload", Json, JsonElement.serializer())
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(userId)
}
