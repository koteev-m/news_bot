package repo

import ab.Assignment
import ab.Experiment
import ab.ExperimentsPort
import db.DatabaseFactory.dbQuery
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

object ExperimentsTable : Table("experiments") {
    val key = text("key")
    val name = text("name")
    val enabled = bool("enabled")
    val traffic = jsonb("traffic", Json, JsonObject.serializer())
    val scope = text("scope")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(key)
}

object ExperimentAssignmentsTable : Table("experiment_assignments") {
    val userId = long("user_id")
    val key = text("key")
    val variant = text("variant")
    val assignedAt = timestampWithTimeZone("assigned_at")
    override val primaryKey = PrimaryKey(userId, key)
}

class ExperimentsRepository : ExperimentsPort {
    override suspend fun list(): List<Experiment> = dbQuery {
        ExperimentsTable.selectAll().map { row -> mapExperiment(row) }
    }

    override suspend fun upsert(e: Experiment) {
        val trafficJson = buildJsonObject {
            e.traffic.forEach { (variant, percent) ->
                put(variant, JsonPrimitive(percent))
            }
        }
        dbQuery {
            ExperimentsTable.insertIgnore { statement ->
                statement[key] = e.key
                statement[name] = e.key
                statement[enabled] = e.enabled
                statement[traffic] = trafficJson
                statement[scope] = "GLOBAL"
            }
            ExperimentsTable.update({ ExperimentsTable.key eq e.key }) { statement ->
                statement[enabled] = e.enabled
                statement[traffic] = trafficJson
                statement[updatedAt] = OffsetDateTime.now(ZoneOffset.UTC)
            }
        }
    }

    override suspend fun getAssignment(userId: Long, key: String): Assignment? = dbQuery {
        ExperimentAssignmentsTable
            .select { (ExperimentAssignmentsTable.userId eq userId) and (ExperimentAssignmentsTable.key eq key) }
            .limit(1)
            .firstOrNull()
            ?.let { row -> mapAssignment(row) }
    }

    override suspend fun saveAssignment(a: Assignment) {
        dbQuery {
            ExperimentAssignmentsTable.insertIgnore { statement ->
                statement[userId] = a.userId
                statement[key] = a.key
                statement[variant] = a.variant
                statement[assignedAt] = OffsetDateTime.now(ZoneOffset.UTC)
            }
        }
    }

    private fun mapExperiment(row: ResultRow): Experiment {
        val trafficObject = row[ExperimentsTable.traffic]
        val variants = trafficObject.mapValues { entry ->
            entry.value.jsonPrimitive.int
        }.toMap()
        return Experiment(
            key = row[ExperimentsTable.key],
            enabled = row[ExperimentsTable.enabled],
            traffic = variants
        )
    }

    private fun mapAssignment(row: ResultRow): Assignment {
        return Assignment(
            userId = row[ExperimentAssignmentsTable.userId],
            key = row[ExperimentAssignmentsTable.key],
            variant = row[ExperimentAssignmentsTable.variant]
        )
    }
}
