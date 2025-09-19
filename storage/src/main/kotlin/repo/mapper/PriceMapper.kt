package repo.mapper

import model.PricePoint
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ResultRow
import repo.tables.PricesTable

fun ResultRow.toPricePoint(): PricePoint = PricePoint(
    instrumentId = this[PricesTable.instrumentId],
    ts = this[PricesTable.ts].toInstant(),
    price = this[PricesTable.price],
    ccy = this[PricesTable.ccy],
    source = this[PricesTable.sourceCol],
)

fun PricePoint.toColumnValues(): Map<Column<*>, Any?> = mapOf(
    PricesTable.instrumentId to instrumentId,
    PricesTable.ts to ts.toDbTimestamp(),
    PricesTable.price to price,
    PricesTable.ccy to ccy,
    PricesTable.sourceCol to source,
)
