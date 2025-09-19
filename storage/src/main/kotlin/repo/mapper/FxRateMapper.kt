package repo.mapper

import model.FxRate
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ResultRow
import repo.tables.FxRatesTable

fun ResultRow.toFxRate(): FxRate = FxRate(
    ccy = this[FxRatesTable.ccy],
    ts = this[FxRatesTable.ts].toInstant(),
    rateRub = this[FxRatesTable.rateRub],
    source = this[FxRatesTable.sourceCol],
)

fun FxRate.toColumnValues(): Map<Column<*>, Any?> = mapOf(
    FxRatesTable.ccy to ccy,
    FxRatesTable.ts to ts.toDbTimestamp(),
    FxRatesTable.rateRub to rateRub,
    FxRatesTable.sourceCol to source,
)
