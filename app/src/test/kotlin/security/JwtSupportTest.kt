package security

import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.exceptions.TokenExpiredException
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JwtSupportTest {
    private val baseConfig =
        JwtConfig(
            issuer = "test-issuer",
            audience = "test-audience",
            realm = "test-realm",
            secret = "very-secret-key",
            accessTtlMinutes = 5,
        )

    @Test
    fun `issueToken and verify should return expected subject and claims`() {
        val claims = mapOf("role" to "admin", "userId" to "42")
        val token =
            JwtSupport.issueToken(
                config = baseConfig,
                subject = "user-123",
                claims = claims,
            )

        val decoded = JwtSupport.verify(baseConfig).verify(token)

        assertEquals("user-123", decoded.subject)
        claims.forEach { (key, value) ->
            assertEquals(value, decoded.getClaim(key).asString(), "Claim $key should match")
        }
    }

    @Test
    fun `verify should fail for tokens signed with another secret`() {
        val token =
            JwtSupport.issueToken(
                config = baseConfig,
                subject = "user-456",
            )
        val differentSecretConfig = baseConfig.copy(secret = "different-secret")

        assertFailsWith<JWTVerificationException> {
            JwtSupport.verify(differentSecretConfig).verify(token)
        }
    }

    @Test
    fun `verify should reject expired tokens`() {
        val past = Instant.now().minus(2, ChronoUnit.HOURS)
        val shortLivedConfig = baseConfig.copy(accessTtlMinutes = 1)
        val token =
            JwtSupport.issueToken(
                config = shortLivedConfig,
                subject = "user-789",
                now = past,
            )

        assertFailsWith<TokenExpiredException> {
            JwtSupport.verify(shortLivedConfig).verify(token)
        }
    }
}
