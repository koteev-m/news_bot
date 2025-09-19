package portfolio.service

import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.util.UUID
import portfolio.errors.DomainResult
import portfolio.model.Money
import portfolio.model.ValuationDaily

class ValuationService(
    private val storage: Storage,
    private val pricingService: PricingService,
    private val fxRateService: FxRateService,
    private val baseCurrency: String = BASE_CURRENCY,
) {
    suspend fun revaluePortfolioOn(portfolioId: UUID, date: LocalDate): DomainResult<ValuationDaily> {
        val positionsResult = storage.listPositions(portfolioId)
        if (positionsResult.isFailure) {
            return DomainResult.failure(positionsResult.exceptionOrNull()!!)
        }
        val positions = positionsResult.getOrDefault(emptyList())

        var valuationCurrency = baseCurrency
        var totalValue = BigDecimal.ZERO
        var totalCost = BigDecimal.ZERO

        for (position in positions) {
            if (position.quantity <= BigDecimal.ZERO) continue

            val priceResult = pricingService.closeOrLast(position.instrumentId, date)
            if (priceResult.isFailure) {
                return DomainResult.failure(priceResult.exceptionOrNull()!!)
            }
            val price = priceResult.getOrThrow()
            val valuation = price * position.quantity
            valuationCurrency = valuation.currency
            totalValue = totalValue + valuation.amount

            val average = position.averagePrice ?: continue
            val convertedAverageResult = convertToCurrency(
                average,
                date,
                valuation.currency,
            )
            if (convertedAverageResult.isFailure) {
                return DomainResult.failure(convertedAverageResult.exceptionOrNull()!!)
            }
            val convertedAverage = convertedAverageResult.getOrThrow()
            val cost = convertedAverage * position.quantity
            totalCost = totalCost + cost.amount
        }

        val totalValueMoney = Money.of(totalValue, valuationCurrency)
        val totalCostMoney = Money.of(totalCost, valuationCurrency)

        val previousResult = storage.latestValuationBefore(portfolioId, date)
        if (previousResult.isFailure) {
            return DomainResult.failure(previousResult.exceptionOrNull()!!)
        }
        val previous = previousResult.getOrNull()

        val previousValueAmount = previous?.valueRub
        val totalValueAmount = totalValueMoney.amount
        val totalCostAmount = totalCostMoney.amount

        val pnlTotalAmount = totalValueAmount - totalCostAmount
        val baseForDay = previousValueAmount ?: totalCostAmount
        val pnlDayAmount = totalValueAmount - baseForDay

        val previousPeak = previous?.let { computePeakValue(it) }
        val peakValue = when {
            previousPeak == null -> totalValueAmount
            previousPeak < totalValueAmount -> totalValueAmount
            else -> previousPeak
        }
        val drawdownAmount = if (peakValue.compareTo(BigDecimal.ZERO) == 0) {
            BigDecimal.ZERO
        } else {
            totalValueAmount.divide(peakValue, MATH_CONTEXT).subtract(BigDecimal.ONE)
        }

        val record = Storage.ValuationRecord(
            portfolioId = portfolioId,
            date = date,
            valueRub = totalValueAmount,
            pnlDay = pnlDayAmount,
            pnlTotal = pnlTotalAmount,
            drawdown = normalize(drawdownAmount),
        )

        val persistedResult = storage.upsertValuation(record)
        if (persistedResult.isFailure) {
            return DomainResult.failure(persistedResult.exceptionOrNull()!!)
        }
        val persisted = persistedResult.getOrThrow()

        val valueMoney = Money.of(persisted.valueRub, valuationCurrency)
        val pnlDayMoney = Money.of(persisted.pnlDay, valuationCurrency)
        val pnlTotalMoney = Money.of(persisted.pnlTotal, valuationCurrency)

        return DomainResult.success(
            ValuationDaily(
                date = persisted.date,
                valueRub = valueMoney,
                pnlDay = pnlDayMoney,
                pnlTotal = pnlTotalMoney,
                drawdown = normalize(persisted.drawdown),
            ),
        )
    }

    private suspend fun convertToCurrency(
        money: Money,
        date: LocalDate,
        targetCurrency: String,
    ): DomainResult<Money> {
        if (money.currency == targetCurrency) {
            return DomainResult.success(money)
        }
        val rateResult = fxRateService.rateOn(date, money.currency, targetCurrency)
        if (rateResult.isFailure) {
            return DomainResult.failure(rateResult.exceptionOrNull()!!)
        }
        val rate = rateResult.getOrThrow()
        val amount = money.amount.multiply(rate, MATH_CONTEXT)
        return DomainResult.success(Money.of(amount, targetCurrency))
    }

    private fun computePeakValue(previous: Storage.ValuationRecord): BigDecimal {
        val denominator = BigDecimal.ONE + previous.drawdown
        return if (denominator.compareTo(BigDecimal.ZERO) == 0) {
            previous.valueRub
        } else {
            previous.valueRub.divide(denominator, MATH_CONTEXT)
        }
    }

    private fun normalize(value: BigDecimal): BigDecimal {
        val stripped = value.stripTrailingZeros()
        return if (stripped.scale() < 0) stripped.setScale(0) else stripped
    }

    interface Storage {
        suspend fun listPositions(portfolioId: UUID): DomainResult<List<PositionSnapshot>>
        suspend fun latestValuationBefore(portfolioId: UUID, date: LocalDate): DomainResult<ValuationRecord?>
        suspend fun upsertValuation(record: ValuationRecord): DomainResult<ValuationRecord>

        data class PositionSnapshot(
            val instrumentId: Long,
            val quantity: BigDecimal,
            val averagePrice: Money?,
        )

        data class ValuationRecord(
            val portfolioId: UUID,
            val date: LocalDate,
            val valueRub: BigDecimal,
            val pnlDay: BigDecimal,
            val pnlTotal: BigDecimal,
            val drawdown: BigDecimal,
        )
    }

    private companion object {
        private val MATH_CONTEXT: MathContext = MathContext.DECIMAL128
        private const val BASE_CURRENCY = "RUB"
    }
}
