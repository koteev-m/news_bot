package portfolio

import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import model.FxRate
import portfolio.errors.DomainResult
import portfolio.errors.PortfolioError
import portfolio.errors.PortfolioException
import portfolio.model.Money
import portfolio.model.TradeSide
import portfolio.model.TradeView
import portfolio.model.ValuationMethod
import portfolio.service.CoingeckoPriceProvider
import portfolio.service.FxRateRepository
import portfolio.service.FxRateService
import portfolio.service.MoexPriceProvider
import portfolio.service.PricingService
import portfolio.service.PortfolioService
import portfolio.service.PositionCalc

class PortfolioServiceTest {
    private val portfolioId = UUID.randomUUID()
    private val instrumentId = 123L
    private val tradeDate = LocalDate.of(2024, 3, 18)
    private val clock = Clock.fixed(Instant.parse("2024-03-19T10:15:30Z"), ZoneId.of("UTC"))

    @Test
    fun `applies buy trade and stores updated average position`() = runBlocking {
        val storage = FakeStorage()
        val service = PortfolioService(storage, clock)

        val trade = TradeView(
            tradeId = UUID.randomUUID(),
            portfolioId = portfolioId,
            instrumentId = instrumentId,
            tradeDate = tradeDate,
            side = TradeSide.BUY,
            quantity = BigDecimal("10"),
            price = usd("100"),
            fee = usd("1"),
            tax = usd("0.50"),
        )

        val result = service.applyTrade(trade, ValuationMethod.AVERAGE)

        assertTrue(result.isSuccess)
        val saved = storage.savedPositions.single()
        assertEquals(portfolioId, saved.portfolioId)
        assertEquals(instrumentId, saved.instrumentId)
        assertEquals(ValuationMethod.AVERAGE, saved.valuationMethod)
        assertEquals(clock.instant(), saved.updatedAt)
        assertEquals(BigDecimal("10"), saved.position.quantity)
        assertEquals(usd("1001.5"), saved.position.costBasis)
        assertEquals(usd("1000"), storage.recordedTrades.single().notional)
        assertEquals(null, storage.recordedTrades.single().realizedPnl)
    }

    @Test
    fun `rejects sell trade that exceeds current quantity`() = runBlocking {
        val fifoCalc = PositionCalc.FifoCalc()
        var position = PositionCalc.Position.empty("USD")
        position = fifoCalc.applyBuy(position, BigDecimal("3"), usd("50"), usd("0"))
            .position
        val stored = PortfolioService.StoredPosition(
            portfolioId,
            instrumentId,
            ValuationMethod.FIFO,
            position,
            clock.instant(),
        )
        val storage = FakeStorage(existing = stored)
        val service = PortfolioService(storage, clock)

        val trade = TradeView(
            tradeId = UUID.randomUUID(),
            portfolioId = portfolioId,
            instrumentId = instrumentId,
            tradeDate = tradeDate,
            side = TradeSide.SELL,
            quantity = BigDecimal("5"),
            price = usd("60"),
        )

        val result = service.applyTrade(trade, ValuationMethod.FIFO)

        assertTrue(result.isFailure)
        val exception = assertIs<PortfolioException>(result.exceptionOrNull())
        assertIs<PortfolioError.Validation>(exception.error)
        assertTrue(storage.savedPositions.isEmpty())
        assertTrue(storage.recordedTrades.isEmpty())
    }

    @Test
    fun `applies fifo sell and records realized pnl`() = runBlocking {
        val fifoCalc = PositionCalc.FifoCalc()
        var position = PositionCalc.Position.empty("USD")
        position = fifoCalc.applyBuy(position, BigDecimal("5"), usd("100"), usd("0"))
            .position
        position = fifoCalc.applyBuy(position, BigDecimal("3"), usd("120"), usd("0"))
            .position
        val stored = PortfolioService.StoredPosition(
            portfolioId,
            instrumentId,
            ValuationMethod.FIFO,
            position,
            clock.instant(),
        )
        val storage = FakeStorage(existing = stored)
        val service = PortfolioService(storage, clock)

        val totalFees = usd("1.50")
        val expected = fifoCalc.applySell(position, BigDecimal("6"), usd("150"), totalFees)

        val trade = TradeView(
            tradeId = UUID.randomUUID(),
            portfolioId = portfolioId,
            instrumentId = instrumentId,
            tradeDate = tradeDate,
            side = TradeSide.SELL,
            quantity = BigDecimal("6"),
            price = usd("150"),
            fee = usd("1"),
            tax = usd("0.50"),
        )

        val result = service.applyTrade(trade, ValuationMethod.FIFO)

        assertTrue(result.isSuccess)
        val saved = storage.savedPositions.single()
        assertEquals(expected.position.quantity, saved.position.quantity)
        assertEquals(expected.position.costBasis, saved.position.costBasis)
        assertEquals(expected.position.lots, saved.position.lots)
        val recorded = storage.recordedTrades.single()
        assertEquals(expected.realizedPnl, recorded.realizedPnl)
        assertEquals(trade.tradeId, recorded.tradeId)
        assertEquals(ValuationMethod.FIFO, recorded.valuationMethod)
    }

