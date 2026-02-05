package repo.mapper

import repo.model.InstrumentUpdate
import repo.model.NewInstrument
import repo.model.NewInstrumentAlias
import repo.tables.InstrumentAliasesTable
import repo.tables.InstrumentsTable
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class InstrumentMapperTest {
    @Test
    fun `maps result row to instrument entity`() {
        val createdAt = Instant.parse("2024-05-15T09:00:00Z")
        val row =
            testResultRow(
                InstrumentsTable.instrumentId to 10L,
                InstrumentsTable.clazz to "stock",
                InstrumentsTable.exchange to "MOEX",
                InstrumentsTable.board to "TQBR",
                InstrumentsTable.symbol to "SBER",
                InstrumentsTable.isin to "RU0009029540",
                InstrumentsTable.cgId to "cg-123",
                InstrumentsTable.currency to "RUB",
                InstrumentsTable.createdAt to OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC),
            )

        val entity = row.toInstrumentEntity()

        assertEquals(10L, entity.instrumentId)
        assertEquals("stock", entity.clazz)
        assertEquals("MOEX", entity.exchange)
        assertEquals("TQBR", entity.board)
        assertEquals("SBER", entity.symbol)
        assertEquals("RU0009029540", entity.isin)
        assertEquals("cg-123", entity.cgId)
        assertEquals("RUB", entity.currency)
        assertEquals(createdAt, entity.createdAt)
    }

    @Test
    fun `maps new instrument to column values`() {
        val createdAt = Instant.parse("2024-05-15T09:00:00Z")
        val payload =
            NewInstrument(
                clazz = "bond",
                exchange = "NYSE",
                board = null,
                symbol = "TSLA",
                isin = null,
                cgId = null,
                currency = "USD",
                createdAt = createdAt,
            )

        val values = payload.toColumnValues()

        assertEquals("bond", values[InstrumentsTable.clazz])
        assertEquals("NYSE", values[InstrumentsTable.exchange])
        assertEquals(null, values[InstrumentsTable.board])
        assertEquals("TSLA", values[InstrumentsTable.symbol])
        assertEquals(null, values[InstrumentsTable.isin])
        assertEquals("USD", values[InstrumentsTable.currency])
        assertEquals(OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC), values[InstrumentsTable.createdAt])
    }

    @Test
    fun `maps instrument update`() {
        val update = InstrumentUpdate(clazz = "etf", symbol = "FXUS")

        val values = update.toColumnValues()

        assertEquals(2, values.size)
        assertEquals("etf", values[InstrumentsTable.clazz])
        assertEquals("FXUS", values[InstrumentsTable.symbol])
        assertFalse(values.containsKey(InstrumentsTable.currency))
    }

    @Test
    fun `maps instrument alias`() {
        val row =
            testResultRow(
                InstrumentAliasesTable.aliasId to 5L,
                InstrumentAliasesTable.instrumentId to 10L,
                InstrumentAliasesTable.alias to "SBERP",
                InstrumentAliasesTable.sourceCol to "manual",
            )

        val alias = row.toInstrumentAliasEntity()

        assertEquals(5L, alias.aliasId)
        assertEquals(10L, alias.instrumentId)
        assertEquals("SBERP", alias.alias)
        assertEquals("manual", alias.source)

        val insertValues = NewInstrumentAlias(instrumentId = 20L, alias = "GAZP", source = "import").toColumnValues()
        assertEquals(20L, insertValues[InstrumentAliasesTable.instrumentId])
        assertEquals("GAZP", insertValues[InstrumentAliasesTable.alias])
        assertEquals("import", insertValues[InstrumentAliasesTable.sourceCol])
    }
}
