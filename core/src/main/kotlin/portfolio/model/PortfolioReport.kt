package portfolio.model

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class PortfolioReport(
    @Contextual val portfolioId: UUID,
    val period: DateRange,
    val valuationMethod: ValuationMethod,
    val positions: List<PositionView>,
    val trades: List<TradeView>,
    val valuations: List<ValuationDaily>,
    @Contextual val generatedAt: Instant
)
