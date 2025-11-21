package app

import io.ktor.client.HttpClient
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import scim.scimRoutes
import sso.OidcSso
import sso.oidcRoutes
import sso.repo.GroupMappingRepoImpl
import sso.repo.IdpRepository

fun Application.installSsoScimLayer(httpClient: HttpClient) {
    val idpRepo = IdpRepository()
    val mapRepo = GroupMappingRepoImpl()
    val oidc = OidcSso(httpClient, idpRepo, mapRepo)

    routing {
        oidcRoutes(oidc)
        scimRoutes()
    }
}
