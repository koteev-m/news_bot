package repo.mapper

import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import model.FxRate
import repo.tables.FxRatesTable

class FxRateMapperTest {
    @Test
    fun `maps result row to fx rate`() {
        val ts = Instant.parse("2024-08-01T08:00:00Z")
        val row = testResultRow(
            FxRatesTable.ccy to "USD",
            FxRatesTable.ts to OffsetDateTime.ofInstant(ts, ZoneOffset.UTC),
            FxRatesTable.rateRub to BigDecimal("93.45"),
            FxRatesTable.sourceCol to "cbr",
        )

        val rate = row.toFxRate()

        assertEquals("USD", rate.ccy)
        assertEquals(ts, rate.ts)
        assertEquals(BigDecimal("93.45"), rate.rateRub.stripTrailingZeros())
        assertEquals("cbr", rate.source)
    }

    @Test
    fun `maps fx rate to column values`() {
        val ts = Instant.parse("2024-08-01T08:00:00Z")
        val rate = FxRate(ccy = "EUR", ts = ts, rateRub = BigDecimal("100.12"), source = "market")

        val values = rate.toColumnValues()

        assertEquals("EUR", values[FxRatesTable.ccy])
        assertEquals(OffsetDateTime.ofInstant(ts, ZoneOffset.UTC), values[FxRatesTable.ts])
        assertEquals(BigDecimal("100.12"), values[FxRatesTable.rateRub])
        assertEquals("market", values[FxRatesTable.sourceCol])
    }
}
