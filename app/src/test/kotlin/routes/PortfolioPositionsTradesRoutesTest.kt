package routes

import errors.installErrorPages
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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import portfolio.errors.PortfolioError
import portfolio.errors.PortfolioException
import portfolio.model.Money
import portfolio.model.PositionView
import portfolio.model.ValuationMethod
import routes.dto.PositionItemResponse
import routes.dto.TradesPageResponse
import security.JwtConfig
import security.JwtSupport
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PortfolioPositionsTradesRoutesTest {
    private val jwtConfig =
        JwtConfig(
            issuer = "newsbot",
            audience = "newsbot-clients",
            realm = "newsbot-api",
            secret = "test-secret",
            accessTtlMinutes = 60,
        )

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Test
    fun `requests without JWT are rejected`() =
        testApplication {
            val deps = FakeDeps()
            application { configureTestApp(deps.toDeps()) }

            val portfolioId = UUID.randomUUID()
            val positions = get("/api/portfolio/$portfolioId/positions")
            assertEquals(HttpStatusCode.Unauthorized, positions.status)

            val trades = get("/api/portfolio/$portfolioId/trades")
            assertEquals(HttpStatusCode.Unauthorized, trades.status)
        }

    @Test
    fun `invalid portfolio id returns 400`() =
        testApplication {
            val deps = FakeDeps()
            application { configureTestApp(deps.toDeps()) }

            val token = issueToken("1")
            val response =
                get(
                    path = "/api/portfolio/not-a-uuid/positions",
                    headers = authHeader(token),
                )
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val payload = HttpErrorResponse(response.body)
            assertEquals("bad_request", payload.error)
            assertTrue(payload.details?.any { it.contains("portfolioId", ignoreCase = true) } == true)
        }

    @Test
    fun `query validation errors return 400`() =
        testApplication {
            val deps = FakeDeps()
            application { configureTestApp(deps.toDeps()) }

            val token = issueToken("2")
            val portfolioId = UUID.randomUUID()

            fun request(path: String) = get(path, headers = authHeader(token))

            assertEquals(HttpStatusCode.BadRequest, request("/api/portfolio/$portfolioId/trades?limit=0").status)
            assertEquals(HttpStatusCode.BadRequest, request("/api/portfolio/$portfolioId/trades?limit=201").status)
            assertEquals(HttpStatusCode.BadRequest, request("/api/portfolio/$portfolioId/trades?offset=-1").status)

            val reversed = request("/api/portfolio/$portfolioId/trades?from=2024-05-10&to=2024-05-01")
            assertEquals(HttpStatusCode.BadRequest, reversed.status)
            val reversedPayload = HttpErrorResponse(reversed.body)
            assertTrue(reversedPayload.details?.any { it.contains("from must be on or before to") } == true)

            assertEquals(HttpStatusCode.BadRequest, request("/api/portfolio/$portfolioId/trades?side=HOLD").status)
        }

    @Test
    fun `positions endpoint returns data`() =
        testApplication {
            val deps = FakeDeps()
            val positionA =
                PositionView(
                    instrumentId = 101,
                    instrumentName = "AAA",
                    quantity = BigDecimal("10"),
                    valuation = Money.of(BigDecimal("2500"), "RUB"),
                    averageCost = Money.of(BigDecimal("240"), "RUB"),
                    valuationMethod = ValuationMethod.AVERAGE,
                    unrealizedPnl = Money.of(BigDecimal("100"), "RUB"),
                )
            val positionB =
                PositionView(
                    instrumentId = 99,
                    instrumentName = "BBB",
                    quantity = BigDecimal("5"),
                    valuation = Money.of(BigDecimal("1000"), "RUB"),
                    averageCost = Money.of(BigDecimal("180"), "RUB"),
                    valuationMethod = ValuationMethod.AVERAGE,
                    unrealizedPnl = Money.of(BigDecimal("50"), "RUB"),
                )
            deps.positionsResult = Result.success(listOf(positionA, positionB))

            application { configureTestApp(deps.toDeps()) }

            val token = issueToken("42")
            val response =
                get(
                    path = "/api/portfolio/${UUID.randomUUID()}/positions",
                    headers = authHeader(token),
                )
            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString<List<PositionItemResponse>>(response.body)
            assertEquals(2, payload.size)
            assertEquals(99, payload[0].instrumentId)
            assertEquals("5.00000000", payload[0].qty)
            assertEquals("200.00000000", payload[0].lastPrice.amount)
            assertEquals("50.00000000", payload[0].upl.amount)
        }

    @Test
    fun `trades endpoint returns page with filters`() =
        testApplication {
            val deps = FakeDeps()
            val tradeRecord =
                TradeRecord(
                    instrumentId = 123,
                    tradeDate = LocalDate.parse("2024-05-10"),
                    side = "BUY",
                    quantity = BigDecimal("3"),
                    price = Money.of(BigDecimal("150"), "RUB"),
                    notional = Money.of(BigDecimal("450"), "RUB"),
                    fee = Money.of(BigDecimal("5"), "RUB"),
                    tax = null,
                    extId = "t1",
                )
            deps.tradesResult = Result.success(TradesData(total = 15, items = listOf(tradeRecord)))

            application { configureTestApp(deps.toDeps()) }

            val token = issueToken("77")
            val portfolioId = UUID.randomUUID()
            val response =
                get(
                    path =
                    "/api/portfolio/$portfolioId/trades" +
                        "?limit=10&offset=5&from=2024-05-01&to=2024-05-31&side=BUY",
                    headers = authHeader(token),
                )
            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString<TradesPageResponse>(response.body)
            assertEquals(15, payload.total)
            assertEquals(10, payload.limit)
            assertEquals(5, payload.offset)
            assertEquals(1, payload.items.size)
            val item = payload.items.first()
            assertEquals("3.00000000", item.quantity)
            assertEquals("150.00000000", item.price.amount)
            assertEquals("450.00000000", item.notional.amount)
            assertEquals("t1", item.extId)

            val captured = deps.lastTradesQuery
            assertNotNull(captured)
            assertEquals(10, captured.limit)
            assertEquals(5, captured.offset)
            assertEquals(LocalDate.parse("2024-05-01"), captured.from)
            assertEquals(LocalDate.parse("2024-05-31"), captured.to)
            assertEquals("BUY", captured.side)
        }

    @Test
    fun `domain not found propagates as 404`() =
        testApplication {
            val deps = FakeDeps()
            deps.positionsResult = Result.failure(PortfolioException(PortfolioError.NotFound("No portfolio")))

            application { configureTestApp(deps.toDeps()) }

            val token = issueToken("88")
            val response =
                get(
                    path = "/api/portfolio/${UUID.randomUUID()}/positions",
                    headers = authHeader(token),
                )
            assertEquals(HttpStatusCode.NotFound, response.status)
            val payload = HttpErrorResponse(response.body)
            assertEquals("not_found", payload.error)
            assertEquals("No portfolio", payload.reason)
        }

    @Test
    fun `unexpected errors return 500`() =
        testApplication {
            val deps = FakeDeps()
            deps.positionsResult = Result.failure(RuntimeException("boom"))

            application { configureTestApp(deps.toDeps()) }

            val token = issueToken("99")
            val response =
                get(
                    path = "/api/portfolio/${UUID.randomUUID()}/positions",
                    headers = authHeader(token),
                )
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            val payload = HttpErrorResponse(response.body)
            assertEquals("internal", payload.error)
        }

    private fun Application.configureTestApp(deps: PortfolioPositionsTradesDeps) {
        installErrorPages()
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                },
            )
        }
        install(Authentication) {
            jwt("auth-jwt") {
                realm = jwtConfig.realm
                verifier(JwtSupport.verify(jwtConfig))
                validate { cred -> cred.payload.subject?.let { JWTPrincipal(cred.payload) } }
            }
        }
        attributes.put(PortfolioPositionsTradesDepsKey, deps)
        routing {
            authenticate("auth-jwt") {
                portfolioPositionsTradesRoutes()
            }
        }
    }

    private fun issueToken(subject: String): String = JwtSupport.issueToken(jwtConfig, subject)

    private fun authHeader(token: String): Map<String, String> = mapOf(HttpHeaders.Authorization to "Bearer $token")

    private fun testApplication(block: SimpleTestApplication.() -> Unit) {
        val app = SimpleTestApplication()
        try {
            app.block()
        } finally {
            app.close()
        }
    }

    private class FakeDeps {
        var positionsResult: Result<List<PositionView>> = Result.success(emptyList())
        var tradesResult: Result<TradesData> = Result.success(TradesData(total = 0, items = emptyList()))
        var lastTradesQuery: TradesQuery? = null

        fun toDeps(): PortfolioPositionsTradesDeps =
            PortfolioPositionsTradesDeps(
                listPositions = { positionsResult },
                listTrades = { _, query ->
                    lastTradesQuery = query
                    tradesResult
                },
            )
    }

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

        fun get(
            path: String,
            headers: Map<String, String> = emptyMap(),
        ): SimpleHttpResponse = request("GET", path, headers)

        private fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: String? = null,
        ): SimpleHttpResponse {
            ensureStarted()
            val target = URL("http://127.0.0.1:$port$path")
            val connection = (target.openConnection() as HttpURLConnection)
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { output ->
                    output.write(body.toByteArray(Charsets.UTF_8))
                }
            }
            val statusCode = connection.responseCode
            val stream = if (statusCode >= 400) connection.errorStream else connection.inputStream
            val responseBody = stream?.bufferedReader()?.use { it.readText() } ?: ""
            connection.disconnect()
            return SimpleHttpResponse(HttpStatusCode.fromValue(statusCode), responseBody)
        }

        private fun ensureStarted() {
            if (engine == null) {
                val config = module ?: {}
                val selectedPort = ServerSocket(0).use { it.localPort }
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
}
