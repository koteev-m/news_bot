package app

import billing.StarsGatewayFactory
import billing.StarsWebhookHandler
import billing.service.BillingService
import billing.service.BillingServiceImpl
import di.installPortfolioModule
import health.healthRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import repo.BillingRepositoryImpl
import routes.BillingRouteServices
import routes.BillingRouteServicesKey
import routes.authRoutes
import routes.billingRoutes
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
    ensureBillingServices()

    routing {
        // Публичные
        healthRoutes()
        authRoutes()
        quotesRoutes()

        post("/telegram/webhook") {
            val expectedSecret = environment.config.propertyOrNull("telegram.webhookSecret")?.getString()
            val providedSecret = call.request.headers["X-Telegram-Bot-Api-Secret-Token"]
            if (expectedSecret.isNullOrBlank() || providedSecret != expectedSecret) {
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }

            val services = attributes[Services.Key]
            StarsWebhookHandler.handleIfStarsPayment(call, services.billingService)
        }

        // Защищённые (под JWT)
        authenticate("auth-jwt") {
            portfolioRoutes()
            portfolioPositionsTradesRoutes()
            portfolioImportRoutes()
            portfolioValuationReportRoutes()
            billingRoutes()
        }
    }
}

private fun Application.ensureBillingServices(): Services {
    if (attributes.contains(Services.Key)) {
        return attributes[Services.Key]
    }

    val billingService = BillingServiceImpl(
        repo = BillingRepositoryImpl(),
        stars = StarsGatewayFactory.fromConfig(environment),
        defaultDurationDays = billingDefaultDuration(),
    )
    val services = Services(billingService = billingService)
    attributes.put(Services.Key, services)
    attributes.put(BillingRouteServicesKey, BillingRouteServices(billingService))
    return services
}

private fun Application.billingDefaultDuration(): Long {
    val raw = environment.config.propertyOrNull("billing.defaultDurationDays")?.getString()
    return raw?.toLongOrNull() ?: 30L
}

data class Services(val billingService: BillingService) {
    companion object {
        val Key: AttributeKey<Services> = AttributeKey("AppServices")
    }
}
