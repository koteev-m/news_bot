package portfolio.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.LocalDate

@Serializable
data class ValuationDaily(
    @Contextual val date: LocalDate,
    val valueRub: Money,
    val pnlDay: Money,
    val pnlTotal: Money,
    @Contextual val drawdown: BigDecimal,
)
