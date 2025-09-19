package portfolio.model

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class TradeView(
    @Contextual val tradeId: UUID,
    val instrumentId: Long,
    @Contextual val tradeDate: LocalDate,
    @Contextual val quantity: BigDecimal,
    val price: Money,
    val notional: Money
)
