package repo.mapper

import repo.model.NewTrade
import repo.model.TradeUpdate
import repo.tables.TradesTable
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TradeMapperTest {
    @Test
    fun `maps result row to trade dto`() {
        val tradeId = 11L
        val portfolioId = UUID.fromString("00000000-0000-0000-0000-000000000111")
        val instrumentId = 200L
        val datetime = Instant.parse("2024-04-01T12:00:00Z")
        val createdAt = Instant.parse("2024-04-02T12:00:00Z")
        val row =
            testResultRow(
                TradesTable.tradeId to tradeId,
                TradesTable.portfolioId to portfolioId,
                TradesTable.instrumentId to instrumentId,
                TradesTable.datetime to OffsetDateTime.ofInstant(datetime, ZoneOffset.UTC),
                TradesTable.side to "BUY",
                TradesTable.quantity to BigDecimal("10.5"),
                TradesTable.price to BigDecimal("150.25"),
                TradesTable.priceCurrency to "USD",
                TradesTable.fee to BigDecimal("1.23"),
                TradesTable.feeCurrency to "USD",
                TradesTable.tax to BigDecimal("0.45"),
                TradesTable.taxCurrency to "USD",
                TradesTable.broker to "TestBroker",
                TradesTable.note to "note",
                TradesTable.extId to "ext-1",
                TradesTable.createdAt to OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC),
            )

        val dto = row.toTradeDto()

        assertEquals(tradeId, dto.tradeId)
        assertEquals(portfolioId, dto.portfolioId)
        assertEquals(instrumentId, dto.instrumentId)
        assertEquals(datetime, dto.datetime)
        assertEquals("BUY", dto.side)
        assertEquals(0, BigDecimal("10.5").compareTo(dto.quantity))
        assertEquals(0, BigDecimal("150.25").compareTo(dto.price))
        assertEquals("USD", dto.priceCurrency)
        assertEquals(0, BigDecimal("1.23").compareTo(dto.fee))
        assertEquals(0, BigDecimal("0.45").compareTo(dto.tax))
        assertEquals("USD", dto.taxCurrency)
        assertEquals("TestBroker", dto.broker)
        assertEquals("note", dto.note)
        assertEquals("ext-1", dto.extId)
        assertEquals(createdAt, dto.createdAt)
    }

    @Test
    fun `maps new trade to column values`() {
        val portfolioId = UUID.fromString("00000000-0000-0000-0000-000000000999")
        val datetime = Instant.parse("2024-04-01T12:00:00Z")
        val createdAt = Instant.parse("2024-04-01T13:00:00Z")
        val newTrade =
            NewTrade(
                portfolioId = portfolioId,
                instrumentId = 1L,
                datetime = datetime,
                side = "SELL",
                quantity = BigDecimal.ONE,
                price = BigDecimal.TEN,
                priceCurrency = "USD",
                fee = BigDecimal.ZERO,
                feeCurrency = "USD",
                tax = null,
                taxCurrency = null,
                broker = null,
                note = null,
                extId = null,
                createdAt = createdAt,
            )

        val values = newTrade.toColumnValues()

        assertEquals(portfolioId, values[TradesTable.portfolioId])
        assertEquals(OffsetDateTime.ofInstant(datetime, ZoneOffset.UTC), values[TradesTable.datetime])
        assertFalse(values.containsKey(TradesTable.tax))
        assertFalse(values.containsKey(TradesTable.broker))
        assertEquals(OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC), values[TradesTable.createdAt])
    }

    @Test
    fun `maps trade update to column values`() {
        val update = TradeUpdate(price = BigDecimal("99.9"), note = "updated")

        val values = update.toColumnValues()

        assertEquals(BigDecimal("99.9"), values[TradesTable.price])
        assertEquals("updated", values[TradesTable.note])
        assertFalse(values.containsKey(TradesTable.side))
    }
}
