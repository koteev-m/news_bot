package billing

import java.time.Instant
import kotlin.math.min

interface UsageRepo {
    suspend fun record(e: UsageEvent): Long

    suspend fun aggregateByMetric(
        tenantId: Long,
        from: Instant,
        to: Instant,
    ): Map<String, Double>

    suspend fun findRateCardForTenant(
        tenantId: Long,
        at: Instant,
    ): RateCard

    suspend fun persistInvoice(draft: InvoiceDraft): Long
}

class UsageService(
    private val repo: UsageRepo,
) {
    suspend fun recordEvent(e: UsageEvent): Long = repo.record(e)

    suspend fun draftInvoice(
        tenantId: Long,
        from: Instant,
        to: Instant,
        taxRate: Double = 0.0,
    ): InvoiceDraft {
        val totals = repo.aggregateByMetric(tenantId, from, to) // metric -> quantity
        val rate = repo.findRateCardForTenant(tenantId, to)
        val lines = mutableListOf<InvoiceLine>()

        totals.forEach { (metric, qty) ->
            val items = rate.items.filter { it.metric == metric }.sortedBy { it.tierFrom }
            var remaining = qty
            items.forEach { ri ->
                if (remaining <= 0) return@forEach
                val upper = ri.tierTo ?: Double.POSITIVE_INFINITY
                val chargeQty = min(remaining, upper - ri.tierFrom).coerceAtLeast(0.0)
                if (chargeQty > 0.0) {
                    lines += InvoiceLine(metric, chargeQty, ri.unit, ri.pricePerUnit, chargeQty * ri.pricePerUnit)
                    remaining -= chargeQty
                }
            }
            if (items.isEmpty()) { // flat rate fallback
                val unit = "unit"
                val price = 0.0
                lines += InvoiceLine(metric, qty, unit, price, qty * price)
            }
        }
        val subtotal = lines.sumOf { it.amount }
        val tax = subtotal * taxRate
        val total = subtotal + tax
        return InvoiceDraft(tenantId, rate.currency, from, to, lines, subtotal, tax, total)
    }

    suspend fun issueInvoice(draft: InvoiceDraft): Long = repo.persistInvoice(draft)
}
