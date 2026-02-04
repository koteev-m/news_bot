package app

import billing.UsageService
import billing.invoiceRoutes
import billing.usageRoutes
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import repo.UsageBillingRepository

fun Application.installBillingLayer() {
    val repo = UsageBillingRepository()
    val service = UsageService(repo)

    routing {
        usageRoutes(service)
        invoiceRoutes(service)
    }
}
