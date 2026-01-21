package portfolio.metrics

import cbr.CbrXmlDailyClient
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.util.Locale
import common.rethrowIfFatal
import portfolio.errors.PortfolioError
import portfolio.errors.PortfolioException

class CbrFxConverter(
    private val client: CbrXmlDailyClient,
) : FxConverter {
    override suspend fun convert(
        amount: BigDecimal,
        currency: String,
        date: LocalDate,
        baseCurrency: String,
    ): FxConversionResult {
        val normalizedCurrency = normalize(currency)
        val normalizedBase = normalize(baseCurrency)

        if (normalizedCurrency == normalizedBase) {
            return FxConversionResult(normalizeAmount(amount), delayed = false)
        }

        val rates = try {
            client.fetchDailyRates(date)
        } catch (ex: Throwable) {
            rethrowIfFatal(ex)
            throw PortfolioException(PortfolioError.FxRateUnavailable("Failed to load CBR FX rates", ex))
        }

        val ratesToRub = rates.ratesToRub
        val amountInRub = if (normalizedCurrency == RUB) {
            amount
        } else {
            val rate = ratesToRub[normalizedCurrency]
                ?: throw PortfolioException(
                    PortfolioError.FxRateNotFound(normalizedCurrency, date, rates.effectiveDate),
                )
            amount.multiply(rate, MATH_CONTEXT)
        }

        val converted = if (normalizedBase == RUB) {
            amountInRub
        } else {
            val baseRate = ratesToRub[normalizedBase]
                ?: throw PortfolioException(
                    PortfolioError.FxRateNotFound(normalizedBase, date, rates.effectiveDate),
                )
            if (baseRate.compareTo(BigDecimal.ZERO) == 0) {
                throw PortfolioException(
                    PortfolioError.Validation("FX rate for $normalizedBase equals zero and cannot be used"),
                )
            }
            amountInRub.divide(baseRate, MATH_CONTEXT)
        }

        return FxConversionResult(normalizeAmount(converted), delayed = rates.delayed)
    }

    private fun normalize(value: String): String = value.trim().uppercase(Locale.ROOT)

    private fun normalizeAmount(value: BigDecimal): BigDecimal = value.setScale(SCALE, RoundingMode.HALF_UP)

    companion object {
        private const val RUB = "RUB"
        private const val SCALE = 8
        private val MATH_CONTEXT = MathContext.DECIMAL128
    }
}
