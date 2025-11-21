package sso.repo

import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import sso.GroupMappingRepo
import sso.GroupRoleMapping
import db.DatabaseFactory.dbQuery

/**
 * Реализация интерфейса core:sso.GroupMappingRepo на Exposed.
 */
class GroupMappingRepoImpl : GroupMappingRepo {
    override suspend fun mappingsForTenant(tenantId: Long, idpId: Long): List<GroupRoleMapping> = dbQuery {
        IdpGroupMappings
            .select { (IdpGroupMappings.tenantId eq tenantId) and (IdpGroupMappings.idpId eq idpId) }
            .map { row ->
                GroupRoleMapping(
                    tenantId = tenantId,
                    idpId = idpId,
                    extGroup = row[IdpGroupMappings.extGroup],
                    role = row[IdpGroupMappings.role]
                )
            }
    }
}
