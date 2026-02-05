package portfolio

import kotlinx.coroutines.runBlocking
import portfolio.errors.DomainResult
import portfolio.model.DateRange
import portfolio.model.Money
import portfolio.model.ValuationMethod
import portfolio.service.ReportService
import java.math.BigDecimal
import java.math.MathContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportServiceTest {
    private val portfolioId = UUID.randomUUID()
    private val baseCurrency = "RUB"

    @Test
    fun `aggregates totals and breakdowns for range`() =
        runBlocking {
            val range = DateRange(LocalDate.of(2024, 4, 2), LocalDate.of(2024, 4, 4))
            val valuations =
                listOf(
                    valuation(
                        date = LocalDate.of(2024, 4, 4),
                        value = "1300",
                        pnlDay = "40",
                        pnlTotal = "120",
                        drawdown = "0",
                    ),
                    valuation(
                        date = LocalDate.of(2024, 4, 2),
                        value = "1200",
                        pnlDay = "30",
                        pnlTotal = "80",
                        drawdown = "-0.05",
                    ),
                    valuation(
                        date = LocalDate.of(2024, 4, 3),
                        value = "1150",
                        pnlDay = "-20",
                        pnlTotal = "70",
                        drawdown = "-0.0833333333333333",
                    ),
                )
            val baseline =
                valuation(
                    date = LocalDate.of(2024, 4, 1),
                    value = "1000",
                    pnlDay = "0",
                    pnlTotal = "50",
                    drawdown = "0",
                )
            val realized =
                listOf(
                    realized(
                        date = LocalDate.of(2024, 4, 3),
                        amount = "30",
                        instrumentId = 1L,
                        instrument = "ACME",
                        assetClass = "Equity",
                        sector = "Tech",
                    ),
                    realized(
                        date = LocalDate.of(2024, 4, 4),
                        amount = "-10",
                        instrumentId = 2L,
                        instrument = "TBOND",
                        assetClass = "Bond",
                        sector = "Gov",
                    ),
                )
            val holdings =
                listOf(
                    holding(
                        instrumentId = 1L,
                        instrument = "ACME",
                        value = "800",
                        unrealized = "60",
                        assetClass = "Equity",
                        sector = "Tech",
                    ),
                    holding(
                        instrumentId = 2L,
                        instrument = "TBOND",
                        value = "400",
                        unrealized = "10",
                        assetClass = "Bond",
                        sector = "Gov",
                    ),
                    holding(
                        instrumentId = 3L,
                        instrument = "CASH",
                        value = "100",
                        unrealized = "0",
                        assetClass = null,
                        sector = null,
                    ),
                )

            val storage =
                FakeStorage(
                    valuationMethod = ValuationMethod.FIFO,
                    valuations = valuations,
                    baseline = baseline,
                    realized = realized,
                    holdings = holdings,
                )
            val clock = Clock.fixed(Instant.parse("2024-04-05T00:00:00Z"), ZoneOffset.UTC)
            val service = ReportService(storage, clock, baseCurrency)

            val result = service.getPortfolioReport(portfolioId, range)

            assertTrue(result.isSuccess)
            val report = result.getOrThrow()
            assertEquals(portfolioId, report.portfolioId)
            assertEquals(range, report.period)
            assertEquals(ValuationMethod.FIFO, report.valuationMethod)
            assertEquals(clock.instant(), report.generatedAt)

            assertEquals(
                listOf(LocalDate.of(2024, 4, 2), LocalDate.of(2024, 4, 3), LocalDate.of(2024, 4, 4)),
                report.valuations.map { it.date },
            )
            assertEquals(
                listOf(LocalDate.of(2024, 4, 3), LocalDate.of(2024, 4, 4)),
                report.realized.map { it.tradeDate },
            )

            assertEquals(money("20"), report.totals.realized)
            assertEquals(money("70"), report.totals.unrealizedChange)
            assertEquals(money("90"), report.totals.total)
            assertEquals(money("30"), report.totals.averageDaily)
            assertEquals(0, expectedDrawdown().compareTo(report.totals.maxDrawdown))

            val assetClasses = report.assetClassContribution
            assertEquals(2, assetClasses.size)
            assertEquals("Equity", assetClasses[0].key)
            assertEquals(money("800"), assetClasses[0].amount)
            assertDecimalEquals(
                BigDecimal("800").divide(BigDecimal("1300"), MathContext.DECIMAL128),
                assetClasses[0].weight!!,
            )
            assertEquals("Bond", assetClasses[1].key)
            assertEquals(money("400"), assetClasses[1].amount)
            assertDecimalEquals(
                BigDecimal("400").divide(BigDecimal("1300"), MathContext.DECIMAL128),
                assetClasses[1].weight!!,
            )

            val sectors = report.sectorContribution
            assertEquals(listOf("Tech", "Gov"), sectors.map { it.key })
            assertEquals(money("800"), sectors[0].amount)
            assertDecimalEquals(
                BigDecimal("800").divide(BigDecimal("1300"), MathContext.DECIMAL128),
                sectors[0].weight!!,
            )

            val top = report.topPositions
            assertEquals(3, top.size)
            assertEquals(1L, top[0].instrumentId)
            assertEquals(money("800"), top[0].valuation)
            assertEquals(money("60"), top[0].unrealizedPnl)
            assertDecimalEquals(BigDecimal("800").divide(BigDecimal("1300"), MathContext.DECIMAL128), top[0].weight!!)
            assertEquals(2L, top[1].instrumentId)
            assertEquals(money("400"), top[1].valuation)
            assertDecimalEquals(BigDecimal("400").divide(BigDecimal("1300"), MathContext.DECIMAL128), top[1].weight!!)
            assertEquals(3L, top[2].instrumentId)
            assertEquals(money("100"), top[2].valuation)
            assertDecimalEquals(BigDecimal("100").divide(BigDecimal("1300"), MathContext.DECIMAL128), top[2].weight!!)
        }

    @Test
    fun `handles empty data`() =
        runBlocking {
            val range = DateRange(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 3))
            val storage = FakeStorage(valuationMethod = ValuationMethod.AVERAGE)
            val clock = Clock.fixed(Instant.parse("2024-01-04T00:00:00Z"), ZoneOffset.UTC)
            val service = ReportService(storage, clock, baseCurrency)

            val result = service.getPortfolioReport(portfolioId, range)

            assertTrue(result.isSuccess)
            val report = result.getOrThrow()
            assertTrue(report.valuations.isEmpty())
            assertTrue(report.realized.isEmpty())
            assertTrue(report.assetClassContribution.isEmpty())
            assertTrue(report.sectorContribution.isEmpty())
            assertTrue(report.topPositions.isEmpty())
            assertEquals(money("0"), report.totals.realized)
            assertEquals(money("0"), report.totals.unrealizedChange)
            assertEquals(money("0"), report.totals.total)
            assertEquals(money("0"), report.totals.averageDaily)
            assertEquals(BigDecimal.ZERO, report.totals.maxDrawdown)
        }

    private fun money(amount: String): Money = Money.of(BigDecimal(amount), baseCurrency)

    private fun valuation(
        date: LocalDate,
        value: String,
        pnlDay: String,
        pnlTotal: String,
        drawdown: String,
    ): ReportService.Storage.ValuationRecord =
        ReportService.Storage.ValuationRecord(
            date = date,
            value = money(value),
            pnlDay = money(pnlDay),
            pnlTotal = money(pnlTotal),
            drawdown = BigDecimal(drawdown),
        )

    private fun realized(
        date: LocalDate,
        amount: String,
        instrumentId: Long,
        instrument: String?,
        assetClass: String?,
        sector: String?,
    ): ReportService.Storage.RealizedTrade =
        ReportService.Storage.RealizedTrade(
            tradeDate = date,
            amount = money(amount),
            instrumentId = instrumentId,
            instrumentName = instrument,
            assetClass = assetClass,
            sector = sector,
        )

    private fun holding(
        instrumentId: Long,
        instrument: String,
        value: String,
        unrealized: String,
        assetClass: String?,
        sector: String?,
    ): ReportService.Storage.Holding =
        ReportService.Storage.Holding(
            instrumentId = instrumentId,
            instrumentName = instrument,
            valuation = money(value),
            unrealizedPnl = money(unrealized),
            assetClass = assetClass,
            sector = sector,
        )

    private fun expectedDrawdown(): BigDecimal = BigDecimal("-0.0833333333333333").stripTrailingZeros()

    private fun assertDecimalEquals(
        expected: BigDecimal,
        actual: BigDecimal,
    ) {
        val normalizedExpected = expected.stripTrailingZeros()
        val normalizedActual = actual.stripTrailingZeros()
        assertEquals(0, normalizedExpected.compareTo(normalizedActual))
    }

    private class FakeStorage(
        private val valuationMethod: ValuationMethod,
        private val valuations: List<ReportService.Storage.ValuationRecord> = emptyList(),
        private val baseline: ReportService.Storage.ValuationRecord? = null,
        private val realized: List<ReportService.Storage.RealizedTrade> = emptyList(),
        private val holdings: List<ReportService.Storage.Holding> = emptyList(),
    ) : ReportService.Storage {
        override suspend fun valuationMethod(portfolioId: UUID): DomainResult<ValuationMethod> =
            DomainResult.success(valuationMethod)

        override suspend fun listValuations(
            portfolioId: UUID,
            range: DateRange,
        ): DomainResult<List<ReportService.Storage.ValuationRecord>> {
            val filtered = valuations.filter { it.date in range }
            return DomainResult.success(filtered)
        }

        override suspend fun latestValuationBefore(
            portfolioId: UUID,
            date: LocalDate,
        ): DomainResult<ReportService.Storage.ValuationRecord?> {
            val candidate = baseline?.takeIf { it.date.isBefore(date) }
            return DomainResult.success(candidate)
        }

        override suspend fun listRealizedPnl(
            portfolioId: UUID,
            range: DateRange,
        ): DomainResult<List<ReportService.Storage.RealizedTrade>> {
            val filtered = realized.filter { it.tradeDate in range }
            return DomainResult.success(filtered)
        }

        override suspend fun listHoldings(
            portfolioId: UUID,
            asOf: LocalDate,
        ): DomainResult<List<ReportService.Storage.Holding>> = DomainResult.success(holdings)
    }
}
