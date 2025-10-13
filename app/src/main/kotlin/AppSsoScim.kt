package your.app.package

import io.ktor.server.application.*
import io.ktor.server.routing.*
import sso.*
import sso.repo.*
import scim.scimRoutes
import io.ktor.client.*

fun Application.installSsoScimLayer(httpClient: HttpClient) {
    val idpRepo = IdpRepository()
    val mapRepo = GroupMappingRepo()
    val oidc = OidcSso(httpClient, idpRepo, mapRepo)

    routing {
        oidcRoutes(oidc)
        scimRoutes()
    }
}
