package sso

/**
 * Репозиторий маппингов внешних групп IdP → платформенных ролей.
 * Держим интерфейс в core, чтобы не тянуть зависимость на :storage.
 */
interface GroupMappingRepo {
    suspend fun mappingsForTenant(
        tenantId: Long,
        idpId: Long,
    ): List<GroupRoleMapping>
}
