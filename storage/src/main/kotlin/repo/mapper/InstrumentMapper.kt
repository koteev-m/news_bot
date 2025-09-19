package repo.mapper

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ResultRow
import repo.model.InstrumentAliasEntity
import repo.model.InstrumentEntity
import repo.model.InstrumentUpdate
import repo.model.NewInstrument
import repo.model.NewInstrumentAlias
import repo.tables.InstrumentAliasesTable
import repo.tables.InstrumentsTable

fun ResultRow.toInstrumentEntity(): InstrumentEntity = InstrumentEntity(
    instrumentId = this[InstrumentsTable.instrumentId],
    clazz = this[InstrumentsTable.clazz],
    exchange = this[InstrumentsTable.exchange],
    board = this[InstrumentsTable.board],
    symbol = this[InstrumentsTable.symbol],
    isin = this[InstrumentsTable.isin],
    cgId = this[InstrumentsTable.cgId],
    currency = this[InstrumentsTable.currency],
    createdAt = this[InstrumentsTable.createdAt].toInstant(),
)

fun NewInstrument.toColumnValues(): Map<Column<*>, Any?> = mapOf(
    InstrumentsTable.clazz to clazz,
    InstrumentsTable.exchange to exchange,
    InstrumentsTable.board to board,
    InstrumentsTable.symbol to symbol,
    InstrumentsTable.isin to isin,
    InstrumentsTable.cgId to cgId,
    InstrumentsTable.currency to currency,
    InstrumentsTable.createdAt to createdAt.toDbTimestamp(),
)

fun InstrumentUpdate.toColumnValues(): Map<Column<*>, Any?> {
    val values = mutableMapOf<Column<*>, Any?>()
    clazz?.let { values[InstrumentsTable.clazz] = it }
    exchange?.let { values[InstrumentsTable.exchange] = it }
    board?.let { values[InstrumentsTable.board] = it }
    symbol?.let { values[InstrumentsTable.symbol] = it }
    isin?.let { values[InstrumentsTable.isin] = it }
    cgId?.let { values[InstrumentsTable.cgId] = it }
    currency?.let { values[InstrumentsTable.currency] = it }
    return values
}

fun ResultRow.toInstrumentAliasEntity(): InstrumentAliasEntity = InstrumentAliasEntity(
    aliasId = this[InstrumentAliasesTable.aliasId],
    instrumentId = this[InstrumentAliasesTable.instrumentId],
    alias = this[InstrumentAliasesTable.alias],
    source = this[InstrumentAliasesTable.sourceCol],
)

fun NewInstrumentAlias.toColumnValues(): Map<Column<*>, Any?> = mapOf(
    InstrumentAliasesTable.instrumentId to instrumentId,
    InstrumentAliasesTable.alias to alias,
    InstrumentAliasesTable.sourceCol to source,
)
