package routes

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
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import errors.installErrorPages
import portfolio.errors.DomainResult
import portfolio.errors.PortfolioError
import portfolio.errors.PortfolioException
import portfolio.model.DateRange
import portfolio.model.Money
import portfolio.model.ValuationMethod
import portfolio.metrics.FxConversionResult
import portfolio.metrics.FxConverter
import portfolio.metrics.PortfolioMetricsService
import portfolio.service.FxRateRepository
import portfolio.service.FxRateService
import portfolio.service.PricingService
import portfolio.service.ReportService
import portfolio.service.ValuationService
import portfolio.service.CoingeckoPriceProvider
import portfolio.service.MoexPriceProvider
import routes.dto.PortfolioReportResponse
import routes.dto.PortfolioMetricsReportResponse
import routes.dto.ValuationDailyResponse
import security.JwtConfig
import security.JwtSupport

class PortfolioValuationReportRoutesTest {
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
    fun `revalue without JWT returns 401`() = testApplication {
        val deps = FakeDeps()
        application { configureTestApp(deps.toServices()) }

        val response = post("/api/portfolio/${UUID.randomUUID()}/revalue?date=2024-01-01")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `report without JWT returns 401`() = testApplication {
        val deps = FakeDeps()
        application { configureTestApp(deps.toServices()) }

        val response = get("/api/portfolio/${UUID.randomUUID()}/report?from=2024-01-01&to=2024-01-02")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `invalid portfolio id on revalue returns 400`() = testApplication {
        val deps = FakeDeps()
        application { configureTestApp(deps.toServices()) }

        val token = issueToken("101")
        val response = post(
            path = "/api/portfolio/not-a-uuid/revalue?date=2024-01-01",
            headers = authHeader(token),
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val payload = HttpErrorResponse(response.body)
        assertEquals("bad_request", payload.error)
        assertTrue(payload.details?.any { it.contains("portfolioId", ignoreCase = true) } == true)
    }

    @Test
    fun `missing date parameter returns 400`() = testApplication {
        val deps = FakeDeps()
        application { configureTestApp(deps.toServices()) }

        val token = issueToken("102")
        val response = post(
            path = "/api/portfolio/${UUID.randomUUID()}/revalue",
            headers = authHeader(token),
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val payload = HttpErrorResponse(response.body)
        assertEquals("bad_request", payload.error)
        assertTrue(payload.details?.any { it.contains("date", ignoreCase = true) } == true)
    }

    @Test
    fun `invalid date format on revalue returns 400`() = testApplication {
        val deps = FakeDeps()
        application { configureTestApp(deps.toServices()) }

        val token = issueToken("103")
        val response = post(
            path = "/api/portfolio/${UUID.randomUUID()}/revalue?date=2024/01/01",
            headers = authHeader(token),
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val payload = HttpErrorResponse(response.body)
        assertEquals("bad_request", payload.error)
        assertTrue(payload.details?.any { it.contains("date", ignoreCase = true) } == true)
    }

    @Test
    fun `invalid portfolio id on report returns 400`() = testApplication {
        val deps = FakeDeps()
        application { configureTestApp(deps.toServices()) }

        val token = issueToken("104")
        val response = get(
            path = "/api/portfolio/not-a-uuid/report?from=2024-01-01&to=2024-01-10",
            headers = authHeader(token),
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val payload = HttpErrorResponse(response.body)
        assertEquals("bad_request", payload.error)
        assertTrue(payload.details?.any { it.contains("portfolioId", ignoreCase = true) } == true)
    }

    @Test
    fun `missing from parameter on report returns 400`() = testApplication {
        val deps = FakeDeps()
        application { configureTestApp(deps.toServices()) }

        val token = issueToken("105")
        val response = get(
            path = "/api/portfolio/${UUID.randomUUID()}/report?to=2024-01-10",
            headers = authHeader(token),
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val payload = HttpErrorResponse(response.body)
        assertEquals("bad_request", payload.error)
        assertTrue(payload.details?.any { it.contains("from", ignoreCase = true) } == true)
    }

    @Test
    fun `invalid to parameter on report returns 400`() = testApplication {
        val deps = FakeDeps()
        application { configureTestApp(deps.toServices()) }

        val token = issueToken("106")
        val response = get(
            path = "/api/portfolio/${UUID.randomUUID()}/report?from=2024-01-01&to=2024/01/10",
            headers = authHeader(token),
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val payload = HttpErrorResponse(response.body)
        assertEquals("bad_request", payload.error)
        assertTrue(payload.details?.any { it.contains("to", ignoreCase = true) } == true)
    }

    @Test
    fun `range where to before from returns 400`() = testApplication {
        val deps = FakeDeps()
        application { configureTestApp(deps.toServices()) }

        val token = issueToken("107")
        val response = get(
            path = "/api/portfolio/${UUID.randomUUID()}/report?from=2024-02-10&to=2024-02-01",
            headers = authHeader(token),
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val payload = HttpErrorResponse(response.body)
        assertEquals("bad_request", payload.error)
        assertTrue(payload.details?.any { it.contains("from", ignoreCase = true) } == true)
    }

    @Test
    fun `revalue returns valuation response`() = testApplication {
        val deps = FakeDeps()
        val portfolioId = UUID.randomUUID()
        val valuationDate = LocalDate.parse("2024-04-18")
        deps.valuationStorage.listPositionsResult = DomainResult.success(emptyList())
        deps.valuationStorage.latestValuationResult = DomainResult.success(null)
        deps.valuationStorage.upsertResult = DomainResult.success(
            ValuationService.Storage.ValuationRecord(
                portfolioId = portfolioId,
                date = valuationDate,
                valueRub = BigDecimal("123456.00000000"),
                pnlDay = BigDecimal("123.00000000"),
                pnlTotal = BigDecimal("456.00000000"),
                drawdown = BigDecimal("-0.01000000"),
            ),
        )
        application { configureTestApp(deps.toServices()) }

        val token = issueToken("108")
        val response = post(
            path = "/api/portfolio/$portfolioId/revalue?date=2024-04-18",
            headers = authHeader(token),
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.decodeFromString<ValuationDailyResponse>(response.body)
        assertEquals("2024-04-18", payload.date)
        assertEquals("123456.00000000", payload.valueRub)
        assertEquals("123.00000000", payload.pnlDay)
        assertEquals("456.00000000", payload.pnlTotal)
        assertEquals("-0.01000000", payload.drawdown)
    }

    @Test
    fun `report returns portfolio report`() = testApplication {
        val deps = FakeDeps()
        val portfolioId = UUID.randomUUID()
        deps.reportStorage.valuationMethodResult = DomainResult.success(ValuationMethod.AVERAGE)
        deps.reportStorage.valuationsResult = DomainResult.success(
            listOf(
                ReportService.Storage.ValuationRecord(
                    date = LocalDate.parse("2024-03-01"),
                    value = Money.of(BigDecimal("1000.00000000"), "RUB"),
                    pnlDay = Money.of(BigDecimal("100.00000000"), "RUB"),
                    pnlTotal = Money.of(BigDecimal("100.00000000"), "RUB"),
                    drawdown = BigDecimal("-0.05000000"),
                ),
                ReportService.Storage.ValuationRecord(
                    date = LocalDate.parse("2024-03-02"),
                    value = Money.of(BigDecimal("1200.00000000"), "RUB"),
                    pnlDay = Money.of(BigDecimal("50.00000000"), "RUB"),
                    pnlTotal = Money.of(BigDecimal("150.00000000"), "RUB"),
                    drawdown = BigDecimal("-0.02000000"),
                ),
            ),
        )
        deps.reportStorage.baselineResult = DomainResult.success(null)
        deps.reportStorage.realizedResult = DomainResult.success(emptyList())
        deps.reportStorage.holdingsResult = DomainResult.success(emptyList())
        application { configureTestApp(deps.toServices()) }

        val token = issueToken("109")
        val response = get(
            path = "/api/portfolio/$portfolioId/report?from=2024-03-01&to=2024-03-02",
            headers = authHeader(token),
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.decodeFromString<PortfolioReportResponse>(response.body)
        assertEquals("2024-03-01", payload.from)
        assertEquals("2024-03-02", payload.to)
        assertEquals("150.00000000", payload.totalPnl)
        assertEquals("75.00000000", payload.avgDailyPnl)
        assertEquals("-0.05000000", payload.maxDrawdown)
        assertTrue(payload.topPositions.isEmpty())
    }

    @Test
    fun `metrics report returns json`() = testApplication {
        val deps = FakeDeps()
        val portfolioId = UUID.randomUUID()
        deps.metricsStorage.valuationsResult = DomainResult.success(
            listOf(
                PortfolioMetricsService.Storage.ValuationRecord(
                    date = LocalDate.parse("2024-01-01"),
                    valueRub = BigDecimal("100.00000000"),
                ),
                PortfolioMetricsService.Storage.ValuationRecord(
                    date = LocalDate.parse("2024-01-02"),
                    valueRub = BigDecimal("120.00000000"),
                ),
            ),
        )
        deps.metricsStorage.tradesResult = DomainResult.success(emptyList())
        application { configureTestApp(deps.toServices()) }

        val token = issueToken("114")
        val response = get(
            path = "/api/portfolio/$portfolioId/report?period=daily&base=RUB",
            headers = authHeader(token),
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.decodeFromString<PortfolioMetricsReportResponse>(response.body)
        assertEquals(portfolioId.toString(), payload.portfolioId)
        assertEquals("RUB", payload.base)
        assertEquals("daily", payload.period)
        assertEquals(false, payload.delayed)
        assertEquals(2, payload.series.size)
    }

    @Test
    fun `metrics report returns csv`() = testApplication {
        val deps = FakeDeps()
        val portfolioId = UUID.randomUUID()
        deps.metricsStorage.valuationsResult = DomainResult.success(
            listOf(
                PortfolioMetricsService.Storage.ValuationRecord(
                    date = LocalDate.parse("2024-01-01"),
                    valueRub = BigDecimal("100.00000000"),
                ),
                PortfolioMetricsService.Storage.ValuationRecord(
                    date = LocalDate.parse("2024-01-02"),
                    valueRub = BigDecimal("120.00000000"),
                ),
            ),
        )
        deps.metricsStorage.tradesResult = DomainResult.success(emptyList())
        application { configureTestApp(deps.toServices()) }

        val token = issueToken("115")
        val response = get(
            path = "/api/portfolio/$portfolioId/report?period=daily&format=csv",
            headers = authHeader(token),
        )
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.body.startsWith("date,valuation,cashflow,pnlDaily,pnlTotal"))
        assertTrue(response.body.contains("2024-01-02"))
    }

    @Test
    fun `metrics report returns 502 on fx errors`() = testApplication {
        val deps = FakeDeps()
        val portfolioId = UUID.randomUUID()
        deps.metricsStorage.valuationsResult = DomainResult.success(
            listOf(
                PortfolioMetricsService.Storage.ValuationRecord(
                    date = LocalDate.parse("2024-01-01"),
                    valueRub = BigDecimal("100.00000000"),
                ),
            ),
        )
        deps.metricsStorage.tradesResult = DomainResult.success(emptyList())
        deps.fxConverter.failWith = PortfolioException(
            PortfolioError.FxRateNotFound("USD", LocalDate.parse("2024-01-01"), LocalDate.parse("2024-01-01")),
        )
        application { configureTestApp(deps.toServices()) }

        val token = issueToken("116")
        val response = get(
            path = "/api/portfolio/$portfolioId/report?period=daily&base=USD",
            headers = authHeader(token),
        )
        assertEquals(HttpStatusCode.BadGateway, response.status)
        val payload = HttpErrorResponse(response.body)
        assertEquals("fx_rate_unavailable", payload.error)
    }

    @Test
    fun `metrics report rejects invalid period`() = testApplication {
        val deps = FakeDeps()
        application { configureTestApp(deps.toServices()) }

        val token = issueToken("117")
        val response = get(
            path = "/api/portfolio/${UUID.randomUUID()}/report?period=yearly",
            headers = authHeader(token),
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `revalue returns 404 when portfolio missing`() = testApplication {
        val deps = FakeDeps()
        deps.valuationStorage.listPositionsResult = DomainResult.failure(
            PortfolioException(PortfolioError.NotFound("portfolio missing")),
        )
        application { configureTestApp(deps.toServices()) }

        val token = issueToken("110")
        val response = post(
            path = "/api/portfolio/${UUID.randomUUID()}/revalue?date=2024-01-05",
            headers = authHeader(token),
        )
        assertEquals(HttpStatusCode.NotFound, response.status)
        val payload = HttpErrorResponse(response.body)
        assertEquals("not_found", payload.error)
    }

    @Test
    fun `report returns 404 when portfolio missing`() = testApplication {
        val deps = FakeDeps()
        deps.reportStorage.valuationMethodResult = DomainResult.failure(
            PortfolioException(PortfolioError.NotFound("portfolio missing")),
        )
        application { configureTestApp(deps.toServices()) }

        val token = issueToken("111")
        val response = get(
            path = "/api/portfolio/${UUID.randomUUID()}/report?from=2024-01-01&to=2024-01-10",
            headers = authHeader(token),
        )
        assertEquals(HttpStatusCode.NotFound, response.status)
        val payload = HttpErrorResponse(response.body)
        assertEquals("not_found", payload.error)
    }

    @Test
    fun `revalue unexpected error returns 500`() = testApplication {
        val deps = FakeDeps()
        deps.valuationStorage.listPositionsResult = DomainResult.failure(RuntimeException("boom"))
        application { configureTestApp(deps.toServices()) }

        val token = issueToken("112")
        val response = post(
            path = "/api/portfolio/${UUID.randomUUID()}/revalue?date=2024-01-05",
            headers = authHeader(token),
        )
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val payload = HttpErrorResponse(response.body)
        assertEquals("internal", payload.error)
    }

    @Test
    fun `report unexpected error returns 500`() = testApplication {
        val deps = FakeDeps()
        deps.reportStorage.valuationsResult = DomainResult.failure(RuntimeException("boom"))
        application { configureTestApp(deps.toServices()) }

        val token = issueToken("113")
        val response = get(
            path = "/api/portfolio/${UUID.randomUUID()}/report?from=2024-01-01&to=2024-01-02",
            headers = authHeader(token),
        )
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val payload = HttpErrorResponse(response.body)
        assertEquals("internal", payload.error)
    }

    private fun testApplication(block: SimpleTestApplication.() -> Unit) {
        val app = SimpleTestApplication()
        try {
            app.block()
        } finally {
            app.close()
        }
    }

    private fun Application.configureTestApp(services: PortfolioValuationReportServices) {
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
        attributes.put(Services.Key, services)
        routing {
            authenticate("auth-jwt") {
                portfolioValuationReportRoutes()
            }
        }
    }

    private fun issueToken(subject: String): String = JwtSupport.issueToken(jwtConfig, subject)

    private fun authHeader(token: String): Map<String, String> =
        mapOf(HttpHeaders.Authorization to "Bearer $token")

    private class SimpleHttpResponse(
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

        fun get(path: String, headers: Map<String, String> = emptyMap()): SimpleHttpResponse =
            request("GET", path, headers)

        fun post(path: String, headers: Map<String, String> = emptyMap()): SimpleHttpResponse =
            request("POST", path, headers)

        private fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
        ): SimpleHttpResponse {
            ensureStarted()
            val connection = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection)
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (method == "POST") {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(0)
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            connection.disconnect()
            return SimpleHttpResponse(HttpStatusCode.fromValue(status), body)
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

    private class FakeDeps {
        val valuationStorage = FakeValuationStorage()
        val reportStorage = FakeReportStorage()
        val metricsStorage = FakeMetricsStorage()
        val fxConverter = FakeFxConverter()
        private val fxRepository = object : FxRateRepository {
            override suspend fun findOnOrBefore(ccy: String, timestamp: Instant) = null
        }
        private val fxService = FxRateService(fxRepository)
        private val moexProvider = object : MoexPriceProvider {
            override suspend fun closePrice(instrumentId: Long, on: LocalDate) = DomainResult.success<Money?>(null)
            override suspend fun lastPrice(instrumentId: Long, on: LocalDate) = DomainResult.success<Money?>(null)
        }
        private val coingeckoProvider = object : CoingeckoPriceProvider {
            override suspend fun closePrice(instrumentId: Long, on: LocalDate) = DomainResult.success<Money?>(null)
            override suspend fun lastPrice(instrumentId: Long, on: LocalDate) = DomainResult.success<Money?>(null)
        }
        private val pricingService = PricingService(moexProvider, coingeckoProvider, fxService)
        val valuationService = ValuationService(
            storage = valuationStorage,
            pricingService = pricingService,
            fxRateService = fxService,
        )
        val reportService = ReportService(reportStorage)
        val metricsService = PortfolioMetricsService(metricsStorage, fxConverter)

        fun toServices(): PortfolioValuationReportServices =
            PortfolioValuationReportServices(
                valuationService = valuationService,
                reportService = reportService,
                metricsService = metricsService,
                loadPortfolio = { id ->
                    repo.model.PortfolioEntity(
                        portfolioId = id,
                        userId = 1L,
                        name = "Test",
                        baseCurrency = "RUB",
                        isActive = true,
                        createdAt = Instant.EPOCH,
                    )
                },
            )
    }

    private class FakeValuationStorage : ValuationService.Storage {
        var listPositionsResult: DomainResult<List<ValuationService.Storage.PositionSnapshot>> =
            DomainResult.success(emptyList())
        var latestValuationResult: DomainResult<ValuationService.Storage.ValuationRecord?> =
            DomainResult.success(null)
        var upsertResult: DomainResult<ValuationService.Storage.ValuationRecord> =
            DomainResult.success(
                ValuationService.Storage.ValuationRecord(
                    portfolioId = UUID.randomUUID(),
                    date = LocalDate.EPOCH,
                    valueRub = BigDecimal.ZERO,
                    pnlDay = BigDecimal.ZERO,
                    pnlTotal = BigDecimal.ZERO,
                    drawdown = BigDecimal.ZERO,
                ),
            )

        override suspend fun listPositions(portfolioId: UUID) = listPositionsResult

        override suspend fun latestValuationBefore(
            portfolioId: UUID,
            date: LocalDate,
        ) = latestValuationResult

        override suspend fun upsertValuation(
            record: ValuationService.Storage.ValuationRecord,
        ): DomainResult<ValuationService.Storage.ValuationRecord> = upsertResult
    }

    private class FakeReportStorage : ReportService.Storage {
        var valuationMethodResult: DomainResult<ValuationMethod> = DomainResult.success(ValuationMethod.AVERAGE)
        var valuationsResult: DomainResult<List<ReportService.Storage.ValuationRecord>> =
            DomainResult.success(emptyList())
        var baselineResult: DomainResult<ReportService.Storage.ValuationRecord?> =
            DomainResult.success(null)
        var realizedResult: DomainResult<List<ReportService.Storage.RealizedTrade>> =
            DomainResult.success(emptyList())
        var holdingsResult: DomainResult<List<ReportService.Storage.Holding>> =
            DomainResult.success(emptyList())

        override suspend fun valuationMethod(portfolioId: UUID) = valuationMethodResult

        override suspend fun listValuations(
            portfolioId: UUID,
            range: DateRange,
        ) = valuationsResult

        override suspend fun latestValuationBefore(
            portfolioId: UUID,
            date: LocalDate,
        ) = baselineResult

        override suspend fun listRealizedPnl(
            portfolioId: UUID,
            range: DateRange,
        ) = realizedResult

        override suspend fun listHoldings(
            portfolioId: UUID,
            asOf: LocalDate,
        ) = holdingsResult
    }

    private class FakeMetricsStorage : PortfolioMetricsService.Storage {
        var valuationsResult: DomainResult<List<PortfolioMetricsService.Storage.ValuationRecord>> =
            DomainResult.success(emptyList())
        var tradesResult: DomainResult<List<PortfolioMetricsService.Storage.TradeRecord>> =
            DomainResult.success(emptyList())

        override suspend fun listValuations(portfolioId: UUID) = valuationsResult

        override suspend fun listTrades(portfolioId: UUID) = tradesResult
    }

    private class FakeFxConverter : FxConverter {
        var failWith: Throwable? = null

        override suspend fun convert(
            amount: BigDecimal,
            currency: String,
            date: LocalDate,
            baseCurrency: String,
        ): FxConversionResult {
            failWith?.let { throw it }
            return FxConversionResult(amount, delayed = false)
        }
    }
}
