package repo.mapper

import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import model.PricePoint
import repo.tables.PricesTable

class PriceMapperTest {
    @Test
    fun `maps result row to price point`() {
        val ts = Instant.parse("2024-07-01T12:30:00Z")
        val row = testResultRow(
            PricesTable.instrumentId to 5L,
            PricesTable.ts to OffsetDateTime.ofInstant(ts, ZoneOffset.UTC),
            PricesTable.price to BigDecimal("321.45"),
            PricesTable.ccy to "USD",
            PricesTable.sourceCol to "provider",
        )

        val point = row.toPricePoint()

        assertEquals(5L, point.instrumentId)
        assertEquals(ts, point.ts)
        assertEquals(BigDecimal("321.45"), point.price.stripTrailingZeros())
        assertEquals("USD", point.ccy)
        assertEquals("provider", point.source)
    }

    @Test
    fun `maps price point to column values`() {
        val ts = Instant.parse("2024-07-01T12:30:00Z")
        val point = PricePoint(instrumentId = 9L, ts = ts, price = BigDecimal("10.0"), ccy = "EUR", source = "manual")

        val values = point.toColumnValues()

        assertEquals(9L, values[PricesTable.instrumentId])
        assertEquals(OffsetDateTime.ofInstant(ts, ZoneOffset.UTC), values[PricesTable.ts])
        assertEquals(BigDecimal("10.0"), values[PricesTable.price])
        assertEquals("manual", values[PricesTable.sourceCol])
    }
}
