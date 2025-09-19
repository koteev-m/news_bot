package portfolio.model

import java.time.LocalDate
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class ValuationDaily(
    @Contextual val date: LocalDate,
    val totalValue: Money,
    val unrealizedPnl: Money?,
    val realizedPnl: Money?
)
