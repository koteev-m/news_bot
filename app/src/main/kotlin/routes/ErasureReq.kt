package routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import privacy.ErasureReport
import privacy.PrivacyService
import privacy.RetentionReport
import security.userIdOrNull

@Serializable
data class ErasureReq(val userId: Long, val dryRun: Boolean = false)

fun Route.adminPrivacyRoutes(service: PrivacyService, adminUserIds: Set<Long>) {
    route("/api/admin/privacy") {
        post("/erase") {
            val subject = call.request.headers["X-User-Id"]?.toLongOrNull() ?: call.userIdOrNull?.toLongOrNull()
            if (subject == null || subject !in adminUserIds) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                return@post
            }
            val req = call.receive<ErasureReq>()
            val report: ErasureReport = service.runErasure(req.userId, req.dryRun)
            call.respond(HttpStatusCode.OK, report)
        }
        post("/retention/run") {
            val subject = call.request.headers["X-User-Id"]?.toLongOrNull() ?: call.userIdOrNull?.toLongOrNull()
            if (subject == null || subject !in adminUserIds) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                return@post
            }
            val report: RetentionReport = service.runRetention()
            call.respond(HttpStatusCode.OK, report)
        }
    }
}
