package portfolio.metrics

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.UUID
import portfolio.errors.DomainResult
import portfolio.errors.PortfolioError
import portfolio.errors.PortfolioException
import common.runCatchingNonFatal
import portfolio.model.TradeSide

class PortfolioMetricsService(
    private val storage: Storage,
    private val fxConverter: FxConverter,
    private val zoneId: ZoneId = ZoneOffset.UTC,
) {
    suspend fun buildReport(
        portfolioId: UUID,
        baseCurrency: String,
        period: MetricsPeriod,
    ): DomainResult<PortfolioMetricsReport> {
        return runCatchingNonFatal {
            val normalizedBase = normalizeCurrency(baseCurrency)

            val valuations = storage.listValuations(portfolioId)
                .getOrElse { throw it }
                .sortedBy { record -> record.date }
            if (valuations.isEmpty()) {
                throw PortfolioException(PortfolioError.Validation("No valuation data for portfolio $portfolioId"))
            }

            val trades = storage.listTrades(portfolioId).getOrElse { throw it }

            val endDate = valuations.last().date
            val valuationResult = convertValuations(valuations, normalizedBase)
            val cashflowResult = buildCashflows(trades, endDate, normalizedBase)

            val dailySeries = computePnlSeries(valuationResult.entries, cashflowResult.cashflows)
            val series = when (period) {
                MetricsPeriod.DAILY -> dailySeries.map { it.toSeriesPoint() }
                else -> buildPeriodSeries(period, valuationResult.entries, cashflowResult.cashflows)
            }

            val totalPnL = dailySeries.lastOrNull()?.pnlTotal ?: zero()
            val irr = computeIrr(cashflowResult.cashflows, endDate, valuationResult.entries.last().value)
            val twr = computeTwr(valuationResult.entries, cashflowResult.cashflows)

            val summary = MetricsSummary(
                totalPnL = totalPnL,
                irr = irr,
                twr = twr,
            )

            PortfolioMetricsReport(
                portfolioId = portfolioId,
                baseCurrency = normalizedBase,
                period = period,
                delayed = valuationResult.delayed || cashflowResult.delayed,
                summary = summary,
                series = series,
            )
        }
    }

    private suspend fun convertValuations(
        valuations: List<Storage.ValuationRecord>,
        baseCurrency: String,
    ): ConversionResult<ValuationEntry> {
        var delayed = false
        val entries = valuations.map { record ->
            val conversion = fxConverter.convert(record.valueRub, RUB, record.date, baseCurrency)
            if (conversion.delayed) {
                delayed = true
            }
            ValuationEntry(date = record.date, value = conversion.amount)
        }
        return ConversionResult(entries, delayed)
    }

    private suspend fun buildCashflows(
        trades: List<Storage.TradeRecord>,
        endDate: LocalDate,
        baseCurrency: String,
    ): CashflowResult {
        val cashflows = mutableListOf<CashflowEntry>()
        var delayed = false

        trades.forEach { trade ->
            val tradeDate = trade.datetime.atZone(zoneId).toLocalDate()
            if (tradeDate.isAfter(endDate)) {
                return@forEach
            }
            val side = parseSide(trade.side) ?: return@forEach
            val sign = if (side == TradeSide.BUY) NEGATIVE else POSITIVE
            val notional = trade.price.multiply(trade.quantity.abs()).multiply(sign)
            val components = buildList {
                add(AmountWithCurrency(notional, trade.priceCurrency))
                if (trade.fee.compareTo(ZERO) != 0) {
                    add(AmountWithCurrency(trade.fee.negate(), trade.feeCurrency))
                }
                trade.tax?.let { taxAmount ->
                    val currency = trade.taxCurrency ?: trade.feeCurrency
                    add(AmountWithCurrency(taxAmount.negate(), currency))
                }
            }

            var total = ZERO
            for (component in components) {
                val conversion = fxConverter.convert(component.amount, component.currency, tradeDate, baseCurrency)
                if (conversion.delayed) {
                    delayed = true
                }
                total = total.add(conversion.amount)
            }
            if (total.compareTo(ZERO) != 0) {
                cashflows += CashflowEntry(tradeDate, normalize(total))
            }
        }

        val aggregated = cashflows
            .groupBy { it.date }
            .mapValues { (_, items) ->
                items.fold(ZERO) { acc, item -> acc.add(item.amount) }
            }

        return CashflowResult(
            cashflows = aggregated.entries.sortedBy { it.key }.map { CashflowEntry(it.key, it.value) },
            delayed = delayed,
        )
    }

    private fun buildPeriodSeries(
        period: MetricsPeriod,
        valuations: List<ValuationEntry>,
        cashflows: List<CashflowEntry>,
    ): List<MetricsSeriesPoint> {
        val globalEnd = valuations.maxOf { it.date }
        val cashflowsByPeriod = cashflows
            .groupBy { entry -> periodKey(entry.date, period) }
            .mapValues { (_, items) ->
                items.fold(ZERO) { acc, item -> acc.add(item.amount) }
            }

        val valuationsByPeriod = valuations
            .groupBy { entry -> periodKey(entry.date, period) }
            .mapValues { (_, items) -> items.maxBy { it.date } }

        val orderedKeys = valuationsByPeriod.keys.sortedBy { it.start }
        val periodEndByKey = orderedKeys.associateWith { key ->
            if (key.end.isAfter(globalEnd)) globalEnd else key.end
        }
        val periodValuations = orderedKeys.map { key ->
            ValuationEntry(periodEndByKey.getValue(key), valuationsByPeriod.getValue(key).value)
        }
        val periodCashflows = orderedKeys.map { key ->
            CashflowEntry(periodEndByKey.getValue(key), cashflowsByPeriod[key] ?: ZERO)
        }
        val pnlSeries = computePnlSeries(periodValuations, periodCashflows)

        return pnlSeries.mapIndexed { index, point ->
            val key = orderedKeys[index]
            MetricsSeriesPoint(
                periodStart = key.start,
                periodEnd = periodEndByKey.getValue(key),
                valuation = point.valuation,
                cashflow = point.cashflow,
                pnlDaily = point.pnlDaily,
                pnlTotal = point.pnlTotal,
            )
        }
    }

    private fun periodKey(date: LocalDate, period: MetricsPeriod): PeriodKey = when (period) {
        MetricsPeriod.DAILY -> PeriodKey(date, date)
        MetricsPeriod.WEEKLY -> {
            val start = date.with(TemporalAdjusters.previousOrSame(WEEK_START))
            PeriodKey(start, start.plusDays(6))
        }
        MetricsPeriod.MONTHLY -> {
            val start = date.withDayOfMonth(1)
            PeriodKey(start, date.withDayOfMonth(date.lengthOfMonth()))
        }
    }

    private fun parseSide(raw: String): TradeSide? = try {
        TradeSide.valueOf(raw.trim().uppercase(Locale.ROOT))
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun normalizeCurrency(value: String): String = value.trim().uppercase(Locale.ROOT)

    private fun zero(): BigDecimal = ZERO.setScale(SCALE, RoundingMode.HALF_UP)

    private fun normalize(value: BigDecimal): BigDecimal = value.setScale(SCALE, RoundingMode.HALF_UP)

    private data class CashflowResult(
        val cashflows: List<CashflowEntry>,
        val delayed: Boolean,
    )

    private data class ConversionResult<T>(
        val entries: List<T>,
        val delayed: Boolean,
    )

    private data class AmountWithCurrency(
        val amount: BigDecimal,
        val currency: String,
    )

    private data class PeriodKey(
        val start: LocalDate,
        val end: LocalDate,
    )

    private fun PnlPoint.toSeriesPoint(): MetricsSeriesPoint = MetricsSeriesPoint(
        date = date,
        valuation = valuation,
        cashflow = cashflow,
        pnlDaily = pnlDaily,
        pnlTotal = pnlTotal,
    )

    interface Storage {
        suspend fun listValuations(portfolioId: UUID): DomainResult<List<ValuationRecord>>

        suspend fun listTrades(portfolioId: UUID): DomainResult<List<TradeRecord>>

        data class ValuationRecord(
            val date: LocalDate,
            val valueRub: BigDecimal,
        )

        data class TradeRecord(
            val datetime: Instant,
            val side: String,
            val quantity: BigDecimal,
            val price: BigDecimal,
            val priceCurrency: String,
            val fee: BigDecimal,
            val feeCurrency: String,
            val tax: BigDecimal?,
            val taxCurrency: String?,
        )
    }

    companion object {
        private const val RUB = "RUB"
        private val ZERO = BigDecimal.ZERO
        private val POSITIVE = BigDecimal.ONE
        private val NEGATIVE = BigDecimal.ONE.negate()
        private const val SCALE = 8
        private val WEEK_START = java.time.DayOfWeek.MONDAY
    }
}
