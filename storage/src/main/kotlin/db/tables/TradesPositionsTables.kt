package db.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import java.math.BigDecimal

object TradesTable : Table("trades") {
    val tradeId = long("trade_id").autoIncrement()
    val portfolioId = uuid("portfolio_id").references(PortfoliosTable.portfolioId, onDelete = ReferenceOption.CASCADE)
    val instrumentId = long("instrument_id").references(InstrumentsTable.instrumentId, onDelete = ReferenceOption.RESTRICT)
    val datetime = timestampWithTimeZone("datetime")
    val side = text("side")
    val quantity = decimal("quantity", 20, 8)
    val price = decimal("price", 20, 8)
    val priceCurrency = char("price_currency", 3)
    val fee = decimal("fee", 20, 8).default(BigDecimal.ZERO)
    val feeCurrency = char("fee_currency", 3).default("RUB")
    val tax = decimal("tax", 20, 8).default(BigDecimal.ZERO)
    val taxCurrency = char("tax_currency", 3).nullable()
    val broker = text("broker").nullable()
    val note = text("note").nullable()
    val extId = text("ext_id").nullable().uniqueIndex()
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(tradeId)
    init {
        uniqueIndex(
            "uk_trade_dedup",
            portfolioId,
            instrumentId,
            datetime,
            side,
            quantity,
            price
        )
        index("idx_trades_portfolio_time", false, portfolioId, datetime)
    }
}

object PositionsTable : Table("positions") {
    val portfolioId = uuid("portfolio_id").references(PortfoliosTable.portfolioId, onDelete = ReferenceOption.CASCADE)
    val instrumentId = long("instrument_id").references(InstrumentsTable.instrumentId, onDelete = ReferenceOption.RESTRICT)
    val qty = decimal("qty", 20, 8).default(BigDecimal.ZERO)
    val avgPrice = decimal("avg_price", 20, 8).nullable()
    val avgPriceCcy = char("avg_price_ccy", 3).nullable()
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(portfolioId, instrumentId)
}
