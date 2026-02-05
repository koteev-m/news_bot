package com.example.app.routes

import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class DsarRequest(
    val userId: Long,
    val action: String,
) // "export" | "erase"

@Serializable
data class DsarResponse(
    val userId: Long,
    val action: String,
    val status: String,
    val processedAt: String,
)

fun Route.dsarRoutes() {
    route("/api/dsar") {
        post {
            val req = call.receive<DsarRequest>()
            val ts =
                java.time.Instant
                    .now()
                    .toString()
            val res =
                when (req.action.lowercase()) {
                    "export" -> DsarResponse(req.userId, "export", "ok", ts)
                    "erase" -> DsarResponse(req.userId, "erase", "ok", ts)
                    else -> DsarResponse(req.userId, "unknown", "ignored", ts)
                }
            call.respond(res)
        }
    }
}
