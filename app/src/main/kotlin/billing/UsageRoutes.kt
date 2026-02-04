package billing

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.http.*
import io.ktor.server.routing.*
import tenancy.TenantContextKey
import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable data class UsageIngestReq(val metric: String, val quantity: Double, val occurredAt: String? = null, val dedupKey: String? = null)

@Serializable data class InvoiceRequest(
    val tenantId: Long? = null,
    val from: String,
    val to: String,
    val taxRate: Double = 0.0
)

fun Route.usageRoutes(service: UsageService) {
    route("/api/usage") {
        post("/ingest") {
            val ctx = call.attributes[TenantContextKey]
            val req = call.receive<UsageIngestReq>()
            val id = service.recordEvent(
                UsageEvent(
                    tenantId = ctx.tenant.tenantId,
                    metric = req.metric,
                    quantity = req.quantity,
                    occurredAt = req.occurredAt?.let { Instant.parse(it) } ?: Instant.now(),
                    dedupKey = req.dedupKey
                )
            )
            call.respond(HttpStatusCode.Accepted, mapOf("eventId" to id))
        }
    }
}

fun Route.invoiceRoutes(service: UsageService) {
    route("/api/billing") {
        post("/invoice/draft") {
            val ctx = call.attributes[TenantContextKey]
            val req = call.receive<InvoiceRequest>()
            val tenantId = req.tenantId ?: ctx.tenant.tenantId
            val draft = service.draftInvoice(tenantId, Instant.parse(req.from), Instant.parse(req.to), req.taxRate)
            call.respond(HttpStatusCode.OK, draft)
        }
        post("/invoice/issue") {
            val ctx = call.attributes[TenantContextKey]
            val req = call.receive<InvoiceRequest>()
            val tenantId = req.tenantId ?: ctx.tenant.tenantId
            val draft = service.draftInvoice(tenantId, Instant.parse(req.from), Instant.parse(req.to), req.taxRate)
            val id = service.issueInvoice(draft)
            call.respond(HttpStatusCode.Created, mapOf("invoiceId" to id))
        }
    }
}
