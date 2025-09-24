package app

import di.installPortfolioModule
import health.healthRoutes
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.routing
import routes.authRoutes
import routes.portfolioImportRoutes
import routes.portfolioPositionsTradesRoutes
import routes.portfolioRoutes
import routes.portfolioValuationReportRoutes
import routes.quotesRoutes
import security.installSecurity
import security.installUploadGuard

fun Application.module() {
    installSecurity()
    installUploadGuard()
    installPortfolioModule()

    routing {
        // Публичные
        healthRoutes()
        authRoutes()
        quotesRoutes()

        // Защищённые (под JWT)
        authenticate("auth-jwt") {
            portfolioRoutes()
            portfolioPositionsTradesRoutes()
            portfolioImportRoutes()
            portfolioValuationReportRoutes()
        }
    }
}
