package portfolio.model

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class TradeView(
    @Contextual val tradeId: UUID,
    @Contextual val portfolioId: UUID,
    val instrumentId: Long,
    @Contextual val tradeDate: LocalDate,
    val side: TradeSide,
    @Contextual val quantity: BigDecimal,
    val price: Money,
    val fee: Money = Money.of(BigDecimal.ZERO, price.currency),
    val tax: Money? = null,
    val notional: Money = price * quantity.abs()
    val instrumentId: Long,
    @Contextual val tradeDate: LocalDate,
    @Contextual val quantity: BigDecimal,
    val price: Money,
    val notional: Money
)
