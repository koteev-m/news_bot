package portfolio.metrics

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

enum class MetricsPeriod {
    DAILY,
    WEEKLY,
    MONTHLY,
}

enum class IrrStatus {
    OK,
    NO_ROOT,
    INVALID_INPUT,
    DIVERGED,
}

enum class TwrStatus {
    OK,
    INSUFFICIENT_DATA,
    INVALID_INPUT,
}

data class CashflowEntry(
    val date: LocalDate,
    val amount: BigDecimal,
)

data class ValuationEntry(
    val date: LocalDate,
    val value: BigDecimal,
)

data class PnlPoint(
    val date: LocalDate,
    val valuation: BigDecimal,
    val cashflow: BigDecimal,
    val pnlDaily: BigDecimal,
    val pnlTotal: BigDecimal,
)

data class IrrResult(
    val irr: Double?,
    val status: IrrStatus,
    val iterations: Int? = null,
)

data class TwrResult(
    val twr: Double?,
    val status: TwrStatus,
)

data class MetricsSummary(
    val totalPnL: BigDecimal,
    val irr: IrrResult,
    val twr: TwrResult,
)

data class MetricsSeriesPoint(
    val date: LocalDate? = null,
    val periodStart: LocalDate? = null,
    val periodEnd: LocalDate? = null,
    val valuation: BigDecimal,
    val cashflow: BigDecimal,
    val pnlDaily: BigDecimal,
    val pnlTotal: BigDecimal,
)

data class PortfolioMetricsReport(
    val portfolioId: UUID,
    val baseCurrency: String,
    val period: MetricsPeriod,
    val delayed: Boolean,
    val summary: MetricsSummary,
    val series: List<MetricsSeriesPoint>,
)

data class FxConversionResult(
    val amount: BigDecimal,
    val delayed: Boolean,
)

interface FxConverter {
    suspend fun convert(
        amount: BigDecimal,
        currency: String,
        date: LocalDate,
        baseCurrency: String,
    ): FxConversionResult
}
