package sso

import kotlinx.serialization.Serializable

enum class IdpKind { oidc, saml }

@Serializable data class IdpProvider(
    val idpId: Long,
    val name: String,
    val kind: IdpKind,
    val issuer: String,
    val clientId: String? = null,
    val jwksUri: String? = null,
    val ssoUrl: String? = null,
    val enabled: Boolean = true
)

@Serializable data class GroupRoleMapping(
    val tenantId: Long,
    val idpId: Long,
    val extGroup: String,
    val role: String
)

@Serializable data class SsoProfile(
    val subject: String,
    val email: String?,
    val name: String?,
    val groups: Set<String>
)
