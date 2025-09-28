package routes

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Clock
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import portfolio.service.CsvImportService
import routes.ImportByUrlRateLimiterHolder
import routes.ImportByUrlSettings
import routes.dto.ImportReportResponse
import routes.setImportByUrlLimiterHolder
import routes.setImportByUrlSettings
import security.JwtConfig
import security.JwtSupport
import security.RateLimitConfig

class CsvSheetsImportRoutesTest {
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

    private lateinit var app: SimpleTestApplication

    @BeforeTest
    fun setUp() {
        app = SimpleTestApplication()
    }

    @AfterTest
    fun tearDown() {
        app.close()
    }

    @Test
    fun `multipart import without JWT returns 401`() {
        val deps = FakeDeps()
        app.application { configureTestApp(deps.toDeps()) }

        val response = app.postMultipart(
            path = "/api/portfolio/${UUID.randomUUID()}/trades/import/csv",
            parts = listOf(
                multipartFile("file", "trades.csv", "text/csv", "ext_id,datetime\n".toByteArray()),
            ),
        )
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `import by url without JWT returns 401`() {
        val deps = FakeDeps()
        app.application { configureTestApp(deps.toDeps()) }

        val response = app.postJson(
            path = "/api/portfolio/${UUID.randomUUID()}/trades/import/by-url",
            body = """{"url":"https://example.com/sample.csv"}""",
        )
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `invalid portfolio id returns 400`() {
        val deps = FakeDeps()
        app.application { configureTestApp(deps.toDeps()) }

        val token = issueToken("123")
        val response = app.postMultipart(
            path = "/api/portfolio/not-a-uuid/trades/import/csv",
            headers = authHeader(token),
            parts = listOf(
                multipartFile("file", "trades.csv", "text/csv", "ext_id,datetime\n".toByteArray()),
            ),
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val payload = json.decodeFromString<HttpErrorResponse>(response.body)
        assertEquals("bad_request", payload.error)
        assertTrue(payload.details?.any { it.contains("portfolioId", ignoreCase = true) } == true)
    }

    @Test
    fun `missing file part returns 400`() {
        val deps = FakeDeps()
        app.application { configureTestApp(deps.toDeps()) }

        val token = issueToken("456")
        val response = app.postMultipart(
            path = "/api/portfolio/${UUID.randomUUID()}/trades/import/csv",
            headers = authHeader(token),
            parts = listOf(
                MultipartPart(name = "meta", filename = null, contentType = "text/plain", content = "value".toByteArray()),
            ),
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val payload = json.decodeFromString<HttpErrorResponse>(response.body)
        assertEquals("bad_request", payload.error)
        assertTrue(payload.details?.any { it.contains("file part", ignoreCase = true) } == true)
    }

    @Test
    fun `unsupported mime type returns 415`() {
        val deps = FakeDeps()
        app.application { configureTestApp(deps.toDeps()) }

        val token = issueToken("789")
        val response = app.postMultipart(
            path = "/api/portfolio/${UUID.randomUUID()}/trades/import/csv",
            headers = authHeader(token),
            parts = listOf(multipartFile("file", "image.png", "image/png", ByteArray(8) { 1 })),
        )
        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        val payload = json.decodeFromString<HttpErrorResponse>(response.body)
        assertEquals("unsupported_media_type", payload.error)
    }

    @Test
    fun `payload larger than limit returns 413`() {
        val deps = FakeDeps().apply { maxBytes = 64 }
        app.application { configureTestApp(deps.toDeps()) }

        val token = issueToken("111")
        val large = ByteArray(256) { 'A'.code.toByte() }
        val response = app.postMultipart(
            path = "/api/portfolio/${UUID.randomUUID()}/trades/import/csv",
            headers = authHeader(token),
            parts = listOf(
                multipartFile(
                    name = "file",
                    filename = "trades.csv",
                    contentType = "text/csv",
                    content = large,
                    headers = mapOf(HttpHeaders.ContentLength to (large.size.toLong()).toString()),
                ),
            ),
        )
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        val payload = json.decodeFromString<HttpErrorResponse>(response.body)
        assertEquals("payload_too_large", payload.error)
        assertEquals(64, payload.limit)
    }

    @Test
    fun `successful multipart import returns report`() {
        val deps = FakeDeps().apply {
            importResult = Result.success(
                CsvImportService.ImportReport(
                    inserted = 3,
                    skippedDuplicates = 1,
                    failed = listOf(
                        CsvImportService.ImportFailure(lineNumber = 4, extId = "dup", message = "SELL exceeds free qty"),
                        CsvImportService.ImportFailure(lineNumber = 6, extId = null, message = "Invalid price"),
                    ),
                ),
            )
        }
        app.application { configureTestApp(deps.toDeps()) }

        val token = issueToken("222")
        val csvBytes = Files.readAllBytes(Paths.get("..", "samples", "trades.csv"))
        val response = app.postMultipart(
            path = "/api/portfolio/${UUID.randomUUID()}/trades/import/csv",
            headers = authHeader(token),
            parts = listOf(multipartFile("file", "trades.csv", "text/csv", csvBytes)),
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.decodeFromString<ImportReportResponse>(response.body)
        assertEquals(3, payload.inserted)
        assertEquals(1, payload.skippedDuplicates)
        assertEquals(2, payload.failed.size)
        val first = payload.failed.first()
        assertEquals(4, first.line)
        assertEquals("SELL exceeds free qty", first.error)
        val second = payload.failed.last()
        assertEquals(6, second.line)
        assertEquals("Invalid price", second.error)
    }

    @Test
    fun `successful import by url returns report`() {
        val csv = "ext_id,datetime\n".toByteArray()
        val deps = FakeDeps().apply {
            downloadBytes = csv
            downloadContentType = ContentType.Text.CSV
            importResult = Result.success(
                CsvImportService.ImportReport(
                    inserted = 2,
                    skippedDuplicates = 0,
                    failed = emptyList(),
                ),
            )
        }
        app.application { configureTestApp(deps.toDeps()) }

        val token = issueToken("333")
        val response = app.postJson(
            path = "/api/portfolio/${UUID.randomUUID()}/trades/import/by-url",
            headers = authHeader(token),
            body = """{"url":"https://example.com/trades.csv"}""",
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.decodeFromString<ImportReportResponse>(response.body)
        assertEquals(2, payload.inserted)
        assertEquals(0, payload.skippedDuplicates)
        assertTrue(payload.failed.isEmpty())
    }

    @Test
    fun `non https url returns 400`() {
        val deps = FakeDeps()
        app.application { configureTestApp(deps.toDeps()) }

        val token = issueToken("444")
        val response = app.postJson(
            path = "/api/portfolio/${UUID.randomUUID()}/trades/import/by-url",
            headers = authHeader(token),
            body = """{"url":"http://example.com/data.csv"}""",
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val payload = json.decodeFromString<HttpErrorResponse>(response.body)
        assertEquals("bad_request", payload.error)
    }

    @Test
    fun `empty url returns 400`() {
        val deps = FakeDeps()
        app.application { configureTestApp(deps.toDeps()) }

        val token = issueToken("555")
        val response = app.postJson(
            path = "/api/portfolio/${UUID.randomUUID()}/trades/import/by-url",
            headers = authHeader(token),
            body = """{"url":"   "}""",
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val payload = json.decodeFromString<HttpErrorResponse>(response.body)
        assertEquals("bad_request", payload.error)
    }

    @Test
    fun `download exceeding limit returns 413`() {
        val deps = FakeDeps().apply {
            downloadError = RemoteCsvTooLargeException(limit = 256)
        }
        app.application { configureTestApp(deps.toDeps()) }

        val token = issueToken("666")
        val response = app.postJson(
            path = "/api/portfolio/${UUID.randomUUID()}/trades/import/by-url",
            headers = authHeader(token),
            body = """{"url":"https://example.com/big.csv"}""",
        )
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        val payload = json.decodeFromString<HttpErrorResponse>(response.body)
        assertEquals("payload_too_large", payload.error)
        assertEquals(256, payload.limit)
    }

    @Test
    fun `unexpected importer error returns 500`() {
        val deps = FakeDeps().apply { throwOnImport = RuntimeException("boom") }
        app.application { configureTestApp(deps.toDeps()) }

        val token = issueToken("777")
        val response = app.postMultipart(
            path = "/api/portfolio/${UUID.randomUUID()}/trades/import/csv",
            headers = authHeader(token),
            parts = listOf(multipartFile("file", "trades.csv", "text/csv", "ext_id,datetime\n".toByteArray())),
        )
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val payload = json.decodeFromString<HttpErrorResponse>(response.body)
        assertEquals("internal", payload.error)
    }

    private fun Application.configureTestApp(deps: PortfolioImportDeps) {
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
        setImportByUrlSettings(
            ImportByUrlSettings(
                enabled = true,
                rateLimit = RateLimitConfig(capacity = 100, refillPerMinute = 100),
            ),
        )
        setImportByUrlLimiterHolder(ImportByUrlRateLimiterHolder(Clock.systemUTC()))
        routing {
            authenticate("auth-jwt") {
                portfolioImportRoutes()
            }
        }
    }

    private fun issueToken(subject: String): String = JwtSupport.issueToken(jwtConfig, subject)

    private fun authHeader(token: String): Map<String, String> = mapOf(HttpHeaders.Authorization to "Bearer $token")

    private fun multipartFile(
        name: String,
        filename: String,
        contentType: String,
        content: ByteArray,
        headers: Map<String, String> = emptyMap(),
    ): MultipartPart = MultipartPart(
        name = name,
        filename = filename,
        contentType = contentType,
        content = content,
        headers = headers,
    )

    private data class MultipartPart(
        val name: String,
        val filename: String?,
        val contentType: String?,
        val content: ByteArray,
        val headers: Map<String, String> = emptyMap(),
    )

    private data class SimpleHttpResponse(
        val status: HttpStatusCode,
        val body: String,
    )

    private class SimpleTestApplication {
        private var module: (Application.() -> Unit)? = null
        private var engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
        private var port: Int = 0

        fun application(configure: Application.() -> Unit) {
            module = configure
        }

        fun postMultipart(
            path: String,
            headers: Map<String, String> = emptyMap(),
            parts: List<MultipartPart>,
            contentLengthOverride: Long? = null,
        ): SimpleHttpResponse {
            val boundary = "Boundary-${'$'}{System.currentTimeMillis()}"
            val body = buildMultipart(boundary, parts)
            val combinedHeaders = headers + mapOf("Content-Type" to "multipart/form-data; boundary=$boundary")
            val length = contentLengthOverride ?: body.size.toLong()
            return request("POST", path, combinedHeaders, body, length)
        }

        fun postJson(path: String, body: String, headers: Map<String, String> = emptyMap()): SimpleHttpResponse =
            request(
                method = "POST",
                path = path,
                headers = headers + mapOf("Content-Type" to "application/json"),
                body = body.toByteArray(),
                contentLength = body.toByteArray().size.toLong(),
            )

        private fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
            contentLength: Long?,
        ): SimpleHttpResponse {
            ensureStarted()
            val connection = java.net.URL("http://127.0.0.1:$port$path").openConnection() as java.net.HttpURLConnection
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                if (contentLength != null) {
                    connection.setFixedLengthStreamingMode(contentLength)
                }
                connection.doOutput = true
                try {
                    connection.outputStream.use { output ->
                        output.write(body)
                        output.flush()
                    }
                } catch (_: java.io.IOException) {
                    // Server may close the connection early when rejecting oversized payloads.
                }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val responseBody = stream?.bufferedReader()?.use { it.readText() } ?: ""
            connection.disconnect()
            return SimpleHttpResponse(HttpStatusCode.fromValue(status), responseBody)
        }

        private fun buildMultipart(boundary: String, parts: List<MultipartPart>): ByteArray {
            val output = ByteArrayOutputStream()
            for (part in parts) {
                output.write("--$boundary\r\n".toByteArray())
                val disposition = buildString {
                    append("Content-Disposition: form-data; name=\"")
                    append(part.name)
                    append("\"")
                    part.filename?.let {
                        append("; filename=\"")
                        append(it)
                        append("\"")
                    }
                }
                output.write("$disposition\r\n".toByteArray())
                part.contentType?.let { output.write("Content-Type: $it\r\n".toByteArray()) }
                part.headers.forEach { (headerName, headerValue) ->
                    if (!headerName.equals("Content-Type", ignoreCase = true)) {
                        output.write("$headerName: $headerValue\r\n".toByteArray())
                    }
                }
                output.write("\r\n".toByteArray())
                output.write(part.content)
                output.write("\r\n".toByteArray())
            }
            output.write("--$boundary--\r\n".toByteArray())
            return output.toByteArray()
        }

        private fun ensureStarted() {
            if (engine == null) {
                val config = module ?: {}
                val selectedPort = java.net.ServerSocket(0).use { it.localPort }
                val created = embeddedServer(Netty, host = "127.0.0.1", port = selectedPort, module = config)
                created.start(wait = false)
                engine = created
                port = selectedPort
            }
        }

        fun close() {
            engine?.stop(100, 1000)
            engine = null
        }
    }

    private class FakeDeps {
        var importResult: Result<CsvImportService.ImportReport> = Result.success(CsvImportService.ImportReport())
        var throwOnImport: Throwable? = null
        var maxBytes: Long = 1_048_576
        var allowed: Set<ContentType> = setOf(ContentType.Text.CSV, ContentType.Application.OctetStream)
        var downloadBytes: ByteArray = ByteArray(0)
        var downloadContentType: ContentType? = ContentType.Text.CSV
        var downloadError: Throwable? = null

        fun toDeps(): PortfolioImportDeps = PortfolioImportDeps(
            importCsv = { _, reader ->
                throwOnImport?.let { throw it }
                reader.use { it.readText() }
                importResult
            },
            uploadSettings = UploadSettings(
                csvMaxBytes = maxBytes,
                allowedContentTypes = allowed,
            ),
            downloadCsv = { _, _ ->
                downloadError?.let { throw it }
                RemoteCsv(contentType = downloadContentType, bytes = downloadBytes)
            },
        )
    }
}
