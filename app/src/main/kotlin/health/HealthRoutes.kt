package health

import db.DatabaseFactory
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.healthRoutes() {
    get("/health/db") {
        DatabaseFactory.ping()
        call.respond(mapOf("db" to "up"))
    }
}
