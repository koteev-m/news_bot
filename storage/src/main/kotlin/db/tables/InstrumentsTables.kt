package db.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
object InstrumentsTable : Table("instruments") {
    val instrumentId = long("instrument_id").autoIncrement()
    val clazz = text("class")
    val exchange = text("exchange")
    val board = text("board").nullable()
    val symbol = text("symbol")
    val isin = text("isin").nullable().uniqueIndex()
    val cgId = text("cg_id").nullable()
    val currency = char("currency", 3)
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(instrumentId)
    init {
        uniqueIndex("uk_instruments_exchange_board_symbol", exchange, board, symbol)
        index("idx_instruments_symbol", false, symbol)
    }
}

object InstrumentAliasesTable : Table("instrument_aliases") {
    val aliasId = long("alias_id").autoIncrement()
    val instrumentId = long(
        "instrument_id"
    ).references(InstrumentsTable.instrumentId, onDelete = ReferenceOption.CASCADE)
    val alias = text("alias")
    val sourceCol = text("source")
    override val primaryKey = PrimaryKey(aliasId)
    init {
        uniqueIndex("uk_alias_source", alias, sourceCol)
        index("idx_aliases_instr", false, instrumentId)
    }
}
