package routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import privacy.ErasureReport
import privacy.PrivacyService
import privacy.RetentionReport

class AdminPrivacyRoutesTest {
    @Test
    fun `erase requires authentication`() = testApplication {
        val service = FakePrivacyService()
        configure(service)
        val response = client.post("/api/admin/privacy/erase") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":1,"dryRun":true}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `non admin cannot access routes`() = testApplication {
        val service = FakePrivacyService()
        configure(service)
        val response = client.post("/api/admin/privacy/erase") {
            header(HttpHeaders.Authorization, bearerFor("123"))
            contentType(ContentType.Application.Json)
            setBody("""{"userId":1,"dryRun":false}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `dry run erase returns report without mutations`() = testApplication {
        val service = FakePrivacyService()
        configure(service)
        val response = client.post("/api/admin/privacy/erase") {
            header(HttpHeaders.Authorization, bearerFor(ADMIN_ID.toString()))
            contentType(ContentType.Application.Json)
            setBody("""{"userId":5,"dryRun":true}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val report = Json.decodeFromString<ErasureReport>(response.bodyAsText())
        assertEquals(5, report.userId)
        assertTrue(report.dryRun)
        assertEquals(true, service.lastErasure?.second)
    }

    @Test
    fun `erase executes with mutations when dryRun false`() = testApplication {
        val service = FakePrivacyService()
        configure(service)
        val response = client.post("/api/admin/privacy/erase") {
            header(HttpHeaders.Authorization, bearerFor(ADMIN_ID.toString()))
            contentType(ContentType.Application.Json)
            setBody("""{"userId":7,"dryRun":false}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val report = Json.decodeFromString<ErasureReport>(response.bodyAsText())
        assertEquals(7, report.userId)
        assertEquals(false, report.dryRun)
        assertEquals(false, service.lastErasure?.second)
    }

    @Test
    fun `retention run returns deleted summary`() = testApplication {
        val service = FakePrivacyService()
        configure(service)
        val response = client.post("/api/admin/privacy/retention/run") {
            header(HttpHeaders.Authorization, bearerFor(ADMIN_ID.toString()))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val report = Json.decodeFromString<RetentionReport>(response.bodyAsText())
        assertTrue(report.deletedByTable.containsKey("events"))
        assertTrue(service.retentionInvoked)
    }

    private fun ApplicationTestBuilder.configure(service: PrivacyService) {
        application {
            install(ContentNegotiation) { json() }
            install(io.ktor.server.auth.Authentication) {
                jwt("auth-jwt") {
                    verifier(
                        com.auth0.jwt.JWT
                            .require(Algorithm.HMAC256("test-secret"))
                            .build()
                    )
                    validate { credentials ->
                        credentials.payload.subject?.let { JWTPrincipal(credentials.payload) }
                    }
                }
            }
            routing {
                authenticate("auth-jwt") {
                    adminPrivacyRoutes(service, setOf(ADMIN_ID))
                }
            }
        }
    }

    private fun bearerFor(subject: String): String {
        val token = JWT.create().withSubject(subject).sign(Algorithm.HMAC256("test-secret"))
        return "Bearer $token"
    }

    private companion object {
        private const val ADMIN_ID = 7446417641L
    }
}

private class FakePrivacyService : PrivacyService {
    var lastErasure: Pair<Long, Boolean>? = null
    var retentionInvoked: Boolean = false

    override suspend fun enqueueErasure(userId: Long) {
    }

    override suspend fun runErasure(userId: Long, dryRun: Boolean): ErasureReport {
        lastErasure = userId to dryRun
        return ErasureReport(userId, deleted = mapOf("events" to 1L), anonymized = mapOf("bot_starts" to 1L), dryRun = dryRun)
    }

    override suspend fun runRetention(now: java.time.Instant): RetentionReport {
        retentionInvoked = true
        return RetentionReport(mapOf("events" to 5L))
    }
}