    @Test
    fun `rejects trade when currency mismatches stored position`() = runBlocking {
        val avgCalc = PositionCalc.AverageCostCalc()
        val initial = avgCalc.applyBuy(
            PositionCalc.Position.empty("USD"),
            BigDecimal("2"),
            usd("100"),
            usd("0"),
        ).position
        val stored = PortfolioService.StoredPosition(
            portfolioId,
            instrumentId,
            ValuationMethod.AVERAGE,
            initial,
            clock.instant(),
        )
        val storage = FakeStorage(existing = stored)
        val service = PortfolioService(storage, clock)

        val trade = TradeView(
            tradeId = UUID.randomUUID(),
            portfolioId = portfolioId,
            instrumentId = instrumentId,
            tradeDate = tradeDate,
            side = TradeSide.BUY,
            quantity = BigDecimal.ONE,
            price = Money.of(BigDecimal("90"), "EUR"),
        )

        val result = service.applyTrade(trade, ValuationMethod.AVERAGE)

        assertTrue(result.isFailure)
        val exception = assertIs<PortfolioException>(result.exceptionOrNull())
        assertIs<PortfolioError.Validation>(exception.error)
        assertTrue(storage.savedPositions.isEmpty())
        assertTrue(storage.recordedTrades.isEmpty())
    }

    @Test
    fun `lists open positions with unrealized pnl`() = runBlocking {
        val storage = FakeStorage(
            positions = listOf(
                PortfolioService.PositionSummary(
                    portfolioId = portfolioId,
                    instrumentId = instrumentId,
                    instrumentName = "ACME",
                    quantity = BigDecimal("5"),
                    averagePrice = Money.of(BigDecimal("100"), "USD"),
                    valuationMethod = ValuationMethod.AVERAGE,
                ),
            ),
        )
        val fxService = FxRateService(
            FakeFxRateRepository(
                mapOf(
                    "USD" to listOf(rate("USD", tradeDate, BigDecimal("90"))),
                ),
            ),
        )
        val pricing = PricingService(
            StaticMoexProvider(
                mapOf(
                    (instrumentId to tradeDate) to Money.of(BigDecimal("150"), "USD"),
                ),
            ),
            StaticCoingeckoProvider(),
            fxService,
        )
        val service = PortfolioService(storage, clock)

        val result = service.listPositions(portfolioId, tradeDate, pricing, fxService)

        assertTrue(result.isSuccess)
        val views = result.getOrThrow()
        assertEquals(1, views.size)
        val view = views.single()
        assertEquals("ACME", view.instrumentName)
        assertEquals(BigDecimal("5"), view.quantity)
        assertEquals(Money.of(BigDecimal("67500"), "RUB"), view.valuation)
        assertEquals(Money.of(BigDecimal("9000"), "RUB"), view.averageCost)
        assertEquals(Money.of(BigDecimal("22500"), "RUB"), view.unrealizedPnl)
        assertEquals(ValuationMethod.AVERAGE, view.valuationMethod)
    }

    private fun usd(amount: String): Money = Money.of(BigDecimal(amount), "USD")

    private class FakeStorage(
        existing: PortfolioService.StoredPosition? = null,
        positions: List<PortfolioService.PositionSummary> = emptyList(),
    ) : PortfolioService.Storage {
        private var current: PortfolioService.StoredPosition? = existing
        val savedPositions = mutableListOf<PortfolioService.StoredPosition>()
        val recordedTrades = mutableListOf<PortfolioService.StoredTrade>()
        private val positionSummaries = positions.toMutableList()

        override suspend fun <T> transaction(
            block: suspend PortfolioService.Storage.Transaction.() -> DomainResult<T>,
        ): DomainResult<T> {
            val tx = object : PortfolioService.Storage.Transaction {
                override suspend fun loadPosition(
                    portfolioId: UUID,
                    instrumentId: Long,
                    method: ValuationMethod,
                ): DomainResult<PortfolioService.StoredPosition?> {
                    val stored = current
                    return if (stored != null && stored.portfolioId == portfolioId &&
                        stored.instrumentId == instrumentId && stored.valuationMethod == method
                    ) {
                        DomainResult.success(stored)
                    } else {
                        DomainResult.success(null)
                    }
                }

                override suspend fun savePosition(
                    position: PortfolioService.StoredPosition,
                ): DomainResult<Unit> {
                    current = position
                    savedPositions += position
                    return DomainResult.success(Unit)
                }

                override suspend fun recordTrade(
                    trade: PortfolioService.StoredTrade,
                ): DomainResult<Unit> {
                    recordedTrades += trade
                    return DomainResult.success(Unit)
                }
            }

            return tx.block()
        }

        override suspend fun listPositions(portfolioId: UUID): DomainResult<List<PortfolioService.PositionSummary>> {
            return DomainResult.success(positionSummaries.filter { it.portfolioId == portfolioId })
        }
    }

    private class FakeFxRateRepository(
        private val rates: Map<String, List<FxRate>>,
    ) : FxRateRepository {
        override suspend fun findOnOrBefore(ccy: String, timestamp: Instant): FxRate? {
            val entries = rates[ccy] ?: return null
            return entries
                .filter { !it.ts.isAfter(timestamp) }
                .maxByOrNull { it.ts }
        }
    }

    private class StaticMoexProvider(
        private val closePrices: Map<Pair<Long, LocalDate>, Money?>,
    ) : MoexPriceProvider {
        override suspend fun closePrice(instrumentId: Long, on: LocalDate): DomainResult<Money?> =
            DomainResult.success(closePrices[instrumentId to on])

        override suspend fun lastPrice(instrumentId: Long, on: LocalDate): DomainResult<Money?> =
            DomainResult.success(null)
    }

    private class StaticCoingeckoProvider : CoingeckoPriceProvider {
        override suspend fun closePrice(instrumentId: Long, on: LocalDate): DomainResult<Money?> =
            DomainResult.success(null)

        override suspend fun lastPrice(instrumentId: Long, on: LocalDate): DomainResult<Money?> =
            DomainResult.success(null)
    }

    private fun rate(ccy: String, date: LocalDate, value: BigDecimal): FxRate {
        val ts = date.atTime(LocalTime.NOON).atZone(ZoneOffset.UTC).toInstant()
        return FxRate(ccy = ccy, ts = ts, rateRub = value, source = "TEST")
    }
}
