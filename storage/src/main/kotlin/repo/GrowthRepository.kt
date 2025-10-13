package repo

import growth.DeliveryRepo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import db.DatabaseFactory.dbQuery
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Таблицы growth_* (часть из них используются репозиторием доставок).
 */
object GrowthDeliveries : LongIdTable(name = "growth_deliveries", columnName = "delivery_id") {
    val campaignId: Column<Long?> = long("campaign_id").nullable()
    val journeyId: Column<Long?> = long("journey_id").nullable()
    val userId: Column<Long> = long("user_id")
    val tenantId: Column<Long> = long("tenant_id")
    val channel: Column<String> = text("channel")
    val status: Column<String> = text("status")
    val reason: Column<String?> = text("reason").nullable()
    val payload = jsonb("payload_json", Json.Default, JsonObject.serializer()).nullable()
    val createdAt: Column<OffsetDateTime> = timestampWithTimeZone("created_at")
    val sentAt: Column<OffsetDateTime?> = timestampWithTimeZone("sent_at").nullable()
}

object GrowthSuppressions {
    object T : org.jetbrains.exposed.sql.Table("growth_suppressions") {
        val userId: Column<Long> = long("user_id")
        val tenantId: Column<Long> = long("tenant_id")
        val channel: Column<String> = text("channel")
        val reason: Column<String?> = text("reason").nullable()
        val optedOutAt: Column<OffsetDateTime> = timestampWithTimeZone("opted_out_at")
        override val primaryKey = PrimaryKey(userId, tenantId, channel)
    }
}

class GrowthRepository : DeliveryRepo {

    private fun startOfUtcDay(): OffsetDateTime =
        OffsetDateTime.now(ZoneOffset.UTC).toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC)

    override suspend fun frequencyCountToday(userId: Long, tenantId: Long, channel: String): Int = dbQuery {
        val sod = startOfUtcDay()
        GrowthDeliveries
            .select {
                (GrowthDeliveries.userId eq userId) and
                    (GrowthDeliveries.tenantId eq tenantId) and
                    (GrowthDeliveries.channel eq channel) and
                    (GrowthDeliveries.createdAt greaterEq sod)
            }
            .count()
            .toInt()
    }

    override suspend fun isSuppressed(userId: Long, tenantId: Long, channel: String): Boolean = dbQuery {
        GrowthSuppressions.T
            .select {
                (GrowthSuppressions.T.userId eq userId) and
                    (GrowthSuppressions.T.tenantId eq tenantId) and
                    (GrowthSuppressions.T.channel eq channel)
            }
            .empty()
            .not()
    }

    override suspend fun enqueue(
        campaignId: Long?,
        journeyId: Long?,
        userId: Long,
        tenantId: Long,
        channel: String,
        payload: JsonObject?
    ): Long = dbQuery {
        val id: EntityID<Long> = GrowthDeliveries.insert {
            it[GrowthDeliveries.campaignId] = campaignId
            it[GrowthDeliveries.journeyId] = journeyId
            it[GrowthDeliveries.userId] = userId
            it[GrowthDeliveries.tenantId] = tenantId
            it[GrowthDeliveries.channel] = channel
            it[GrowthDeliveries.status] = "QUEUED"
            it[GrowthDeliveries.payload] = payload
            it[GrowthDeliveries.createdAt] = OffsetDateTime.now(ZoneOffset.UTC)
        } get GrowthDeliveries.id
        id.value
    }

    override suspend fun markSent(deliveryId: Long) {
        dbQuery {
            GrowthDeliveries.update({ GrowthDeliveries.id eq deliveryId }) {
                it[status] = "SENT"
                it[sentAt] = OffsetDateTime.now(ZoneOffset.UTC)
            }
        }
    }

    override suspend fun markFailed(deliveryId: Long, reason: String) {
        dbQuery {
            GrowthDeliveries.update({ GrowthDeliveries.id eq deliveryId }) {
                it[status] = "FAILED"
                it[GrowthDeliveries.reason] = reason
            }
        }
    }
}
