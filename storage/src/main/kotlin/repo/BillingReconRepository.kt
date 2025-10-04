package repo

import billing.recon.BillingLedgerPort
import billing.recon.BillingReconPort
import billing.recon.LedgerEntry
import db.DatabaseFactory.dbQuery
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.update

object BillingLedgerTable : Table("billing_ledger") {
    val ledgerId = long("ledger_id").autoIncrement()
    val userId = long("user_id")
    val tier = text("tier")
    val event = text("event")
    val providerPaymentId = text("provider_payment_id")
    val payloadHash = text("payload_hash")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(ledgerId)
}

object BillingReconRuns : Table("billing_recon_runs") {
    val runId = long("run_id").autoIncrement()
    val startedAt = timestampWithTimeZone("started_at")
    val finishedAt = timestampWithTimeZone("finished_at").nullable()
    val status = text("status")
    val notes = text("notes").nullable()
    override val primaryKey = PrimaryKey(runId)
}

object BillingReconMismatches : Table("billing_recon_mismatches") {
    val runId = long("run_id").references(BillingReconRuns.runId, onDelete = ReferenceOption.CASCADE)
    val kind = text("kind")
    val userId = long("user_id").nullable()
    val providerPaymentId = text("provider_payment_id").nullable()
    val tier = text("tier").nullable()
    val createdAt = timestampWithTimeZone("created_at")
}

class BillingLedgerRepository : BillingLedgerPort {
    override suspend fun append(entry: LedgerEntry) = dbQuery {
        BillingLedgerTable.insert {
            it[userId] = entry.userId
            it[tier] = entry.tier
            it[event] = entry.event
            it[providerPaymentId] = entry.providerPaymentId
            it[payloadHash] = entry.payloadHash
        }
        Unit
    }
}

class BillingReconRepository(
    private val clock: Clock = Clock.systemUTC()
) : BillingReconPort {
    override suspend fun beginRun(): Long = dbQuery {
        BillingReconRuns.insert {
            it[status] = "OK"
        } get BillingReconRuns.runId
    }

    override suspend fun recordMismatch(runId: Long, kind: String, userId: Long?, providerPaymentId: String?, tier: String?) = dbQuery {
        BillingReconMismatches.insert {
            it[BillingReconMismatches.runId] = runId
            it[BillingReconMismatches.kind] = kind
            it[BillingReconMismatches.userId] = userId
            it[BillingReconMismatches.providerPaymentId] = providerPaymentId
            it[BillingReconMismatches.tier] = tier
        }
        Unit
    }

    override suspend fun finishRun(runId: Long, status: String, notes: String?) = dbQuery {
        BillingReconRuns.update({ BillingReconRuns.runId eq runId }) {
            it[BillingReconRuns.status] = status
            it[BillingReconRuns.finishedAt] = Instant.now(clock).atOffset(ZoneOffset.UTC)
            it[BillingReconRuns.notes] = notes
        }
        Unit
    }
}
