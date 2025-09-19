package repo.model

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Row model for the [repo.tables.ValuationsDailyTable]. */
data class ValuationDailyRecord(
    val portfolioId: UUID,
    val date: LocalDate,
    val valueRub: BigDecimal,
    val pnlDay: BigDecimal,
    val pnlTotal: BigDecimal,
    val drawdown: BigDecimal,
)

/** Creation payload for valuations. */
data class NewValuationDaily(
    val portfolioId: UUID,
    val date: LocalDate,
    val valueRub: BigDecimal,
    val pnlDay: BigDecimal,
    val pnlTotal: BigDecimal,
    val drawdown: BigDecimal,
)
