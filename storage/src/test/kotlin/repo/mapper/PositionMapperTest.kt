package repo.mapper

import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import repo.tables.PositionsTable
import model.PositionDto

class PositionMapperTest {
    @Test
    fun `maps result row to position dto`() {
        val portfolioId = UUID.fromString("10000000-0000-0000-0000-000000000000")
        val updatedAt = Instant.parse("2024-06-01T10:00:00Z")
        val row = testResultRow(
            PositionsTable.portfolioId to portfolioId,
            PositionsTable.instrumentId to 55L,
            PositionsTable.qty to BigDecimal("15.5"),
            PositionsTable.avgPrice to BigDecimal("123.45"),
            PositionsTable.avgPriceCcy to "USD",
            PositionsTable.updatedAt to OffsetDateTime.ofInstant(updatedAt, ZoneOffset.UTC),
        )

        val dto = row.toPositionDto()

        assertEquals(portfolioId, dto.portfolioId)
        assertEquals(55L, dto.instrumentId)
        assertEquals(0, BigDecimal("15.5").compareTo(dto.qty))
        assertEquals(0, BigDecimal("123.45").compareTo(dto.avgPrice))
        assertEquals("USD", dto.avgPriceCcy)
        assertEquals(updatedAt, dto.updatedAt)
    }

    @Test
    fun `maps position dto to insert values`() {
        val dto = PositionDto(
            portfolioId = UUID.fromString("10000000-0000-0000-0000-000000000000"),
            instrumentId = 5L,
            qty = BigDecimal("1.0"),
            avgPrice = null,
            avgPriceCcy = null,
            updatedAt = Instant.parse("2024-06-01T10:00:00Z"),
        )

        val insertValues = dto.toInsertValues()

        assertEquals(dto.portfolioId, insertValues[PositionsTable.portfolioId])
        assertEquals(null, insertValues[PositionsTable.avgPrice])
        assertEquals(OffsetDateTime.ofInstant(dto.updatedAt, ZoneOffset.UTC), insertValues[PositionsTable.updatedAt])

        val updateValues = dto.toUpdateValues()
        assertEquals(BigDecimal("1.0"), updateValues[PositionsTable.qty])
        assertEquals(null, updateValues[PositionsTable.avgPriceCcy])
    }
}
