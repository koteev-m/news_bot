package routes.dto

import kotlinx.serialization.Serializable
import portfolio.metrics.PortfolioMetricsReport
import portfolio.metrics.MetricsSeriesPoint

@Serializable
data class PortfolioMetricsReportResponse(
    val portfolioId: String,
    val base: String,
    val period: String,
    val delayed: Boolean,
    val summary: PortfolioMetricsSummaryResponse,
    val series: List<PortfolioMetricsPointResponse>,
)

@Serializable
data class PortfolioMetricsSummaryResponse(
    val totalPnL: String,
    val irr: Double?,
    val irrStatus: String,
    val twr: Double?,
    val twrStatus: String,
)

@Serializable
data class PortfolioMetricsPointResponse(
    val date: String? = null,
    val periodStart: String? = null,
    val periodEnd: String? = null,
    val valuation: String,
    val cashflow: String,
    val pnlDaily: String,
    val pnlTotal: String,
)

fun PortfolioMetricsReport.toResponse(): PortfolioMetricsReportResponse = PortfolioMetricsReportResponse(
    portfolioId = portfolioId.toString(),
    base = baseCurrency,
    period = period.name.lowercase(),
    delayed = delayed,
    summary = PortfolioMetricsSummaryResponse(
        totalPnL = summary.totalPnL.toAmt(),
        irr = summary.irr.irr,
        irrStatus = summary.irr.status.name,
        twr = summary.twr.twr,
        twrStatus = summary.twr.status.name,
    ),
    series = series.map { it.toResponse() },
)

private fun MetricsSeriesPoint.toResponse(): PortfolioMetricsPointResponse = PortfolioMetricsPointResponse(
    date = date?.toString(),
    periodStart = periodStart?.toString(),
    periodEnd = periodEnd?.toString(),
    valuation = valuation.toAmt(),
    cashflow = cashflow.toAmt(),
    pnlDaily = pnlDaily.toAmt(),
    pnlTotal = pnlTotal.toAmt(),
)
