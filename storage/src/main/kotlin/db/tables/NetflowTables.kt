package db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date

object MoexNetflow2Table : Table("moex_netflow2") {
    val date = date("date")
    val ticker = text("ticker")
    val p30 = long("p30").nullable()
    val p70 = long("p70").nullable()
    val p100 = long("p100").nullable()
    val pv30 = long("pv30").nullable()
    val pv70 = long("pv70").nullable()
    val pv100 = long("pv100").nullable()
    val vol = long("vol").nullable()
    val oi = long("oi").nullable()

    override val primaryKey = PrimaryKey(date, ticker)

    init {
        index("idx_moex_netflow2_ticker_date", false, ticker, date)
    }
}
