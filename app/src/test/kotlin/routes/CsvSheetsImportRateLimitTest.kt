package routes

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import errors.installErrorPages
import portfolio.service.CsvImportService
import routes.ImportByUrlRateLimiterHolder
import routes.PortfolioImportDeps
import routes.PortfolioImportDepsKey
import routes.RemoteCsv
import routes.UploadSettings
import routes.dto.ImportReportResponse
import routes.portfolioImportRoutes
import routes.setImportByUrlLimiterHolder
import security.JwtConfig
import security.JwtSupport

class CsvSheetsImportRateLimitTest {
    private val jwtConfig = JwtConfig(
        issuer = "newsbot",
        audience = "newsbot-clients",
        realm = "newsbot-api",
        secret = "test-secret",
        accessTtlMinutes = 60,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `returns 503 when import by url disabled`() = testApplication {
        val deps = FakeDeps().apply {
            importResult = Result.success(
                CsvImportService.ImportReport(inserted = 1, skippedDuplicates = 0, failed = emptyList()),
            )
        }
        val clock = MutableClock(Instant.parse("2024-01-01T00:00:00Z"))
        environment {
            config = MapApplicationConfig(
                "import.byUrlEnabled" to "false",
                "import.byUrlRateLimit.capacity" to "3",
                "import.byUrlRateLimit.refillPerMinute" to "3",
            )
        }
        application { configureTestApp(deps.toDeps(), clock) }

        val token = issueToken("user-disabled")
        val response = client.post("/api/portfolio/${UUID.randomUUID()}/trades/import/by-url") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://example.com/report.csv"}""")
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val payload = HttpErrorResponse(response.bodyAsText())
        assertEquals("import_by_url_disabled", payload.error)
    }

    @Test
    fun `rate limit enforces capacity and refills after wait`() = testApplication {
        val deps = FakeDeps().apply {
            importResult = Result.success(
                CsvImportService.ImportReport(inserted = 1, skippedDuplicates = 0, failed = emptyList()),
            )
        }
        val clock = MutableClock(Instant.parse("2024-01-01T00:00:00Z"))
        environment {
            config = MapApplicationConfig(
                "import.byUrlEnabled" to "true",
                "import.byUrlRateLimit.capacity" to "2",
                "import.byUrlRateLimit.refillPerMinute" to "2",
            )
        }
        application { configureTestApp(deps.toDeps(), clock) }

        val token = issueToken("user-rate-limit")
        val path = "/api/portfolio/${UUID.randomUUID()}/trades/import/by-url"

        suspend fun authorizedRequest(): HttpResponse = client.post(path) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://example.com/data.csv"}""")
        }

        val first = authorizedRequest()
        assertEquals(HttpStatusCode.OK, first.status)
        val firstPayload = json.decodeFromString<ImportReportResponse>(first.bodyAsText())
        assertEquals(1, firstPayload.inserted)

        val second = authorizedRequest()
        assertEquals(HttpStatusCode.OK, second.status)

        val third = authorizedRequest()
        assertEquals(HttpStatusCode.TooManyRequests, third.status)
        val retryHeader = third.headers[HttpHeaders.RetryAfter]
        assertNotNull(retryHeader)
        assertTrue(retryHeader.toLong() >= 1)
        val limitedPayload = HttpErrorResponse(third.bodyAsText())
        assertEquals("rate_limited", limitedPayload.error)

        clock.advance(Duration.ofSeconds(30))

        val fourth = authorizedRequest()
        assertEquals(HttpStatusCode.OK, fourth.status)
    }

    @Test
    fun `missing JWT returns 401`() = testApplication {
        val deps = FakeDeps()
        val clock = MutableClock(Instant.parse("2024-01-01T00:00:00Z"))
        environment {
            config = MapApplicationConfig(
                "import.byUrlEnabled" to "true",
                "import.byUrlRateLimit.capacity" to "3",
                "import.byUrlRateLimit.refillPerMinute" to "3",
            )
        }
        application { configureTestApp(deps.toDeps(), clock) }

        val response = client.post("/api/portfolio/${UUID.randomUUID()}/trades/import/by-url") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://example.com/data.csv"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    private fun Application.configureTestApp(deps: PortfolioImportDeps, clock: MutableClock) {
        installErrorPages()
        install(ContentNegotiation) {
            json(json)
        }
        install(Authentication) {
            jwt("auth-jwt") {
                realm = jwtConfig.realm
                verifier(JwtSupport.verify(jwtConfig))
                validate { credentials -> credentials.payload.subject?.let { JWTPrincipal(credentials.payload) } }
            }
        }
        attributes.put(PortfolioImportDepsKey, deps)
        setImportByUrlLimiterHolder(ImportByUrlRateLimiterHolder(clock))
        routing {
            authenticate("auth-jwt") {
                portfolioImportRoutes()
            }
        }
    }

    private fun issueToken(subject: String): String = JwtSupport.issueToken(jwtConfig, subject)

    private class FakeDeps {
        var importResult: Result<CsvImportService.ImportReport> = Result.success(CsvImportService.ImportReport())
        var downloadBytes: ByteArray = "ext_id,datetime\n".toByteArray()
        var downloadContentType: ContentType? = ContentType.Text.CSV

        fun toDeps(): PortfolioImportDeps = PortfolioImportDeps(
            importCsv = { _, reader ->
                reader.use { it.readText() }
                importResult
            },
            uploadSettings = UploadSettings(
                csvMaxBytes = 1_048_576,
                allowedContentTypes = setOf(ContentType.Text.CSV, ContentType.Application.OctetStream),
            ),
            downloadCsv = { _, _ -> RemoteCsv(contentType = downloadContentType, bytes = downloadBytes) },
        )
    }

    private class MutableClock(initialInstant: Instant) : Clock() {
        private var instantValue: Instant = initialInstant
        private var zoneId: ZoneId = ZoneOffset.UTC

        override fun getZone(): ZoneId = zoneId

        override fun withZone(zone: ZoneId): Clock = MutableClock(instantValue).also { it.zoneId = zone }

        override fun instant(): Instant = instantValue

        fun advance(duration: Duration) {
            instantValue = instantValue.plus(duration)
        }
    }
}
