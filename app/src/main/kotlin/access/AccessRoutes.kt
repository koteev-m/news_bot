package access

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import tenancy.TenantContextKey

data class StartReviewReq(
    val dueAtIso: String,
)

data class DecideReq(
    val itemId: Long,
    val keep: Boolean,
)

data class PamReq(
    val reason: String,
    val roles: List<String>,
    val ttlMinutes: Long,
)

data class ApprovePamReq(
    val sessionId: Long,
    val approverId: Long,
)

data class RevokePamReq(
    val sessionId: Long,
    val by: Long?,
)

fun Route.accessRoutes(
    reviewSvc: AccessReviewService,
    sodSvc: SodService,
    pamSvc: PamService,
) {
    route("/api/access") {
        post("/review/start") {
            val ctx = call.attributes[TenantContextKey]
            val req = call.receive<StartReviewReq>()
            val id = reviewSvc.startReview(ctx, java.time.Instant.parse(req.dueAtIso))
            reviewSvc.populateReview(id, ctx.tenant.tenantId)
            call.respond(HttpStatusCode.Created, mapOf("reviewId" to id))
        }
        post("/review/decide") {
            val req = call.receive<DecideReq>()
            reviewSvc.decide(req.itemId, req.keep)
            call.respond(HttpStatusCode.NoContent)
        }
        get("/sod/check") {
            val ctx = call.attributes[TenantContextKey]
            val ok = sodSvc.checkSod(ctx.tenant.tenantId, emptySet())
            call.respond(mapOf("valid" to ok))
        }
        post("/pam/request") {
            val ctx = call.attributes[TenantContextKey]
            val req = call.receive<PamReq>()
            val id = pamSvc.request(ctx, req.reason, req.roles, req.ttlMinutes)
            call.respond(HttpStatusCode.Accepted, mapOf("sessionId" to id))
        }
        post("/pam/approve") {
            val req = call.receive<ApprovePamReq>()
            pamSvc.approve(req.sessionId, req.approverId)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/pam/revoke") {
            val req = call.receive<RevokePamReq>()
            pamSvc.revoke(req.sessionId, req.by)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
