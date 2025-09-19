package repo.mapper

import model.TradeDto
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ResultRow
import repo.model.NewTrade
import repo.model.TradeUpdate
import repo.tables.TradesTable

fun ResultRow.toTradeDto(): TradeDto = TradeDto(
    tradeId = this[TradesTable.tradeId],
    portfolioId = this[TradesTable.portfolioId],
    instrumentId = this[TradesTable.instrumentId],
    datetime = this[TradesTable.datetime].toInstant(),
    side = this[TradesTable.side],
    quantity = this[TradesTable.quantity],
    price = this[TradesTable.price],
    priceCurrency = this[TradesTable.priceCurrency],
    fee = this[TradesTable.fee],
    feeCurrency = this[TradesTable.feeCurrency],
    tax = this[TradesTable.tax],
    taxCurrency = this[TradesTable.taxCurrency],
    broker = this[TradesTable.broker],
    note = this[TradesTable.note],
    extId = this[TradesTable.extId],
    createdAt = this[TradesTable.createdAt].toInstant(),
)

fun NewTrade.toColumnValues(): Map<Column<*>, Any?> {
    val values = mutableMapOf<Column<*>, Any?>(
        TradesTable.portfolioId to portfolioId,
        TradesTable.instrumentId to instrumentId,
        TradesTable.datetime to datetime.toDbTimestamp(),
        TradesTable.side to side,
        TradesTable.quantity to quantity,
        TradesTable.price to price,
        TradesTable.priceCurrency to priceCurrency,
        TradesTable.fee to fee,
        TradesTable.feeCurrency to feeCurrency,
        TradesTable.createdAt to createdAt.toDbTimestamp(),
    )
    tax?.let { values[TradesTable.tax] = it }
    taxCurrency?.let { values[TradesTable.taxCurrency] = it }
    broker?.let { values[TradesTable.broker] = it }
    note?.let { values[TradesTable.note] = it }
    extId?.let { values[TradesTable.extId] = it }
    return values
}

fun TradeUpdate.toColumnValues(): Map<Column<*>, Any?> {
    val values = mutableMapOf<Column<*>, Any?>()
    datetime?.let { values[TradesTable.datetime] = it.toDbTimestamp() }
    side?.let { values[TradesTable.side] = it }
    quantity?.let { values[TradesTable.quantity] = it }
    price?.let { values[TradesTable.price] = it }
    priceCurrency?.let { values[TradesTable.priceCurrency] = it }
    fee?.let { values[TradesTable.fee] = it }
    feeCurrency?.let { values[TradesTable.feeCurrency] = it }
    tax?.let { values[TradesTable.tax] = it }
    taxCurrency?.let { values[TradesTable.taxCurrency] = it }
    broker?.let { values[TradesTable.broker] = it }
    note?.let { values[TradesTable.note] = it }
    return values
}
