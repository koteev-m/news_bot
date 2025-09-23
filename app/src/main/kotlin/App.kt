package app

import db.DatabaseFactory
import di.installPortfolioModule
import health.healthRoutes
import integrations.integrationsModule
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import routes.authRoutes
import routes.portfolioRoutes
import security.installSecurity
import security.installUploadGuard

fun main() {
    embeddedServer(Netty, port = 8080) {
        module()
    }.start(wait = true)
}

fun Application.module() {
    installSecurity()
    installUploadGuard()

    DatabaseFactory.init()
    integrationsModule()
    installPortfolioModule()
    routing {
        healthRoutes()
        authRoutes()
        authenticate("auth-jwt") {
            portfolioRoutes()
        }
    }
}
