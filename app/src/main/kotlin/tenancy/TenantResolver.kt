package tenancy

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.host
import io.ktor.util.AttributeKey
import repo.TenancyRepository

val TenantContextKey = AttributeKey<TenantContext>("tenantContext")

class TenantResolver(
    private val repository: TenancyRepository,
) {
    suspend fun resolve(
        call: ApplicationCall,
        userId: Long?,
        jwtScopes: Set<String>,
    ): TenantContext {
        val slugHeader = call.request.header("X-Tenant-Slug")
        val host = call.request.host()
        val slugSub = host.takeIf { it.contains('.') }?.substringBefore('.')
        val slugClaim = call.request.header("X-JWT-Tenant")

        val slug = slugHeader ?: slugClaim ?: slugSub
        require(!slug.isNullOrBlank()) { "tenant slug required" }
        val tenant = repository.findTenantBySlug(slug) ?: error("tenant not found")
        val roles = userId?.let { repository.rolesForUser(tenant.tenantId, it) } ?: emptySet()
        return TenantContext(tenant, userId, roles, jwtScopes)
    }
}

fun ApplicationCall.tenantContext(): TenantContext = attributes[TenantContextKey]
