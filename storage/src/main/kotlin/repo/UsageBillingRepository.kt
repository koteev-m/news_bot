package repo

import billing.*
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import db.DatabaseFactory.dbQuery
import java.time.Instant
import java.time.ZoneOffset

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
            it[UsageEvents.tenantId] = e.tenantId
            it[UsageEvents.projectId] = e.projectId
            it[UsageEvents.userId] = e.userId
            it[UsageEvents.metric] = e.metric
            it[UsageEvents.quantity] = e.quantity.toBigDecimal()
            it[UsageEvents.occurredAt] = e.occurredAt.atOffset(ZoneOffset.UTC)
            it[UsageEvents.dedupKey] = e.dedupKey
        }.value
    }

    override suspend fun aggregateByMetric(tenantId: Long, from: Instant, to: Instant): Map<String, Double> = dbQuery {
        val fromTs = from.atOffset(ZoneOffset.UTC)
        val toTs = to.atOffset(ZoneOffset.UTC)
        UsageEvents
            .slice(UsageEvents.metric, UsageEvents.quantity.sum())
            .select {
                with(SqlExpressionBuilder) {
                    (UsageEvents.tenantId eq tenantId) and (UsageEvents.occurredAt.between(fromTs, toTs))
                }
            }
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
            it[InvoiceRuns.periodFrom] = draft.periodFrom.atOffset(ZoneOffset.UTC)
            it[InvoiceRuns.periodTo] = draft.periodTo.atOffset(ZoneOffset.UTC)
            it[InvoiceRuns.createdAt] = Instant.now().atOffset(ZoneOffset.UTC)
        }.value
        val invId = Invoices.insertAndGetId {
            it[Invoices.runId] = runId
            it[Invoices.tenantId] = draft.tenantId
            it[Invoices.currency] = draft.currency
            it[Invoices.subtotal] = draft.subtotal.toBigDecimal()
            it[Invoices.tax] = draft.tax.toBigDecimal()
            it[Invoices.total] = draft.total.toBigDecimal()
            it[Invoices.status] = "DRAFT"
            it[Invoices.createdAt] = Instant.now().atOffset(ZoneOffset.UTC)
        }.value
        draft.lines.forEach { ln ->
            InvoiceLines.insert {
                it[InvoiceLines.invoiceId] = invId
                it[InvoiceLines.metric] = ln.metric
                it[InvoiceLines.quantity] = ln.quantity.toBigDecimal()
                it[InvoiceLines.unit] = ln.unit
                it[InvoiceLines.unitPrice] = ln.unitPrice.toBigDecimal()
                it[InvoiceLines.amount] = ln.amount.toBigDecimal()
            }
        }
        invId
    }
}
