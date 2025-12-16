package alerts

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.Route
import io.ktor.server.request.receive
import io.ktor.http.HttpStatusCode

fun Route.alertsRoutes(alertsService: AlertsService) {
    post("/internal/alerts/snapshot") {
        val snapshot = call.receive<MarketSnapshot>()
        call.respond(alertsService.onSnapshot(snapshot))
    }
    get("/internal/alerts/state") {
        val userId = call.request.queryParameters["userId"]?.toLongOrNull()
        if (userId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing userId"))
        } else {
            val state = alertsService.getState(userId)
            call.respond(state)
        }
    }
}
