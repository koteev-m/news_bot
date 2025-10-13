package sso

import tenancy.Role

class GroupRoleMapper(private val repo: sso.repo.GroupMappingRepo) {
    suspend fun resolveRoles(tenantId: Long, idpId: Long, groups: Set<String>): Set<Role> {
        val mappings = repo.mappingsForTenant(tenantId, idpId)
        val hits = mappings.filter { it.extGroup in groups }.mapNotNull {
            runCatching { Role.valueOf(it.role) }.getOrNull()
        }.toSet()
        return if (hits.isNotEmpty()) hits else setOf(Role.VIEWER)
    }
}
