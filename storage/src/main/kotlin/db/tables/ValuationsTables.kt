package db.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import java.math.BigDecimal

object ValuationsDailyTable : Table("valuations_daily") {
    val portfolioId = uuid("portfolio_id").references(PortfoliosTable.portfolioId, onDelete = ReferenceOption.CASCADE)
    val date = date("date")
    val valueRub = decimal("value_rub", 24, 8)
    val pnlDay = decimal("pnl_day", 24, 8).default(BigDecimal.ZERO)
    val pnlTotal = decimal("pnl_total", 24, 8).default(BigDecimal.ZERO)
    val drawdown = decimal("drawdown", 24, 8).default(BigDecimal.ZERO)
    override val primaryKey = PrimaryKey(portfolioId, date)
}
