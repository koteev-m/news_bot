package routes

import errors.installErrorPages
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import portfolio.errors.DomainResult
import portfolio.errors.PortfolioError
import portfolio.errors.PortfolioException
import portfolio.model.Money
import portfolio.service.CoingeckoPriceProvider
import portfolio.service.FxRateRepository
import portfolio.service.FxRateService
import portfolio.service.MoexPriceProvider
import portfolio.service.PricingService
import routes.dto.MoneyDto
import routes.quotes.Services
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuotesRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `missing instrument id returns 400`() =
        testApplication {
            val fixture = pricingFixture()
            application { configureTestApp(fixture.service) }

            val response = client.get("/api/quotes/closeOrLast")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val payload = HttpErrorResponse(response.bodyAsText())
            assertEquals("bad_request", payload.error)
            assertEquals(listOf("instrumentId invalid"), payload.details)
            assertTrue(fixture.provider.closeCalls.isEmpty())
        }

    @Test
    fun `non positive instrument id returns 400`() =
        testApplication {
            val fixture = pricingFixture()
            application { configureTestApp(fixture.service) }

            val response = client.get("/api/quotes/closeOrLast?instrumentId=0")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val payload = HttpErrorResponse(response.bodyAsText())
            assertEquals("bad_request", payload.error)
            assertEquals(listOf("instrumentId invalid"), payload.details)
            assertTrue(fixture.provider.closeCalls.isEmpty())
        }

    @Test
    fun `invalid date returns 400`() =
        testApplication {
            val fixture = pricingFixture()
            application { configureTestApp(fixture.service) }

            val response = client.get("/api/quotes/closeOrLast?instrumentId=42&date=2024-02-30")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val payload = HttpErrorResponse(response.bodyAsText())
            assertEquals("bad_request", payload.error)
            assertEquals(listOf("date invalid"), payload.details)
            assertTrue(fixture.provider.closeCalls.isEmpty())
        }

    @Test
    fun `valid instrument and date returns quote`() =
        testApplication {
            val fixture =
                pricingFixture().apply {
                    provider.closeHandler = { _, _ ->
                        DomainResult.success(Money.of(BigDecimal("123.45"), "RUB"))
                    }
                }
            application { configureTestApp(fixture.service) }

            val response = client.get("/api/quotes/closeOrLast?instrumentId=7&date=2024-03-15")

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString<MoneyDto>(response.bodyAsText())
            assertEquals("123.45000000", payload.amount)
            assertEquals("RUB", payload.ccy)
            val call = fixture.provider.closeCalls.single()
            assertEquals(7L, call.first)
            assertEquals(LocalDate.parse("2024-03-15"), call.second)
        }

    @Test
    fun `valid instrument without date defaults to today`() =
        testApplication {
            val fixture =
                pricingFixture().apply {
                    provider.closeHandler = { _, _ ->
                        DomainResult.success(Money.of(BigDecimal("50"), "RUB"))
                    }
                }
            application { configureTestApp(fixture.service) }

            val response = client.get("/api/quotes/closeOrLast?instrumentId=15")

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString<MoneyDto>(response.bodyAsText())
            assertEquals("50.00000000", payload.amount)
            assertEquals("RUB", payload.ccy)
            val call = fixture.provider.closeCalls.single()
            assertEquals(15L, call.first)
            assertEquals(LocalDate.now(ZoneOffset.UTC), call.second)
        }

    @Test
    fun `missing price returns 404`() =
        testApplication {
            val fixture =
                pricingFixture().apply {
                    provider.closeHandler = { _, _ -> DomainResult.success(null) }
                    provider.lastHandler = { _, _ -> DomainResult.success(null) }
                }
            application { configureTestApp(fixture.service) }

            val response = client.get("/api/quotes/closeOrLast?instrumentId=99&date=2024-01-05")

            assertEquals(HttpStatusCode.NotFound, response.status)
            val payload = HttpErrorResponse(response.bodyAsText())
            assertEquals("not_found", payload.error)
            assertEquals("price_not_available", payload.reason)
        }

    @Test
    fun `pricing failure returns 500`() =
        testApplication {
            val fixture =
                pricingFixture().apply {
                    provider.closeHandler = { _, _ ->
                        DomainResult.failure(RuntimeException("boom"))
                    }
                }
            application { configureTestApp(fixture.service) }

            val response = client.get("/api/quotes/closeOrLast?instrumentId=21&date=2024-04-01")

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            val payload = HttpErrorResponse(response.bodyAsText())
            assertEquals("internal", payload.error)
        }

    @Test
    fun `domain validation returns 400`() =
        testApplication {
            val fixture =
                pricingFixture().apply {
                    provider.closeHandler = { _, _ ->
                        DomainResult.failure(PortfolioException(PortfolioError.Validation("instrument invalid")))
                    }
                }
            application { configureTestApp(fixture.service) }

            val response = client.get("/api/quotes/closeOrLast?instrumentId=34&date=2024-05-20")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val payload = HttpErrorResponse(response.bodyAsText())
            assertEquals("bad_request", payload.error)
            assertEquals(listOf("instrument invalid"), payload.details)
        }

    private fun Application.configureTestApp(pricingService: PricingService) {
        installErrorPages()
        install(ContentNegotiation) {
            json(json)
        }
        attributes.put(Services.Key, pricingService)
        routing {
            quotesRoutes()
        }
    }

    private data class PricingFixture(
        val service: PricingService,
        val provider: FakePriceProvider,
    )

    private fun pricingFixture(): PricingFixture {
        val provider = FakePriceProvider()
        val fxService =
            FxRateService(
                object : FxRateRepository {
                    override suspend fun findOnOrBefore(
                        ccy: String,
                        timestamp: Instant,
                    ) = null
                },
            )
        val service =
            PricingService(
                moexProvider = provider,
                coingeckoProvider = provider,
                fxRateService = fxService,
                config = PricingService.Config(baseCurrency = "RUB"),
            )
        return PricingFixture(service, provider)
    }

    private class FakePriceProvider :
        MoexPriceProvider,
        CoingeckoPriceProvider {
        var closeHandler: suspend (Long, LocalDate) -> DomainResult<Money?> = { _, _ -> DomainResult.success(null) }
        var lastHandler: suspend (Long, LocalDate) -> DomainResult<Money?> = { _, _ -> DomainResult.success(null) }
        val closeCalls = mutableListOf<Pair<Long, LocalDate>>()
        val lastCalls = mutableListOf<Pair<Long, LocalDate>>()

        override suspend fun closePrice(
            instrumentId: Long,
            on: LocalDate,
        ): DomainResult<Money?> {
            closeCalls += instrumentId to on
            return closeHandler(instrumentId, on)
        }

        override suspend fun lastPrice(
            instrumentId: Long,
            on: LocalDate,
        ): DomainResult<Money?> {
            lastCalls += instrumentId to on
            return lastHandler(instrumentId, on)
        }
    }
}
