package security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import com.auth0.jwt.interfaces.JWTVerifier
import io.ktor.server.application.ApplicationEnvironment

data class JwtKeys(
    val primary: String,
    val secondary: String?,
)

fun ApplicationEnvironment.jwtKeys(): JwtKeys {
    val securityConfig = config.config("security")
    val primary =
        securityConfig
            .propertyOrNull("jwtSecretPrimary")
            ?.getString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: securityConfig
                .propertyOrNull("jwtSecret")
                ?.getString()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("security.jwtSecretPrimary must not be blank")

    val secondary =
        securityConfig
            .propertyOrNull("jwtSecretSecondary")
            ?.getString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    return JwtKeys(primary, secondary)
}

fun verifyWithAny(
    config: JwtConfig,
    keys: JwtKeys,
): JWTVerifier {
    val verifiers =
        buildList {
            add(createVerifier(config, keys.primary))
            keys.secondary?.let { add(createVerifier(config, it)) }
        }

    return object : JWTVerifier {
        override fun verify(token: String): DecodedJWT {
            var lastError: JWTVerificationException? = null
            for (verifier in verifiers) {
                try {
                    return verifier.verify(token)
                } catch (ex: JWTVerificationException) {
                    lastError = ex
                }
            }
            throw lastError ?: JWTVerificationException("JWT verification failed")
        }

        override fun verify(jwt: DecodedJWT): DecodedJWT {
            var lastError: JWTVerificationException? = null
            for (verifier in verifiers) {
                try {
                    return verifier.verify(jwt)
                } catch (ex: JWTVerificationException) {
                    lastError = ex
                }
            }
            throw lastError ?: JWTVerificationException("JWT verification failed")
        }
    }
}

fun signWithPrimary(keys: JwtKeys): Algorithm = Algorithm.HMAC256(keys.primary)

private fun createVerifier(
    config: JwtConfig,
    secret: String,
): JWTVerifier =
    JWT
        .require(Algorithm.HMAC256(secret))
        .withIssuer(config.issuer)
        .withAudience(config.audience)
        .build()
