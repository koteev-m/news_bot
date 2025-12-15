package alerts

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.Route
import io.ktor.server.request.receive

fun Route.alertsRoutes(alertsService: AlertsService) {
    post("/internal/alerts/snapshot") {
        val snapshot = call.receive<MarketSnapshot>()
        call.respond(alertsService.onSnapshot(snapshot))
    }
    get("/internal/alerts/state") {
        val userId = call.request.queryParameters["userId"]?.toLongOrNull()
        val state = userId?.let { alertsService.getState(it) } ?: FsmState.IDLE
        call.respond(state)
    }
}
