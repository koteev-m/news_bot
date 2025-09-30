package repo

import db.DatabaseFactory.dbQuery
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.update

interface FeatureOverridesRepository : features.FeatureOverridesRepository

class FeatureOverridesRepositoryImpl(
    private val json: Json = Json { ignoreUnknownKeys = true }
) : FeatureOverridesRepository {
    override suspend fun upsertGlobal(json: String) {
        val element = parsePayload(json)
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        dbQuery {
            val updated = FeatureOverridesTable.update({ FeatureOverridesTable.key eq GLOBAL_KEY }) {
                it[FeatureOverridesTable.payload] = element
                it[FeatureOverridesTable.updatedAt] = now
            }
            if (updated == 0) {
                FeatureOverridesTable.insert {
                    it[FeatureOverridesTable.key] = GLOBAL_KEY
                    it[FeatureOverridesTable.payload] = element
                    it[FeatureOverridesTable.updatedAt] = now
                }
            }
        }
    }

    override suspend fun findGlobal(): String? = dbQuery {
        FeatureOverridesTable
            .select { FeatureOverridesTable.key eq GLOBAL_KEY }
            .firstOrNull()
            ?.get(FeatureOverridesTable.payload)
            ?.let { payload -> json.encodeToString(JsonElement.serializer(), payload) }
    }

    private fun parsePayload(raw: String): JsonElement {
        val element = json.parseToJsonElement(raw)
        require(element is JsonObject) { "payload must be a JSON object" }
        return element
    }

    private companion object {
        private const val GLOBAL_KEY = "global"
    }
}

object FeatureOverridesTable : Table("feature_overrides") {
    val key = text("key")
    val payload = jsonb("payload", Json, JsonElement.serializer())
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(key)
}
