package portfolio

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import model.FxRate
import portfolio.errors.DomainResult
import portfolio.errors.PortfolioError
import portfolio.errors.PortfolioException
import portfolio.model.Money
import portfolio.service.CoingeckoPriceProvider
import portfolio.service.FxRateRepository
import portfolio.service.FxRateService
import portfolio.service.MoexPriceProvider
import portfolio.service.PricingService
import portfolio.service.PriceProvider

class PricingServiceTest {
    private val tradeDate = LocalDate.of(2024, 2, 1)

    @Test
    fun `returns close price from moex`() = runBlocking {
        val moex = StubMoexProvider().apply {
            closeHandler = { id, date ->
                assertEquals(1L, id)
                assertEquals(tradeDate, date)
                DomainResult.success(Money.of(BigDecimal("10"), "USD"))
            }
            lastHandler = { _, _ ->
                fail("Should not request last price when close is available")
            }
        }
        val coingecko = StubCoingeckoProvider().apply {
            closeHandler = { _, _ ->
                fail("Should not call secondary provider when primary succeeds")
            }
            lastHandler = { _, _ ->
                fail("Should not call secondary provider when primary succeeds")
            }
        }
        val fxService = FxRateService(
            FakeFxRateRepository(
                mapOf(
                    "USD" to listOf(rate("USD", tradeDate, BigDecimal("92.50"))),
                ),
            ),
        )
        val service = PricingService(moex, coingecko, fxService)

        val result = service.closeOrLast(1L, tradeDate)

        assertTrue(result.isSuccess)
        assertEquals(Money.of(BigDecimal("925"), "RUB"), result.getOrNull())
        assertEquals(1, moex.closeCalls)
        assertEquals(0, moex.lastCalls)
    }

    @Test
    fun `falls back to last price when close missing`() = runBlocking {
        val moex = StubMoexProvider().apply {
            closeHandler = { _, _ -> DomainResult.success(null) }
            lastHandler = { id, date ->
                assertEquals(2L, id)
                assertEquals(tradeDate, date)
                DomainResult.success(Money.of(BigDecimal("200"), "RUB"))
            }
        }
        val coingecko = StubCoingeckoProvider().apply {
            closeHandler = { _, _ -> DomainResult.success(null) }
            lastHandler = { _, _ ->
                fail("Should not request Coingecko last when MOEX provided one")
            }
        }
        val fxService = FxRateService(FakeFxRateRepository(emptyMap()))
        val service = PricingService(moex, coingecko, fxService)

        val result = service.closeOrLast(2L, tradeDate)

        assertTrue(result.isSuccess)
        assertEquals(Money.of(BigDecimal("200"), "RUB"), result.getOrNull())
        assertEquals(1, moex.closeCalls)
        assertEquals(1, coingecko.closeCalls)
        assertEquals(1, moex.lastCalls)
        assertEquals(0, coingecko.lastCalls)
    }

    @Test
    fun `honors fallback configuration`() = runBlocking {
        val moex = StubMoexProvider().apply {
            closeHandler = { _, _ -> DomainResult.success(null) }
            lastHandler = { _, _ -> DomainResult.success(null) }
        }
        val coingecko = StubCoingeckoProvider().apply {
            closeHandler = { id, date ->
                assertEquals(3L, id)
                assertEquals(tradeDate, date)
                DomainResult.success(Money.of(BigDecimal("150"), "USD"))
            }
            lastHandler = { _, _ ->
                fail("Fallback disabled, last price should not be requested")
            }
        }
        val fxService = FxRateService(
            FakeFxRateRepository(
                mapOf(
                    "USD" to listOf(rate("USD", tradeDate, BigDecimal("90"))),
                ),
            ),
        )
        val service = PricingService(
            moex,
            coingecko,
            fxService,
            PricingService.Config(fallbackToLast = false),
        )

        val result = service.closeOrLast(3L, tradeDate)

        assertTrue(result.isSuccess)
        assertEquals(Money.of(BigDecimal("13500"), "RUB"), result.getOrNull())
        assertEquals(1, moex.closeCalls)
        assertEquals(0, moex.lastCalls)
        assertEquals(1, coingecko.closeCalls)
        assertEquals(0, coingecko.lastCalls)
    }

