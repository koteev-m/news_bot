package db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object FxRatesTable : Table("fx_rates") {
    val ccy = char("ccy", 3)
    val ts = timestampWithTimeZone("ts")
    val rateRub = decimal("rate_rub", 20, 8)
    val sourceCol = text("source")
    override val primaryKey = PrimaryKey(ccy, ts)
}
