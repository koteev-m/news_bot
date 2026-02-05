package app

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.request.header
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import repo.TenancyRepository
import routes.adminTenancyRoutes
import tenancy.DefaultRbacService
import tenancy.QuotaService
import tenancy.RbacService
import tenancy.TenantPlugin

private val RbacServiceKey = AttributeKey<RbacService>("rbacService")
private val QuotaServiceKey = AttributeKey<QuotaService>("quotaService")

fun Application.installTenancyLayer() {
    val tenancyRepo = TenancyRepository()
    install(TenantPlugin) {
        repository = tenancyRepo
        userIdProvider = { call -> call.request.header("X-User-Id")?.toLongOrNull() }
        scopesProvider = { call ->
            call.request
                .header("X-Scopes")
                ?.split(" ")
                ?.toSet() ?: emptySet()
        }
    }
    val rbac = DefaultRbacService()
    val quotas = QuotaService(tenancyRepo)
    attributes.put(RbacServiceKey, rbac)
    attributes.put(QuotaServiceKey, quotas)

    routing {
        adminTenancyRoutes(tenancyRepo)
        // post("/api/portfolio") { ... }
    }
}
