package routes

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
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
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import portfolio.errors.DomainResult
import portfolio.model.Money
import portfolio.service.CoingeckoPriceProvider
import portfolio.service.FxRateRepository
import portfolio.service.FxRateService
import portfolio.service.MoexPriceProvider
import portfolio.service.PricingService
import routes.dto.MoneyDto
import routes.quotes.Services

class QuotesRoutesTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `missing instrument id returns 400`() = testApplication {
        val fixture = pricingFixture()
        application { configureTestApp(fixture.service) }

        val response = get("/api/quotes/closeOrLast")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val payload = json.decodeFromString<HttpErrorResponse>(response.body)
        assertEquals("bad_request", payload.error)
        assertEquals(listOf("instrumentId invalid"), payload.details)
        assertTrue(fixture.provider.closeCalls.isEmpty())
    }

    @Test
    fun `non positive instrument id returns 400`() = testApplication {
        val fixture = pricingFixture()
        application { configureTestApp(fixture.service) }

        val response = get("/api/quotes/closeOrLast?instrumentId=0")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val payload = json.decodeFromString<HttpErrorResponse>(response.body)
        assertEquals("bad_request", payload.error)
        assertEquals(listOf("instrumentId invalid"), payload.details)
        assertTrue(fixture.provider.closeCalls.isEmpty())
    }

    @Test
    fun `invalid date returns 400`() = testApplication {
        val fixture = pricingFixture()
        application { configureTestApp(fixture.service) }

        val response = get("/api/quotes/closeOrLast?instrumentId=12&date=2024-02-30")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val payload = json.decodeFromString<HttpErrorResponse>(response.body)
        assertEquals("bad_request", payload.error)
        assertEquals(listOf("date invalid"), payload.details)
        assertTrue(fixture.provider.closeCalls.isEmpty())
    }

    @Test
    fun `valid instrument and date returns quote`() = testApplication {
        val fixture = pricingFixture().apply {
            provider.closeHandler = { _, _ ->
                DomainResult.success(Money.of(BigDecimal("123.45"), "RUB"))
            }
        }
        application { configureTestApp(fixture.service) }

        val response = get("/api/quotes/closeOrLast?instrumentId=42&date=2024-03-15")
        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.decodeFromString<MoneyDto>(response.body)
        assertEquals("123.45000000", payload.amount)
        assertEquals("RUB", payload.ccy)
        val call = fixture.provider.closeCalls.single()
        assertEquals(42L, call.first)
        assertEquals(LocalDate.parse("2024-03-15"), call.second)
    }

    @Test
    fun `valid instrument without date defaults to today`() = testApplication {
        val fixture = pricingFixture().apply {
            provider.closeHandler = { _, _ ->
                DomainResult.success(Money.of(BigDecimal("50"), "RUB"))
            }
        }
        application { configureTestApp(fixture.service) }

        val response = get("/api/quotes/closeOrLast?instrumentId=7")
        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.decodeFromString<MoneyDto>(response.body)
        assertEquals("50.00000000", payload.amount)
        assertEquals("RUB", payload.ccy)
        val call = fixture.provider.closeCalls.single()
        assertEquals(7L, call.first)
        assertEquals(LocalDate.now(ZoneOffset.UTC), call.second)
    }

    @Test
    fun `missing price returns 404`() = testApplication {
        val fixture = pricingFixture().apply {
            provider.closeHandler = { _, _ -> DomainResult.success(null) }
            provider.lastHandler = { _, _ -> DomainResult.success(null) }
        }
        application { configureTestApp(fixture.service) }

        val response = get("/api/quotes/closeOrLast?instrumentId=99&date=2024-01-05")
        assertEquals(HttpStatusCode.NotFound, response.status)
        val payload = json.decodeFromString<HttpErrorResponse>(response.body)
        assertEquals("not_found", payload.error)
        assertEquals("price_not_available", payload.reason)
    }

    @Test
    fun `pricing failure returns 500`() = testApplication {
        val fixture = pricingFixture().apply {
            provider.closeHandler = { _, _ ->
                DomainResult.failure(RuntimeException("boom"))
            }
        }
        application { configureTestApp(fixture.service) }

        val response = get("/api/quotes/closeOrLast?instrumentId=15&date=2024-04-01")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val payload = json.decodeFromString<HttpErrorResponse>(response.body)
        assertEquals("internal", payload.error)
    }

    private fun Application.configureTestApp(pricingService: PricingService) {
        install(ContentNegotiation) {
            json(json)
        }
        attributes.put(Services.Key, pricingService)
        routing {
            quotesRoutes()
        }
    }

    private fun testApplication(block: SimpleTestApplication.() -> Unit) {
        val app = SimpleTestApplication()
        try {
            app.block()
        } finally {
            app.close()
        }
    }

    private data class PricingFixture(
        val service: PricingService,
        val provider: FakePriceProvider,
    )

    private fun pricingFixture(): PricingFixture {
        val provider = FakePriceProvider()
        val fxService = FxRateService(
            object : FxRateRepository {
                override suspend fun findOnOrBefore(ccy: String, timestamp: Instant) = null
            },
        )
        val service = PricingService(
            moexProvider = provider,
            coingeckoProvider = provider,
            fxRateService = fxService,
            config = PricingService.Config(baseCurrency = "RUB"),
        )
        return PricingFixture(service, provider)
    }

    private class FakePriceProvider : MoexPriceProvider, CoingeckoPriceProvider {
        var closeHandler: suspend (Long, LocalDate) -> DomainResult<Money?> = { _, _ -> DomainResult.success(null) }
        var lastHandler: suspend (Long, LocalDate) -> DomainResult<Money?> = { _, _ -> DomainResult.success(null) }
        val closeCalls = mutableListOf<Pair<Long, LocalDate>>()
        val lastCalls = mutableListOf<Pair<Long, LocalDate>>()

        override suspend fun closePrice(instrumentId: Long, on: LocalDate): DomainResult<Money?> {
            closeCalls += instrumentId to on
            return closeHandler(instrumentId, on)
        }

        override suspend fun lastPrice(instrumentId: Long, on: LocalDate): DomainResult<Money?> {
            lastCalls += instrumentId to on
            return lastHandler(instrumentId, on)
        }
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

        fun get(path: String, headers: Map<String, String> = emptyMap()): SimpleHttpResponse =
            request("GET", path, headers)

        private fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: String? = null,
        ): SimpleHttpResponse {
            ensureStarted()
            val target = URL("http://127.0.0.1:$port$path")
            val connection = target.openConnection() as HttpURLConnection
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
