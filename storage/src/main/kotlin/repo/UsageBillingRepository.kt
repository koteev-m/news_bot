package repo

import billing.*
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.between
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import storage.db.DatabaseFactory.dbQuery
import java.time.Instant

object UsageEvents : LongIdTable("usage_events", "event_id") {
    val tenantId = long("tenant_id")
    val projectId = long("project_id").nullable()
    val userId = long("user_id").nullable()
    val metric = text("metric")
    val quantity = decimal("quantity", 20, 6)
    val occurredAt = timestampWithTimeZone("occurred_at")
    val dedupKey = text("dedup_key").nullable()
}

object RateCards : LongIdTable("rate_cards", "rate_id") {
    val name = text("name")
    val currency = text("currency")
    val createdAt = timestampWithTimeZone("created_at")
}

object RateItems : LongIdTable("rate_items", "item_id") {
    val rateId = long("rate_id").references(RateCards.id)
    val metric = text("metric")
    val unit = text("unit")
    val pricePerUnit = decimal("price_per_unit", 20, 6)
    val tierFrom = decimal("tier_from", 20, 6)
    val tierTo = decimal("tier_to", 20, 6).nullable()
}

object TenantPricing : Table("tenant_pricing") {
    val tenantId = long("tenant_id")
    val rateId = long("rate_id").references(RateCards.id)
    val effectiveFrom = timestampWithTimeZone("effective_from")
    override val primaryKey = PrimaryKey(tenantId)
}

object InvoiceRuns : LongIdTable("invoice_runs", "run_id") {
    val periodFrom = timestampWithTimeZone("period_from")
    val periodTo = timestampWithTimeZone("period_to")
    val createdAt = timestampWithTimeZone("created_at")
}

object Invoices : LongIdTable("invoices", "invoice_id") {
    val runId = long("run_id").references(InvoiceRuns.id)
    val tenantId = long("tenant_id")
    val currency = text("currency")
    val subtotal = decimal("subtotal", 20, 6)
    val tax = decimal("tax", 20, 6)
    val total = decimal("total", 20, 6)
    val status = text("status")
    val createdAt = timestampWithTimeZone("created_at")
    val paidAt = timestampWithTimeZone("paid_at").nullable()
}

object InvoiceLines : LongIdTable("invoice_lines", "line_id") {
    val invoiceId = long("invoice_id").references(Invoices.id)
    val metric = text("metric")
    val quantity = decimal("quantity", 20, 6)
    val unit = text("unit")
    val unitPrice = decimal("unit_price", 20, 6)
    val amount = decimal("amount", 20, 6)
}

class UsageBillingRepository : UsageRepo {

    override suspend fun record(e: UsageEvent): Long = dbQuery {
        if (e.dedupKey != null) {
            // idempotency — простая проверка
            val exists = UsageEvents.select { UsageEvents.dedupKey eq e.dedupKey }.empty().not()
            if (exists) return@dbQuery -1L
        }
        UsageEvents.insertAndGetId {
            it[tenantId] = e.tenantId
            it[projectId] = e.projectId
            it[userId] = e.userId
            it[metric] = e.metric
            it[quantity] = e.quantity.toBigDecimal()
            it[occurredAt] = e.occurredAt
            it[dedupKey] = e.dedupKey
        }.value
    }

    override suspend fun aggregateByMetric(tenantId: Long, from: Instant, to: Instant): Map<String, Double> = dbQuery {
        UsageEvents
            .slice(UsageEvents.metric, UsageEvents.quantity.sum())
            .select { (UsageEvents.tenantId eq tenantId) and (UsageEvents.occurredAt between from and to) }
            .groupBy(UsageEvents.metric)
            .associate { it[UsageEvents.metric] to (it[UsageEvents.quantity.sum()]?.toDouble() ?: 0.0) }
    }

    override suspend fun findRateCardForTenant(tenantId: Long, at: Instant): RateCard = dbQuery {
        val row = TenantPricing
            .select { TenantPricing.tenantId eq tenantId }
            .orderBy(TenantPricing.effectiveFrom, SortOrder.DESC)
            .limit(1)
            .first()
        val rateId = row[TenantPricing.rateId]
        val rc = RateCards.select { RateCards.id eq rateId }.first()
        val items = RateItems
            .select { RateItems.rateId eq rateId }
            .map {
                RateItem(
                    metric = it[RateItems.metric],
                    unit = it[RateItems.unit],
                    pricePerUnit = it[RateItems.pricePerUnit].toDouble(),
                    tierFrom = it[RateItems.tierFrom].toDouble(),
                    tierTo = it[RateItems.tierTo]?.toDouble()
                )
            }
        RateCard(rateId, rc[RateCards.name], rc[RateCards.currency], items)
    }

    override suspend fun persistInvoice(draft: InvoiceDraft): Long = dbQuery {
        val runId = InvoiceRuns.insertAndGetId {
            it[periodFrom] = draft.periodFrom
            it[periodTo] = draft.periodTo
            it[createdAt] = Instant.now()
        }.value
        val invId = Invoices.insertAndGetId {
            it[Invoices.runId] = runId
            it[tenantId] = draft.tenantId
            it[currency] = draft.currency
            it[subtotal] = draft.subtotal.toBigDecimal()
            it[tax] = draft.tax.toBigDecimal()
            it[total] = draft.total.toBigDecimal()
            it[status] = "DRAFT"
            it[createdAt] = Instant.now()
        }.value
        draft.lines.forEach { ln ->
            InvoiceLines.insert {
                it[invoiceId] = invId
                it[metric] = ln.metric
                it[quantity] = ln.quantity.toBigDecimal()
                it[unit] = ln.unit
                it[unitPrice] = ln.unitPrice.toBigDecimal()
                it[amount] = ln.amount.toBigDecimal()
            }
        }
        invId
    }
}
