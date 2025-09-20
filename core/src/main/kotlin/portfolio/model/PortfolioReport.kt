package portfolio.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class PortfolioReport(
    @Contextual val portfolioId: UUID,
    val period: DateRange,
    val valuationMethod: ValuationMethod,
    val valuations: List<ValuationDaily>,
    val realized: List<RealizedPnlEntry>,
    val totals: ReportTotals,
    val assetClassContribution: List<ContributionBreakdown>,
    val sectorContribution: List<ContributionBreakdown>,
    val topPositions: List<TopPosition>,
    @Contextual val generatedAt: Instant,
)

@Serializable
data class ReportTotals(
    val realized: Money,
    val unrealizedChange: Money,
    val total: Money,
    val averageDaily: Money,
    @Contextual val maxDrawdown: BigDecimal,
)

@Serializable
data class ContributionBreakdown(
    val key: String,
    val amount: Money,
    @Contextual val weight: BigDecimal?,
)

@Serializable
data class TopPosition(
    val instrumentId: Long,
    val instrumentName: String,
    val valuation: Money,
    val unrealizedPnl: Money,
    @Contextual val weight: BigDecimal?,
    val assetClass: String?,
    val sector: String?,
)

@Serializable
data class RealizedPnlEntry(
    val instrumentId: Long,
    val instrumentName: String?,
    @Contextual val tradeDate: LocalDate,
    val amount: Money,
    val assetClass: String?,
    val sector: String?,
    val positions: List<PositionView>,
    val trades: List<TradeView>,
    val valuations: List<ValuationDaily>,
    @Contextual val generatedAt: Instant
)
