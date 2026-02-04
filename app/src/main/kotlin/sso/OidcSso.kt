package sso

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.http.*
import io.ktor.util.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.call.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import tenancy.*
import sso.repo.IdpRepository

class OidcSso(
    private val http: HttpClient,
    private val idpRepo: IdpRepository,
    private val mapRepo: GroupMappingRepo
) {

    suspend fun exchangeCode(tokenUrl: String, clientId: String, code: String, redirectUri: String, verifier: String): JsonObject {
        val resp: HttpResponse = http.post(tokenUrl) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                Parameters.build {
                    append("grant_type", "authorization_code")
                    append("client_id", clientId)
                    append("code", code)
                    append("redirect_uri", redirectUri)
                    append("code_verifier", verifier)
                }.formUrlEncode()
            )
        }
        val body = resp.bodyAsText()
        return Json.parseToJsonElement(body).jsonObject
    }
}

fun Route.oidcRoutes(oidc: OidcSso) {
    route("/sso/oidc") {
        // GET /sso/oidc/login?issuer=...&redirect_uri=... (редирект на авторизацию)
        get("/login") {
            val issuer = call.request.queryParameters["issuer"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val redirect = call.request.queryParameters["redirect_uri"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            // В реале: Discovery (/.well-known/openid-configuration) и авторизация
            call.respondRedirect(
                "$issuer/protocol/openid-connect/auth?client_id=CLIENT_ID&response_type=code&redirect_uri=$redirect&scope=openid%20profile%20email&code_challenge=XYZ&code_challenge_method=S256"
            )
        }
        // GET /sso/oidc/callback?code=...&state=...
        get("/callback") {
            // Мок: в реале — обмен кода на токен, валидация id_token, создание SSO session
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
    }
}
