package portfolio.service

import portfolio.errors.DomainResult
import portfolio.model.ContributionBreakdown
import portfolio.model.DateRange
import portfolio.model.Money
import portfolio.model.PortfolioReport
import portfolio.model.RealizedPnlEntry
import portfolio.model.ReportTotals
import portfolio.model.TopPosition
import portfolio.model.ValuationDaily
import portfolio.model.ValuationMethod
import java.math.BigDecimal
import java.math.MathContext
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

class ReportService(
    private val storage: Storage,
    private val clock: Clock = Clock.systemUTC(),
    private val baseCurrency: String = BASE_CURRENCY,
) {
    suspend fun getPortfolioReport(
        portfolioId: UUID,
        range: DateRange,
    ): DomainResult<PortfolioReport> {
        val method =
            storage.valuationMethod(portfolioId).fold(
                onSuccess = { it },
                onFailure = { return DomainResult.failure(it) },
            )

        val valuationRecords =
            storage.listValuations(portfolioId, range).fold(
                onSuccess = { records -> records.sortedBy { it.date } },
                onFailure = { return DomainResult.failure(it) },
            )

        val baseline =
            storage.latestValuationBefore(portfolioId, range.from).fold(
                onSuccess = { it },
                onFailure = { return DomainResult.failure(it) },
            )

        val realizedTrades =
            storage.listRealizedPnl(portfolioId, range).fold(
                onSuccess = { trades -> trades.sortedBy { it.tradeDate } },
                onFailure = { return DomainResult.failure(it) },
            )

        val holdings =
            storage.listHoldings(portfolioId, range.to).fold(
                onSuccess = { it },
                onFailure = { return DomainResult.failure(it) },
            )

        val valuations = valuationRecords.map { it.toValuationDaily() }
        val realizedEntries = realizedTrades.map { it.toEntry() }

        val totals = computeTotals(baseline, valuationRecords, realizedTrades, range)
        val assetClassContribution = computeContribution(holdings) { holding -> holding.assetClass }
        val sectorContribution = computeContribution(holdings) { holding -> holding.sector }
        val topPositions = computeTopPositions(holdings)

        val report =
            PortfolioReport(
                portfolioId = portfolioId,
                period = range,
                valuationMethod = method,
                valuations = valuations,
                realized = realizedEntries,
                totals = totals,
                assetClassContribution = assetClassContribution,
                sectorContribution = sectorContribution,
                topPositions = topPositions,
                generatedAt = clock.instant(),
            )

        return DomainResult.success(report)
    }

    private fun computeTotals(
        baseline: Storage.ValuationRecord?,
        valuations: List<Storage.ValuationRecord>,
        realizedTrades: List<Storage.RealizedTrade>,
        range: DateRange,
    ): ReportTotals {
        val baselineUnrealized = baseline?.pnlTotal ?: zero()
        val lastUnrealized = valuations.lastOrNull()?.pnlTotal ?: baselineUnrealized
        val unrealizedChange = lastUnrealized - baselineUnrealized

        val realizedTotal = realizedTrades.fold(zero()) { acc, trade -> acc + trade.amount }
        val total = realizedTotal + unrealizedChange

        val divisor = BigDecimal.valueOf(range.lengthInDays)
        val averageAmount =
            if (divisor.compareTo(BigDecimal.ZERO) == 0) {
                BigDecimal.ZERO
            } else {
                total.amount.divide(divisor, MATH_CONTEXT)
            }
        val average = Money.of(averageAmount, baseCurrency)

        val drawdowns =
            buildList {
                baseline?.let { add(it.drawdown) }
                valuations.forEach { add(it.drawdown) }
            }
        val maxDrawdown = drawdowns.minOrNull()?.let { normalize(it) } ?: BigDecimal.ZERO

        return ReportTotals(
            realized = realizedTotal,
            unrealizedChange = unrealizedChange,
            total = total,
            averageDaily = average,
            maxDrawdown = maxDrawdown,
        )
    }

    private fun computeContribution(
        holdings: List<Storage.Holding>,
        selector: (Storage.Holding) -> String?,
    ): List<ContributionBreakdown> {
        if (holdings.isEmpty()) {
            return emptyList()
        }

        val totalValueAmount = holdings.fold(BigDecimal.ZERO) { acc, holding -> acc + holding.valuation.amount }
        if (totalValueAmount.compareTo(BigDecimal.ZERO) == 0) {
            return holdings
                .mapNotNull { holding -> selector(holding)?.let { ContributionBreakdown(it, holding.valuation, null) } }
                .groupBy { it.key }
                .map { (key, items) ->
                    val combined = items.fold(zero()) { acc, item -> acc + item.amount }
                    ContributionBreakdown(key, combined, null)
                }.sortedByDescending { it.amount.amount }
        }

        val grouped = holdings.groupBy(selector)
        return grouped
            .mapNotNull { (key, items) ->
                key?.let {
                    val amount = items.fold(zero()) { acc, holding -> acc + holding.valuation }
                    val weight = normalize(amount.amount.divide(totalValueAmount, MATH_CONTEXT))
                    ContributionBreakdown(it, amount, weight)
                }
            }.sortedByDescending { it.amount.amount }
    }

    private fun computeTopPositions(holdings: List<Storage.Holding>): List<TopPosition> {
        if (holdings.isEmpty()) {
            return emptyList()
        }

        val sorted = holdings.sortedByDescending { it.valuation.amount }
        val denominator = sorted.fold(BigDecimal.ZERO) { acc, holding -> acc + holding.valuation.amount }

        return sorted
            .take(TOP_POSITIONS_LIMIT)
            .map { holding ->
                val weight =
                    if (denominator.compareTo(BigDecimal.ZERO) == 0) {
                        null
                    } else {
                        normalize(holding.valuation.amount.divide(denominator, MATH_CONTEXT))
                    }
                TopPosition(
                    instrumentId = holding.instrumentId,
                    instrumentName = holding.instrumentName,
                    valuation = holding.valuation,
                    unrealizedPnl = holding.unrealizedPnl,
                    weight = weight,
                    assetClass = holding.assetClass,
                    sector = holding.sector,
                )
            }
    }

    private fun zero(): Money = Money.of(BigDecimal.ZERO, baseCurrency)

    private fun normalize(value: BigDecimal): BigDecimal {
        val stripped = value.stripTrailingZeros()
        return if (stripped.scale() < 0) stripped.setScale(0) else stripped
    }

    private fun Storage.ValuationRecord.toValuationDaily(): ValuationDaily =
        ValuationDaily(
            date = date,
            valueRub = value,
            pnlDay = pnlDay,
            pnlTotal = pnlTotal,
            drawdown = normalize(drawdown),
        )

    private fun Storage.RealizedTrade.toEntry(): RealizedPnlEntry =
        RealizedPnlEntry(
            instrumentId = instrumentId,
            instrumentName = instrumentName,
            tradeDate = tradeDate,
            amount = amount,
            assetClass = assetClass,
            sector = sector,
        )

    interface Storage {
        suspend fun valuationMethod(portfolioId: UUID): DomainResult<ValuationMethod>

        suspend fun listValuations(
            portfolioId: UUID,
            range: DateRange,
        ): DomainResult<List<ValuationRecord>>

        suspend fun latestValuationBefore(
            portfolioId: UUID,
            date: LocalDate,
        ): DomainResult<ValuationRecord?>

        suspend fun listRealizedPnl(
            portfolioId: UUID,
            range: DateRange,
        ): DomainResult<List<RealizedTrade>>

        suspend fun listHoldings(
            portfolioId: UUID,
            asOf: LocalDate,
        ): DomainResult<List<Holding>>

        data class ValuationRecord(
            val date: LocalDate,
            val value: Money,
            val pnlDay: Money,
            val pnlTotal: Money,
            val drawdown: BigDecimal,
        )

        data class RealizedTrade(
            val tradeDate: LocalDate,
            val amount: Money,
            val instrumentId: Long,
            val instrumentName: String?,
            val assetClass: String?,
            val sector: String?,
        )

        data class Holding(
            val instrumentId: Long,
            val instrumentName: String,
            val valuation: Money,
            val unrealizedPnl: Money,
            val assetClass: String?,
            val sector: String?,
        )
    }

    private companion object {
        private const val BASE_CURRENCY = "RUB"
        private val MATH_CONTEXT: MathContext = MathContext.DECIMAL128
        private const val TOP_POSITIONS_LIMIT = 5
    }
}
