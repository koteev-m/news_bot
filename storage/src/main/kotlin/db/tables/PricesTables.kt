package db.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object PricesTable : Table("prices") {
    val instrumentId = long(
        "instrument_id"
    ).references(InstrumentsTable.instrumentId, onDelete = ReferenceOption.CASCADE)
    val ts = timestampWithTimeZone("ts")
    val price = decimal("price", 20, 8)
    val ccy = char("ccy", 3)
    val sourceCol = text("source")
    override val primaryKey = PrimaryKey(instrumentId, ts)
    init {
        index("idx_prices_ts", false, ts)
    }
}