    @Test
    fun `uses close as secondary when last preferred`() = runBlocking {
        val moex = StubMoexProvider().apply {
            closeHandler = { _, _ ->
                DomainResult.success(Money.of(BigDecimal("5"), "EUR"))
            }
            lastHandler = { _, _ -> DomainResult.success(null) }
        }
        val coingecko = StubCoingeckoProvider().apply {
            closeHandler = { _, _ -> DomainResult.success(null) }
            lastHandler = { _, _ -> DomainResult.success(null) }
        }
        val fxService = FxRateService(
            FakeFxRateRepository(
                mapOf(
                    "EUR" to listOf(rate("EUR", tradeDate, BigDecimal("100"))),
                ),
            ),
        )
        val service = PricingService(
            moex,
            coingecko,
            fxService,
            PricingService.Config(preferClosePrice = false),
        )

        val result = service.closeOrLast(4L, tradeDate)

        assertTrue(result.isSuccess)
        assertEquals(Money.of(BigDecimal("500"), "RUB"), result.getOrNull())
        assertEquals(1, moex.lastCalls)
        assertEquals(1, moex.closeCalls)
    }

    @Test
    fun `returns not found when providers lack data`() = runBlocking {
        val moex = StubMoexProvider()
        val coingecko = StubCoingeckoProvider()
        val fxService = FxRateService(FakeFxRateRepository(emptyMap()))
        val service = PricingService(moex, coingecko, fxService)

        val result = service.closeOrLast(5L, tradeDate)

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertIs<PortfolioException>(exception)
        assertIs<PortfolioError.NotFound>(exception.error)
    }

    @Test
    fun `propagates provider failure`() = runBlocking {
        val error = PortfolioError.External("upstream down")
        val moex = StubMoexProvider().apply {
            closeHandler = { _, _ -> DomainResult.failure(PortfolioException(error)) }
        }
        val coingecko = StubCoingeckoProvider()
        val fxService = FxRateService(FakeFxRateRepository(emptyMap()))
        val service = PricingService(moex, coingecko, fxService)

        val result = service.closeOrLast(6L, tradeDate)

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertIs<PortfolioException>(exception)
        assertEquals(error, exception.error)
    }

    @Test
    fun `propagates fx conversion failure`() = runBlocking {
        val moex = StubMoexProvider().apply {
            closeHandler = { _, _ -> DomainResult.success(Money.of(BigDecimal("1"), "CHF")) }
        }
        val coingecko = StubCoingeckoProvider()
        val fxService = FxRateService(FakeFxRateRepository(emptyMap()))
        val service = PricingService(moex, coingecko, fxService)

        val result = service.closeOrLast(7L, tradeDate)

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertIs<PortfolioException>(exception)
        assertIs<PortfolioError.NotFound>(exception.error)
    }

    private open class StubPriceProvider : PriceProvider {
        var closeCalls: Int = 0
        var lastCalls: Int = 0

        var closeHandler: suspend (Long, LocalDate) -> DomainResult<Money?> = { _, _ -> DomainResult.success(null) }
        var lastHandler: suspend (Long, LocalDate) -> DomainResult<Money?> = { _, _ -> DomainResult.success(null) }

        override suspend fun closePrice(instrumentId: Long, on: LocalDate): DomainResult<Money?> {
            closeCalls += 1
            return closeHandler(instrumentId, on)
        }

        override suspend fun lastPrice(instrumentId: Long, on: LocalDate): DomainResult<Money?> {
            lastCalls += 1
            return lastHandler(instrumentId, on)
        }
    }

    private class StubMoexProvider : StubPriceProvider(), MoexPriceProvider

    private class StubCoingeckoProvider : StubPriceProvider(), CoingeckoPriceProvider

    private class FakeFxRateRepository(
        entries: Map<String, List<FxRate>>,
    ) : FxRateRepository {
        private val rates: Map<String, List<FxRate>> = entries.mapValues { (_, values) ->
            values.sortedBy { it.ts }
        }

        override suspend fun findOnOrBefore(ccy: String, timestamp: Instant): FxRate? =
            rates[ccy]?.filter { it.ts <= timestamp }?.maxByOrNull { it.ts }
    }

    private fun rate(ccy: String, date: LocalDate, value: BigDecimal): FxRate =
        FxRate(
            ccy = ccy,
            ts = date.atTime(LocalTime.NOON).atZone(ZoneOffset.UTC).toInstant(),
            rateRub = value,
            source = "test",
        )
}
