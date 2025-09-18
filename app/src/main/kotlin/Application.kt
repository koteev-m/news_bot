package app

import db.DatabaseFactory
import health.healthRoutes
import integrations.integrationsModule
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*

fun main() {
    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) { json() }
        DatabaseFactory.init()
        @Suppress("UNUSED_VARIABLE")
        val integrations = integrationsModule()
        routing { healthRoutes() }
    }.start(wait = true)
}
