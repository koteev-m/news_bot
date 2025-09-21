package security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.JWTVerifier
import io.ktor.server.application.ApplicationEnvironment
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date

data class JwtConfig(
    val issuer: String,
    val audience: String,
    val realm: String,
    val secret: String,
    val accessTtlMinutes: Int,
)

object JwtSupport {
    private const val SECURITY_CONFIG_PATH = "security"
    private const val SECRET_PROPERTY = "jwtSecret"
    private const val ISSUER_PROPERTY = "issuer"
    private const val AUDIENCE_PROPERTY = "audience"
    private const val REALM_PROPERTY = "realm"
    private const val ACCESS_TTL_PROPERTY = "accessTtlMinutes"

    fun config(env: ApplicationEnvironment): JwtConfig {
        val securityConfig = env.config.config(SECURITY_CONFIG_PATH)
        val issuer = securityConfig.property(ISSUER_PROPERTY).getString().trim()
        val audience = securityConfig.property(AUDIENCE_PROPERTY).getString().trim()
        val realm = securityConfig.property(REALM_PROPERTY).getString().trim()
        val secret = securityConfig.property(SECRET_PROPERTY).getString().trim()
        val accessTtlMinutes = securityConfig.property(ACCESS_TTL_PROPERTY).getString().trim().toIntOrNull()
            ?: throw IllegalArgumentException("security.$ACCESS_TTL_PROPERTY must be a positive integer")

        require(issuer.isNotEmpty()) { "security.$ISSUER_PROPERTY must not be blank" }
        require(audience.isNotEmpty()) { "security.$AUDIENCE_PROPERTY must not be blank" }
        require(realm.isNotEmpty()) { "security.$REALM_PROPERTY must not be blank" }
        require(secret.isNotEmpty()) { "security.$SECRET_PROPERTY must not be blank" }
        require(accessTtlMinutes > 0) { "security.$ACCESS_TTL_PROPERTY must be greater than zero" }

        return JwtConfig(
            issuer = issuer,
            audience = audience,
            realm = realm,
            secret = secret,
            accessTtlMinutes = accessTtlMinutes,
        )
    }

    fun issueToken(
        config: JwtConfig,
        subject: String,
        claims: Map<String, String> = emptyMap(),
        now: Instant = Instant.now(),
    ): String {
        val expiresAt = now.plus(config.accessTtlMinutes.toLong(), ChronoUnit.MINUTES)
        val algorithm = Algorithm.HMAC256(config.secret)
        val builder = JWT.create()
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .withSubject(subject)
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(expiresAt))

        claims.forEach { (key, value) ->
            if (key.isNotBlank()) {
                builder.withClaim(key, value)
            }
        }

        return builder.sign(algorithm)
    }

    fun verify(config: JwtConfig): JWTVerifier =
        JWT.require(Algorithm.HMAC256(config.secret))
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .build()
}
