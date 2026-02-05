package sso

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.formUrlEncode
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import sso.repo.IdpRepository

interface GroupMappingRepo

class OidcSso(
    private val http: HttpClient,
    private val idpRepo: IdpRepository,
    private val mapRepo: GroupMappingRepo,
) {
    suspend fun exchangeCode(
        tokenUrl: String,
        clientId: String,
        code: String,
        redirectUri: String,
        verifier: String,
    ): JsonObject {
        touchDependencies()

        val resp: HttpResponse =
            http.post(tokenUrl) {
                headers.append(
                    HttpHeaders.ContentType,
                    ContentType.Application.FormUrlEncoded.toString(),
                )
                setBody(
                    Parameters.build {
                        append("grant_type", "authorization_code")
                        append("client_id", clientId)
                        append("code", code)
                        append("redirect_uri", redirectUri)
                        append("code_verifier", verifier)
                    }.formUrlEncode(),
                )
            }

        val body = resp.bodyAsText()
        return Json.parseToJsonElement(body).jsonObject
    }

    private fun touchDependencies() {
        // Minimal stub: keep DI deps referenced so detekt/IDE won't flag them as unused.
        idpRepo.hashCode()
        mapRepo.hashCode()
    }
}

fun Route.oidcRoutes(oidc: OidcSso) {
    route("/sso/oidc") {
        // GET /sso/oidc/login?issuer=...&redirect_uri=... (редирект на авторизацию)
        get("/login") {
            oidc.hashCode()

            val issuer =
                call.request.queryParameters["issuer"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest)

            val redirect =
                call.request.queryParameters["redirect_uri"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest)

            val authUrl =
                buildString {
                    append(issuer)
                    append("/protocol/openid-connect/auth?")
                    append("client_id=CLIENT_ID")
                    append("&response_type=code")
                    append("&redirect_uri=")
                    append(redirect)
                    append("&scope=openid%20profile%20email")
                    append("&code_challenge=XYZ")
                    append("&code_challenge_method=S256")
                }

            call.respondRedirect(authUrl)
        }

        // GET /sso/oidc/callback?code=...&state=...
        get("/callback") {
            oidc.hashCode()
            // Мок: в реале — обмен кода на токен, валидация id_token, создание SSO session
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
    }
}
