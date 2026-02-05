package portfolio.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.math.BigDecimal

@Serializable
data class PositionView(
    val instrumentId: Long,
    val instrumentName: String,
    @Contextual val quantity: BigDecimal,
    val valuation: Money,
    val averageCost: Money?,
    val valuationMethod: ValuationMethod,
    val unrealizedPnl: Money,
)
