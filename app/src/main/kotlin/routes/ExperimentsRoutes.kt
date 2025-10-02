package routes

import ab.ExperimentsService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import security.userIdOrNull

fun Route.experimentsRoutes(service: ExperimentsService) {
    get("/api/experiments/assignments") {
        val userId = call.userIdOrNull?.toLongOrNull()
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized)
            return@get
        }
        val assignments = service.activeAssignments(userId)
        call.respond(HttpStatusCode.OK, assignments)
    }
}
