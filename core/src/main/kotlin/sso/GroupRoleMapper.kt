package sso

import common.runCatchingNonFatal
import tenancy.Role

/**
 * Маппер групп из IdP в платформенные роли.
 * Зависит только от интерфейса GroupMappingRepo из core.
 */
class GroupRoleMapper(
    private val repo: GroupMappingRepo,
) {
    suspend fun resolveRoles(
        tenantId: Long,
        idpId: Long,
        groups: Set<String>,
    ): Set<Role> {
        val mappings = repo.mappingsForTenant(tenantId, idpId)
        val resolved =
            mappings
                .filter { it.extGroup in groups }
                .mapNotNull { runCatchingNonFatal { Role.valueOf(it.role) }.getOrNull() }
                .toSet()
        return if (resolved.isNotEmpty()) resolved else setOf(Role.VIEWER)
    }
}
