package routes

import ab.Experiment
import ab.ExperimentsPort
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import security.userIdOrNull

@Serializable
data class ExperimentUpsert(val key: String, val enabled: Boolean, val traffic: Map<String, Int>)

fun Route.adminExperimentsRoutes(port: ExperimentsPort, adminUserIds: Set<Long>) {
    route("/api/admin/experiments") {
        post("/upsert") {
            val userId = call.userIdOrNull?.toLongOrNull()
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized)
                return@post
            }
            if (userId !in adminUserIds) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                return@post
            }
            val request = runCatching { call.receive<ExperimentUpsert>() }
                .getOrElse { exception ->
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (exception.message ?: "invalid payload")))
                    return@post
                }
            val totalTraffic = request.traffic.values.sum()
            if (totalTraffic != 100) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "traffic sum must be 100"))
                return@post
            }
            if (request.traffic.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "traffic is empty"))
                return@post
            }
            port.upsert(Experiment(key = request.key, enabled = request.enabled, traffic = request.traffic))
            call.respond(HttpStatusCode.NoContent)
        }
        get {
            call.respond(HttpStatusCode.OK, port.list())
        }
    }
}
