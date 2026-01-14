package repo

import audit.AuditActorType
import audit.AuditCheckpoint
import audit.AuditEvent
import audit.AuditLedgerPort
import audit.HashUtil
import audit.LedgerRecord
import db.DatabaseFactory.dbQuery
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import common.runCatchingNonFatal

private const val GENESIS_HASH = "GENESIS"

object AuditLedgerTable : Table("audit_ledger") {
    val seqId = long("seq_id").autoIncrement()
    val ts = timestampWithTimeZone("ts")
    val actorType = text("actor_type")
    val actorId = text("actor_id").nullable()
    val tenantId = long("tenant_id").nullable()
    val action = text("action")
    val resource = text("resource")
    val metaJson = jsonb("meta_json", Json, JsonObject.serializer())
    val prevHash = text("prev_hash")
    val hash = text("hash")

    override val primaryKey = PrimaryKey(seqId)

    init {
        uniqueIndex("uq_audit_hash", hash)
        index("idx_audit_ts", false, ts)
        index("idx_audit_tenant", false, tenantId, ts)
    }
}

object AuditCheckpointsTable : Table("audit_checkpoints") {
    val day = date("day")
    val lastSeqId = long("last_seq_id")
    val rootHash = text("root_hash")
    val signature = text("signature")
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(day)
}

class AuditLedgerRepository : AuditLedgerPort {
    override suspend fun appendEvent(event: AuditEvent): LedgerRecord = dbQuery {
        val prev = findLastHash() ?: GENESIS_HASH
        computeAndInsert(event, prev)
    }

    suspend fun getLastHash(): String? = dbQuery { findLastHash() }

    suspend fun getLastRecord(): LedgerRecord? = dbQuery {
        AuditLedgerTable
            .selectAll()
            .orderBy(AuditLedgerTable.seqId, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.toLedgerRecord()
    }

    suspend fun beginCheckpoint(day: LocalDate): AuditCheckpoint? = dbQuery {
        AuditCheckpointsTable
            .selectAll()
            .where { AuditCheckpointsTable.day eq day }
            .singleOrNull()
            ?.toCheckpoint()
    }

    suspend fun finalizeCheckpoint(day: LocalDate, lastSeq: Long, rootHash: String, signature: String): AuditCheckpoint = dbQuery {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val updated = AuditCheckpointsTable.update({ AuditCheckpointsTable.day eq day }) {
            it[AuditCheckpointsTable.lastSeqId] = lastSeq
            it[AuditCheckpointsTable.rootHash] = rootHash
            it[AuditCheckpointsTable.signature] = signature
            it[AuditCheckpointsTable.createdAt] = now
        }
        if (updated == 0) {
            AuditCheckpointsTable.insert {
                it[AuditCheckpointsTable.day] = day
                it[AuditCheckpointsTable.lastSeqId] = lastSeq
                it[AuditCheckpointsTable.rootHash] = rootHash
                it[AuditCheckpointsTable.signature] = signature
                it[AuditCheckpointsTable.createdAt] = now
            }
        }
        AuditCheckpointsTable
            .selectAll()
            .where { AuditCheckpointsTable.day eq day }
            .single()
            .toCheckpoint()
    }

    suspend fun getCheckpoint(day: LocalDate): AuditCheckpoint? = dbQuery {
        AuditCheckpointsTable
            .selectAll()
            .where { AuditCheckpointsTable.day eq day }
            .singleOrNull()
            ?.toCheckpoint()
    }

    private fun findLastHash(): String? =
        AuditLedgerTable
            .slice(AuditLedgerTable.hash)
            .selectAll()
            .orderBy(AuditLedgerTable.seqId, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(AuditLedgerTable.hash)

    private fun computeAndInsert(event: AuditEvent, prevHashValue: String): LedgerRecord {
        val timestamp = event.occurredAt.atOffset(ZoneOffset.UTC)
        val insertStatement = AuditLedgerTable.insert {
            it[ts] = timestamp
            it[actorType] = event.actorType.name.lowercase()
            it[actorId] = event.actorId
            it[tenantId] = event.tenantId
            it[action] = event.action
            it[resource] = event.resource
            it[metaJson] = event.meta
            it[prevHash] = prevHashValue
            it[hash] = prevHashValue
        }
        val row = insertStatement.resultedValues?.singleOrNull()
            ?: error("Failed to insert audit ledger entry")
        val seq = row[AuditLedgerTable.seqId]
        val canonicalMeta = HashUtil.canonicalMeta(event.meta)
        val payloadHash = HashUtil.sha256Hex(
            seq.toString(),
            timestamp.toInstant().toString(),
            event.actorType.name.lowercase(),
            event.actorId.orEmpty(),
            event.tenantId?.toString().orEmpty(),
            event.action,
            event.resource,
            canonicalMeta,
            prevHashValue
        )
        AuditLedgerTable.update({ AuditLedgerTable.seqId eq seq }) {
            it[hash] = payloadHash
        }
        return LedgerRecord(
            seqId = seq,
            occurredAt = timestamp.toInstant(),
            actorType = event.actorType,
            actorId = event.actorId,
            tenantId = event.tenantId,
            action = event.action,
            resource = event.resource,
            meta = event.meta,
            prevHash = prevHashValue,
            hash = payloadHash
        )
    }

    private fun ResultRow.toLedgerRecord(): LedgerRecord {
        val seq = this[AuditLedgerTable.seqId]
        val timestamp = this[AuditLedgerTable.ts].toInstant()
        val actorTypeValue = this[AuditLedgerTable.actorType]
        val actorType = runCatchingNonFatal { AuditActorType.valueOf(actorTypeValue.uppercase()) }
            .getOrDefault(AuditActorType.SYSTEM)
        val meta = this[AuditLedgerTable.metaJson]
        return LedgerRecord(
            seqId = seq,
            occurredAt = timestamp,
            actorType = actorType,
            actorId = this[AuditLedgerTable.actorId],
            tenantId = this[AuditLedgerTable.tenantId],
            action = this[AuditLedgerTable.action],
            resource = this[AuditLedgerTable.resource],
            meta = meta,
            prevHash = this[AuditLedgerTable.prevHash],
            hash = this[AuditLedgerTable.hash]
        )
    }

    private fun ResultRow.toCheckpoint(): AuditCheckpoint = AuditCheckpoint(
        day = this[AuditCheckpointsTable.day],
        lastSeqId = this[AuditCheckpointsTable.lastSeqId],
        rootHash = this[AuditCheckpointsTable.rootHash],
        signature = this[AuditCheckpointsTable.signature],
        createdAt = this[AuditCheckpointsTable.createdAt].toInstant()
    )
}
