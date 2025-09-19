package portfolio

import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import model.FxRate
import portfolio.errors.DomainResult
import portfolio.model.Money
import portfolio.service.CoingeckoPriceProvider
import portfolio.service.FxRateRepository
import portfolio.service.FxRateService
import portfolio.service.MoexPriceProvider
import portfolio.service.PricingService
import portfolio.service.ValuationService

class ValuationServiceTest {
    private val portfolioId = UUID.randomUUID()
    private val date = LocalDate.of(2024, 4, 1)

    @Test
    fun `returns zero valuation for empty portfolio`() = runBlocking {
        val storage = FakeStorage()
        val fxService = FxRateService(FakeFxRateRepository(emptyMap()))
        val pricing = PricingService(
            MutableMoexProvider(),
            StaticCoingeckoProvider(),
            fxService,
        )
        val service = ValuationService(storage, pricing, fxService)

        val result = service.revaluePortfolioOn(portfolioId, date)

        assertTrue(result.isSuccess)
        val valuation = result.getOrThrow()
        assertEquals(Money.of(BigDecimal.ZERO, "RUB"), valuation.valueRub)
        assertEquals(Money.of(BigDecimal.ZERO, "RUB"), valuation.pnlDay)
        assertEquals(Money.of(BigDecimal.ZERO, "RUB"), valuation.pnlTotal)
        assertEquals(BigDecimal.ZERO, valuation.drawdown)
        assertEquals(1, storage.upserts.size)
        val record = storage.upserts.single()
        assertEquals(BigDecimal.ZERO, record.valueRub)
        assertEquals(BigDecimal.ZERO, record.pnlDay)
        assertEquals(BigDecimal.ZERO, record.pnlTotal)
        assertEquals(BigDecimal.ZERO, record.drawdown)
    }

    @Test
    fun `persists valuations and computes drawdown`() = runBlocking {
        val positions = listOf(
            ValuationService.Storage.PositionSnapshot(
                instrumentId = 1L,
                quantity = BigDecimal("5"),
                averagePrice = Money.of(BigDecimal("100"), "USD"),
            ),
            ValuationService.Storage.PositionSnapshot(
                instrumentId = 2L,
                quantity = BigDecimal("2"),
                averagePrice = Money.of(BigDecimal("3000"), "RUB"),
            ),
        )
        val storage = FakeStorage(positions)
        val fxService = FxRateService(
            FakeFxRateRepository(
                mapOf(
                    "USD" to listOf(rate("USD", date, BigDecimal("90"))),
                ),
            ),
        )
        val moex = MutableMoexProvider().apply {
            closePrices[(1L to date)] = Money.of(BigDecimal("120"), "USD")
            closePrices[(2L to date)] = Money.of(BigDecimal("3200"), "RUB")
        }
        val pricing = PricingService(moex, StaticCoingeckoProvider(), fxService)
        val service = ValuationService(storage, pricing, fxService)

        val dayOne = service.revaluePortfolioOn(portfolioId, date)

        assertTrue(dayOne.isSuccess)
        val firstValuation = dayOne.getOrThrow()
        assertMoneyEquals("60400", firstValuation.valueRub)
        assertMoneyEquals("9400", firstValuation.pnlDay)
        assertMoneyEquals("9400", firstValuation.pnlTotal)
        assertEquals(BigDecimal.ZERO, firstValuation.drawdown)

        val nextDate = date.plusDays(1)
        moex.closePrices[(1L to nextDate)] = Money.of(BigDecimal("80"), "USD")
        moex.closePrices[(2L to nextDate)] = Money.of(BigDecimal("2800"), "RUB")

        val dayTwo = service.revaluePortfolioOn(portfolioId, nextDate)

        assertTrue(dayTwo.isSuccess)
        val secondValuation = dayTwo.getOrThrow()
        assertMoneyEquals("41600", secondValuation.valueRub)
        assertMoneyEquals("-18800", secondValuation.pnlDay)
        assertMoneyEquals("-9400", secondValuation.pnlTotal)
        val expectedDrawdown = BigDecimal("41600")
            .divide(BigDecimal("60400"), MathContext.DECIMAL128)
            .subtract(BigDecimal.ONE)
            .stripTrailingZeros()
        assertEquals(0, expectedDrawdown.compareTo(secondValuation.drawdown))

        // ensure idempotent upsert per day
        val repeat = service.revaluePortfolioOn(portfolioId, nextDate)
        assertTrue(repeat.isSuccess)
        assertEquals(3, storage.upserts.size)
        val storedForNextDate = storage.records[nextDate]!!
        assertEquals(secondValuation.valueRub.amount, storedForNextDate.valueRub)
    }

    private fun assertMoneyEquals(expected: String, actual: Money) {
        assertEquals(Money.of(BigDecimal(expected), "RUB"), actual)
    }

    private class FakeStorage(
        private val positions: List<ValuationService.Storage.PositionSnapshot> = emptyList(),
    ) : ValuationService.Storage {
        val records = mutableMapOf<LocalDate, ValuationService.Storage.ValuationRecord>()
        val upserts = mutableListOf<ValuationService.Storage.ValuationRecord>()

        override suspend fun listPositions(portfolioId: UUID): DomainResult<List<ValuationService.Storage.PositionSnapshot>> {
            return DomainResult.success(positions)
        }

        override suspend fun latestValuationBefore(
            portfolioId: UUID,
            date: LocalDate,
        ): DomainResult<ValuationService.Storage.ValuationRecord?> {
            val previous = records.values
                .filter { it.portfolioId == portfolioId && it.date < date }
                .maxByOrNull { it.date }
            return DomainResult.success(previous)
        }

        override suspend fun upsertValuation(
            record: ValuationService.Storage.ValuationRecord,
        ): DomainResult<ValuationService.Storage.ValuationRecord> {
            records[record.date] = record
            upserts += record
            return DomainResult.success(record)
        }
    }

    private class FakeFxRateRepository(
        private val rates: Map<String, List<FxRate>>,
    ) : FxRateRepository {
        override suspend fun findOnOrBefore(ccy: String, timestamp: java.time.Instant): FxRate? {
            val entries = rates[ccy] ?: return null
            return entries
                .filter { !it.ts.isAfter(timestamp) }
                .maxByOrNull { it.ts }
        }
    }

    private class MutableMoexProvider : MoexPriceProvider {
        val closePrices = mutableMapOf<Pair<Long, LocalDate>, Money?>()

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
