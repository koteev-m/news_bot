package portfolio.metrics

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.pow

private val MATH_CONTEXT = MathContext.DECIMAL128
private val ZERO = BigDecimal.ZERO
private val ONE = BigDecimal.ONE
private const val SCALE = 8

fun computePnlSeries(
    valuations: List<ValuationEntry>,
    cashflows: List<CashflowEntry>,
): List<PnlPoint> {
    if (valuations.isEmpty()) {
        return emptyList()
    }

    val sorted = valuations.sortedBy { it.date }
    val sortedCashflows = cashflows.sortedBy { it.date }
    var cashflowIndex = 0
    var cumulativeCashflow = ZERO
    var previousTotal = ZERO

    return sorted.map { valuation ->
        var cashflow = ZERO
        while (cashflowIndex < sortedCashflows.size &&
            !sortedCashflows[cashflowIndex].date.isAfter(valuation.date)
        ) {
            cashflow = cashflow.add(sortedCashflows[cashflowIndex].amount)
            cashflowIndex += 1
        }
        cumulativeCashflow = cumulativeCashflow.add(cashflow)
        val total = valuation.value.add(cumulativeCashflow)
        val daily = total.subtract(previousTotal)
        previousTotal = total
        PnlPoint(
            date = valuation.date,
            valuation = normalize(valuation.value),
            cashflow = normalize(cashflow),
            pnlDaily = normalize(daily),
            pnlTotal = normalize(total),
        )
    }
}

fun computeIrr(
    cashflows: List<CashflowEntry>,
    endDate: LocalDate,
    endValue: BigDecimal,
): IrrResult {
    val allFlows = cashflows + CashflowEntry(endDate, endValue)
    if (allFlows.size < 2) {
        return IrrResult(null, IrrStatus.INVALID_INPUT)
    }
    val nonZeroFlows = allFlows.count { it.amount.compareTo(ZERO) != 0 }
    if (nonZeroFlows < 2) {
        return IrrResult(null, IrrStatus.INVALID_INPUT)
    }

    val hasPositive = allFlows.any { it.amount > ZERO }
    val hasNegative = allFlows.any { it.amount < ZERO }
    if (!hasPositive || !hasNegative) {
        return IrrResult(null, IrrStatus.NO_ROOT)
    }

    val startDate = allFlows.minBy { it.date }.date
    val flows = allFlows.sortedBy { it.date }
    val npv: (Double) -> Double = { rate ->
        if (rate <= -0.999999) {
            Double.NaN
        } else {
            flows.sumOf { flow ->
                val days = ChronoUnit.DAYS.between(startDate, flow.date)
                val years = days.toDouble() / 365.0
                val denom = (1.0 + rate).pow(years)
                flow.amount.toDouble() / denom
            }
        }
    }

    var lo = -0.9999
    var hi = 1.0
    var npvLo = npv(lo)
    var npvHi = npv(hi)
    var brackets = 0
    while (npvLo * npvHi > 0 && hi < 1024 && brackets < 100) {
        hi *= 2
        npvHi = npv(hi)
        brackets += 1
    }
    if (npvLo * npvHi > 0) {
        return IrrResult(null, IrrStatus.NO_ROOT, iterations = brackets)
    }

    val tolerance = 1e-7
    var mid = 0.0
    for (i in 0 until 100) {
        mid = (lo + hi) / 2.0
        val npvMid = npv(mid)
        if (!npvMid.isFinite()) {
            return IrrResult(null, IrrStatus.DIVERGED, iterations = i + 1)
        }
        if (abs(npvMid) < tolerance) {
            return IrrResult(mid, IrrStatus.OK, iterations = i + 1)
        }
        if (npvLo * npvMid <= 0) {
            hi = mid
            npvHi = npvMid
        } else {
            lo = mid
            npvLo = npvMid
        }
    }

    return IrrResult(mid, IrrStatus.DIVERGED, iterations = 100)
}

fun computeTwr(
    valuations: List<ValuationEntry>,
    cashflows: List<CashflowEntry>,
): TwrResult {
    if (valuations.size < 2) {
        return TwrResult(null, TwrStatus.INSUFFICIENT_DATA)
    }

    val sorted = valuations.sortedBy { it.date }
    val sortedCashflows = cashflows.sortedBy { it.date }
    var cashflowIndex = 0
    var accumulated = 1.0
    var previous = sorted.first().value
    var hasReturn = false
    val firstDate = sorted.first().date

    while (cashflowIndex < sortedCashflows.size &&
        !sortedCashflows[cashflowIndex].date.isAfter(firstDate)
    ) {
        cashflowIndex += 1
    }

    for (index in 1 until sorted.size) {
        val current = sorted[index]
        var cashflow = ZERO
        while (cashflowIndex < sortedCashflows.size &&
            !sortedCashflows[cashflowIndex].date.isAfter(current.date)
        ) {
            cashflow = cashflow.add(sortedCashflows[cashflowIndex].amount)
            cashflowIndex += 1
        }
        if (previous.compareTo(ZERO) <= 0) {
            previous = current.value
            continue
        }
        val numerator = current.value.add(cashflow)
        val daily = numerator.divide(previous, MATH_CONTEXT).subtract(ONE)
        val dailyRate = daily.toDouble()
        if (!dailyRate.isFinite()) {
            return TwrResult(null, TwrStatus.INVALID_INPUT)
        }
        accumulated *= 1.0 + dailyRate
        hasReturn = true
        previous = current.value
    }

    if (!hasReturn) {
        return TwrResult(null, TwrStatus.INSUFFICIENT_DATA)
    }

    val result = accumulated - 1.0
    if (!result.isFinite()) {
        return TwrResult(null, TwrStatus.INVALID_INPUT)
    }
    return TwrResult(result, TwrStatus.OK)
}

private fun normalize(value: BigDecimal): BigDecimal = value.setScale(SCALE, RoundingMode.HALF_UP)
