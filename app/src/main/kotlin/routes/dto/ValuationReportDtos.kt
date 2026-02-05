package routes.dto

import kotlinx.serialization.Serializable
import portfolio.model.PortfolioReport
import portfolio.model.TopPosition
import portfolio.model.ValuationDaily
import java.math.BigDecimal
import java.math.RoundingMode

@Serializable
data class ValuationDailyResponse(
    val date: String,
    val valueRub: String,
    val pnlDay: String,
    val pnlTotal: String,
    val drawdown: String,
)

@Serializable
data class PortfolioReportResponse(
    val from: String,
    val to: String,
    val totalPnl: String,
    val avgDailyPnl: String,
    val maxDrawdown: String,
    val topPositions: List<TopPositionItem> = emptyList(),
)

@Serializable
data class TopPositionItem(
    val instrumentId: Long,
    val weightPercent: String,
    val upl: MoneyDto,
)

fun BigDecimal.toAmt(): String = setScale(AMOUNT_SCALE, RoundingMode.HALF_UP).toPlainString()

fun ValuationDaily.toResponse(): ValuationDailyResponse =
    ValuationDailyResponse(
        date = date.toString(),
        valueRub = valueRub.amount.toAmt(),
        pnlDay = pnlDay.amount.toAmt(),
        pnlTotal = pnlTotal.amount.toAmt(),
        drawdown = drawdown.toAmt(),
    )

fun PortfolioReport.toResponse(): PortfolioReportResponse =
    PortfolioReportResponse(
        from = period.from.toString(),
        to = period.to.toString(),
        totalPnl = totals.total.amount.toAmt(),
        avgDailyPnl = totals.averageDaily.amount.toAmt(),
        maxDrawdown = totals.maxDrawdown.toAmt(),
        topPositions = topPositions.map { it.toItem() },
    )

private fun TopPosition.toItem(): TopPositionItem =
    TopPositionItem(
        instrumentId = instrumentId,
        weightPercent = (weight ?: BigDecimal.ZERO).multiply(PERCENT_MULTIPLIER).toAmt(),
        upl = unrealizedPnl.toDto(),
    )

private const val AMOUNT_SCALE = 8
private val PERCENT_MULTIPLIER: BigDecimal = BigDecimal("100")
