package app

import di.installPortfolioModule
import health.healthRoutes
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import routes.authRoutes
import routes.portfolioImportRoutes
import routes.portfolioPositionsTradesRoutes
import routes.portfolioRoutes
import routes.portfolioValuationReportRoutes
import routes.quotesRoutes
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
    installPortfolioModule()

    routing {
        healthRoutes()
        authRoutes()
        quotesRoutes()

        authenticate("auth-jwt") {
            portfolioRoutes()
            portfolioPositionsTradesRoutes()
            portfolioImportRoutes()
            portfolioValuationReportRoutes()
        }
    }
}
