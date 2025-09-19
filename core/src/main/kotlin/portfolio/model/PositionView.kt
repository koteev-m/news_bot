package portfolio.model

import java.math.BigDecimal
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class PositionView(
    val instrumentId: Long,
    val instrumentName: String,
    @Contextual val quantity: BigDecimal,
    val valuation: Money,
    val averageCost: Money?,
    val valuationMethod: ValuationMethod
)
