package sso.repo

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import sso.*
import storage.db.DatabaseFactory.dbQuery

object IdpProviders : LongIdTable("idp_providers", "idp_id") {
    val name = text("name")
    val kind = text("kind")
    val issuer = text("issuer")
    val clientId = text("client_id").nullable()
    val jwksUri = text("jwks_uri").nullable()
    val ssoUrl = text("sso_url").nullable()
    val enabled = bool("enabled")
    val createdAt = timestampWithTimeZone("created_at")
}

object IdpGroupMappings : Table("idp_group_mappings") {
    val tenantId = long("tenant_id")
    val idpId = long("idp_id").references(IdpProviders.id)
    val extGroup = text("ext_group")
    val role = text("role")
    override val primaryKey = PrimaryKey(tenantId, idpId, extGroup)
}

object SsoSessions : LongIdTable("sso_sessions", "session_id") {
    val userId = long("user_id").nullable()
    val tenantId = long("tenant_id").nullable()
    val idpId = long("idp_id").nullable()
    val subject = text("subject")
    val issuedAt = timestampWithTimeZone("issued_at")
    val expiresAt = timestampWithTimeZone("expires_at").nullable()
    val ip = text("ip").nullable()
    val userAgent = text("user_agent").nullable()
}

object ScimUsers : Table("scim_users") {
    val scimId = uuid("scim_id")
    val tenantId = long("tenant_id")
    val userId = long("user_id")
    val externalId = text("external_id").nullable()
    val userName = text("user_name")
    val email = text("email").nullable()
    val active = bool("active")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(scimId)
}

class IdpRepository {

    suspend fun findProviderByIssuer(issuer: String): IdpProvider? = dbQuery {
        IdpProviders.select { (IdpProviders.issuer eq issuer) and (IdpProviders.enabled eq true) }
            .firstOrNull()?.let {
                IdpProvider(
                    idpId = it[IdpProviders.id].value,
                    name = it[IdpProviders.name],
                    kind = IdpKind.valueOf(it[IdpProviders.kind]),
                    issuer = it[IdpProviders.issuer],
                    clientId = it[IdpProviders.clientId],
                    jwksUri = it[IdpProviders.jwksUri],
                    ssoUrl = it[IdpProviders.ssoUrl],
                    enabled = it[IdpProviders.enabled]
                )
            }
    }
}

class GroupMappingRepo {
    suspend fun mappingsForTenant(tenantId: Long, idpId: Long): List<sso.GroupRoleMapping> = dbQuery {
        IdpGroupMappings.select { (IdpGroupMappings.tenantId eq tenantId) and (IdpGroupMappings.idpId eq idpId) }
            .map { sso.GroupRoleMapping(tenantId, idpId, it[IdpGroupMappings.extGroup], it[IdpGroupMappings.role]) }
    }
}
