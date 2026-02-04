package repo.mapper

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import repo.model.NewPortfolio
import repo.model.PortfolioUpdate
import repo.tables.PortfoliosTable

class PortfolioMapperTest {
    @Test
    fun `maps result row to portfolio entity`() {
        val portfolioId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val createdAt = Instant.parse("2024-01-02T00:00:00Z")
        val row = testResultRow(
            PortfoliosTable.portfolioId to portfolioId,
            PortfoliosTable.userId to 77L,
            PortfoliosTable.name to "Retirement",
            PortfoliosTable.baseCurrency to "USD",
            PortfoliosTable.isActive to true,
            PortfoliosTable.createdAt to OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC),
        )

        val entity = row.toPortfolioEntity()

        assertEquals(portfolioId, entity.portfolioId)
        assertEquals(77L, entity.userId)
        assertEquals("Retirement", entity.name)
        assertEquals("USD", entity.baseCurrency)
        assertTrue(entity.isActive)
        assertEquals(createdAt, entity.createdAt)
    }

    @Test
    fun `maps new portfolio to insert values`() {
        val createdAt = Instant.parse("2024-01-02T00:00:00Z")
        val payload = NewPortfolio(
            userId = 7L,
            name = "Growth",
            baseCurrency = "EUR",
            isActive = false,
            createdAt = createdAt
        )

        val values = payload.toColumnValues()

        assertEquals(7L, values[PortfoliosTable.userId])
        assertEquals("Growth", values[PortfoliosTable.name])
        assertEquals("EUR", values[PortfoliosTable.baseCurrency])
        assertEquals(false, values[PortfoliosTable.isActive])
        assertEquals(OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC), values[PortfoliosTable.createdAt])
    }

    @Test
    fun `maps portfolio update to column values`() {
        val update = PortfolioUpdate(name = "Income", baseCurrency = null, isActive = true)

        val values = update.toColumnValues()

        assertEquals(2, values.size)
        assertEquals("Income", values[PortfoliosTable.name])
        assertEquals(true, values[PortfoliosTable.isActive])
        assertFalse(values.containsKey(PortfoliosTable.baseCurrency))
    }
}
