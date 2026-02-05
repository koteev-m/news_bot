package security

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.json.Json

fun Application.installSecurity() {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            },
        )
    }

    val jwtConfig = JwtSupport.config(environment)
    val jwtKeys = environment.jwtKeys()
    val verifier = verifyWithAny(jwtConfig, jwtKeys)

    install(Authentication) {
        jwt("auth-jwt") {
            realm = jwtConfig.realm
            verifier(verifier)
            validate { credentials ->
                credentials.payload.subject
                    ?.takeIf { it.isNotBlank() }
                    ?.let { JWTPrincipal(credentials.payload) }
            }
        }
    }
}

val ApplicationCall.userIdOrNull: String?
    get() = principal<JWTPrincipal>()?.payload?.subject
