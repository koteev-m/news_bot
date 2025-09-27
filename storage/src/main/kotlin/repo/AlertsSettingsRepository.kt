package repo

import alerts.settings.AlertsSettingsRepository as CoreAlertsSettingsRepository
import db.DatabaseFactory.dbQuery
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update

interface AlertsSettingsRepository : CoreAlertsSettingsRepository

class AlertsSettingsRepositoryImpl(
    private val json: Json = Json { ignoreUnknownKeys = true }
) : AlertsSettingsRepository {
    override suspend fun upsert(userId: Long, json: String) {
        val element = parsePayload(json)
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        dbQuery {
            val updated = UserAlertOverridesTable.update({ UserAlertOverridesTable.userId eq userId }) {
                it[UserAlertOverridesTable.payload] = element
                it[UserAlertOverridesTable.updatedAt] = now
            }
            if (updated == 0) {
                UserAlertOverridesTable.insert {
                    it[UserAlertOverridesTable.userId] = userId
                    it[UserAlertOverridesTable.payload] = element
                    it[UserAlertOverridesTable.updatedAt] = now
                }
            }
        }
    }

    override suspend fun find(userId: Long): String? = dbQuery {
        UserAlertOverridesTable
            .select { UserAlertOverridesTable.userId eq userId }
            .firstOrNull()
            ?.get(UserAlertOverridesTable.payload)
            ?.let { payload -> json.encodeToString(JsonElement.serializer(), payload) }
    }

    private fun parsePayload(raw: String): JsonElement {
        val element = json.parseToJsonElement(raw)
        require(element is JsonObject) { "payload must be a JSON object" }
        return element
    }
}
