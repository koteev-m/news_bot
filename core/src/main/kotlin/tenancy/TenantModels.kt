package tenancy

import kotlinx.serialization.Serializable

@Serializable
data class Org(
    val orgId: Long,
    val name: String,
)

@Serializable
data class Tenant(
    val tenantId: Long,
    val orgId: Long,
    val slug: String,
    val displayName: String,
)

@Serializable
data class Project(
    val projectId: Long,
    val tenantId: Long,
    val key: String,
    val name: String,
)

enum class Role { OWNER, ADMIN, DEVELOPER, VIEWER }

data class Quotas(
    val tenantId: Long,
    val maxPortfolios: Int,
    val maxAlerts: Int,
    val rpsSoft: Int,
    val rpsHard: Int,
)

data class TenantContext(
    val tenant: Tenant,
    val userId: Long?,
    val roles: Set<Role>,
    val scopes: Set<String>,
)
