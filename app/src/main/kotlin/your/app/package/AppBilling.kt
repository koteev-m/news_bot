package your.app.package

import io.ktor.server.application.*
import io.ktor.server.routing.*
import billing.*
import repo.UsageBillingRepository

fun Application.installBillingLayer() {
    val repo = UsageBillingRepository()
    val service = UsageService(repo)

    routing {
        usageRoutes(service)
        invoiceRoutes(service)
    }
}
