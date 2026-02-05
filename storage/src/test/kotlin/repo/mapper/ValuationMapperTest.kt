package repo.mapper

import repo.model.NewValuationDaily
import repo.tables.ValuationsDailyTable
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ValuationMapperTest {
    @Test
    fun `maps result row to valuation`() {
        val portfolioId = UUID.fromString("20000000-0000-0000-0000-000000000000")
        val date = LocalDate.of(2024, 9, 1)
        val row =
            testResultRow(
                ValuationsDailyTable.portfolioId to portfolioId,
                ValuationsDailyTable.date to date,
                ValuationsDailyTable.valueRub to BigDecimal("100000.12"),
                ValuationsDailyTable.pnlDay to BigDecimal("150.00"),
                ValuationsDailyTable.pnlTotal to BigDecimal("2500.50"),
                ValuationsDailyTable.drawdown to BigDecimal("-50.25"),
            )

        val record = row.toValuationDailyRecord()

        assertEquals(portfolioId, record.portfolioId)
        assertEquals(date, record.date)
        assertEquals(0, BigDecimal("100000.12").compareTo(record.valueRub))
        assertEquals(0, BigDecimal("150.00").compareTo(record.pnlDay))
        assertEquals(0, BigDecimal("2500.50").compareTo(record.pnlTotal))
        assertEquals(0, BigDecimal("-50.25").compareTo(record.drawdown))
    }

    @Test
    fun `maps valuation payload to column values`() {
        val portfolioId = UUID.fromString("20000000-0000-0000-0000-000000000000")
        val payload =
            NewValuationDaily(
                portfolioId = portfolioId,
                date = LocalDate.of(2024, 9, 2),
                valueRub = BigDecimal("101000.00"),
                pnlDay = BigDecimal("100.00"),
                pnlTotal = BigDecimal("2600.50"),
                drawdown = BigDecimal("-40.25"),
            )

        val values = payload.toColumnValues()

        assertEquals(portfolioId, values[ValuationsDailyTable.portfolioId])
        assertEquals(LocalDate.of(2024, 9, 2), values[ValuationsDailyTable.date])
        assertEquals(BigDecimal("101000.00"), values[ValuationsDailyTable.valueRub])
    }
}
