package alerts

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

const val INTERNAL_TOKEN_HEADER = "X-Internal-Token"

fun Route.alertsRoutes(alertsService: AlertsService, internalToken: String?) {
    suspend fun io.ktor.server.application.ApplicationCall.ensureInternalAccess(): Boolean {
        if (internalToken.isNullOrBlank()) {
            respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "internal token not configured"))
            return false
        }
        val provided = request.headers[INTERNAL_TOKEN_HEADER]
        if (provided != internalToken) {
            respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
            return false
        }
        return true
    }

    post("/internal/alerts/snapshot") {
        if (!call.ensureInternalAccess()) return@post
        val snapshot = call.receive<MarketSnapshot>()
        call.respond(alertsService.onSnapshot(snapshot))
    }
    get("/internal/alerts/state") {
        if (!call.ensureInternalAccess()) return@get
        val userId = call.request.queryParameters["userId"]?.toLongOrNull()
        if (userId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing userId"))
        } else {
            val state = alertsService.getState(userId)
            call.respond(state)
        }
    }
}
