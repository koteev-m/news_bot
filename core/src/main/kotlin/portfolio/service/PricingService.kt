package portfolio.service

import portfolio.errors.DomainResult
import portfolio.errors.PortfolioError
import portfolio.errors.PortfolioException
import portfolio.model.Money
import java.time.LocalDate
import java.util.Locale

class PricingService(
    private val moexProvider: MoexPriceProvider,
    private val coingeckoProvider: CoingeckoPriceProvider,
    private val fxRateService: FxRateService,
    private val config: Config = Config(),
) {
    suspend fun closeOrLast(
        instrumentId: Long,
        on: LocalDate,
    ): DomainResult<Money> {
        for (priceType in priceTypes()) {
            for (provider in providers) {
                when (val outcome = fetch(provider, priceType, instrumentId, on)) {
                    is FetchOutcome.Success -> return DomainResult.success(outcome.value)
                    is FetchOutcome.Failure -> return DomainResult.failure(outcome.cause)
                    FetchOutcome.Missing -> continue
                }
            }
        }

        return failure(
            PortfolioError.NotFound("No price available for instrument $instrumentId on $on"),
        )
    }

    private fun priceTypes(): List<PriceType> {
        val primary = if (config.preferClosePrice) PriceType.CLOSE else PriceType.LAST
        if (!config.fallbackToLast) return listOf(primary)

        val secondary = if (primary == PriceType.CLOSE) PriceType.LAST else PriceType.CLOSE
        return listOf(primary, secondary)
    }

    private suspend fun fetch(
        provider: PriceProvider,
        type: PriceType,
        instrumentId: Long,
        on: LocalDate,
    ): FetchOutcome {
        val rawResult =
            when (type) {
                PriceType.CLOSE -> provider.closePrice(instrumentId, on)
                PriceType.LAST -> provider.lastPrice(instrumentId, on)
            }

        rawResult.exceptionOrNull()?.let { throwable ->
            val domainError = (throwable as? PortfolioException)?.error
            return if (domainError is PortfolioError.NotFound) {
                FetchOutcome.Missing
            } else {
                FetchOutcome.Failure(throwable)
            }
        }

        val money = rawResult.getOrNull() ?: return FetchOutcome.Missing
        val converted = convertToBase(money, on)
        return if (converted.isSuccess) {
            FetchOutcome.Success(converted.getOrThrow())
        } else {
            FetchOutcome.Failure(converted.exceptionOrNull()!!)
        }
    }

    private suspend fun convertToBase(
        price: Money,
        on: LocalDate,
    ): DomainResult<Money> {
        val baseCurrency = config.baseCurrency
        if (price.ccy == baseCurrency) {
            return DomainResult.success(price)
        }

        val rateResult = fxRateService.rateOn(on, price.ccy, baseCurrency)
        return rateResult.fold(
            onSuccess = { rate ->
                val amountInBase = price.amount.multiply(rate)
                DomainResult.success(Money.of(amountInBase, baseCurrency))
            },
            onFailure = { throwable -> DomainResult.failure(throwable) },
        )
    }

    private fun <T> failure(error: PortfolioError): DomainResult<T> = DomainResult.failure(PortfolioException(error))

    class Config(
        val preferClosePrice: Boolean = true,
        val fallbackToLast: Boolean = true,
        baseCurrency: String = DEFAULT_BASE_CURRENCY,
    ) {
        val baseCurrency: String = baseCurrency.trim().uppercase(Locale.ROOT)

        init {
            require(CURRENCY_REGEX.matches(this.baseCurrency)) { "Base currency must be a valid ISO 4217 code" }
        }

        companion object {
            const val DEFAULT_BASE_CURRENCY: String = "RUB"
            private val CURRENCY_REGEX = Regex("^[A-Z]{3}$")
        }
    }

    private enum class PriceType { CLOSE, LAST }

    private sealed interface FetchOutcome {
        data class Success(
            val value: Money,
        ) : FetchOutcome

        data class Failure(
            val cause: Throwable,
        ) : FetchOutcome

        object Missing : FetchOutcome
    }

    private val providers: List<PriceProvider> = listOf(moexProvider, coingeckoProvider)
}

interface PriceProvider {
    suspend fun closePrice(
        instrumentId: Long,
        on: LocalDate,
    ): DomainResult<Money?>

    suspend fun lastPrice(
        instrumentId: Long,
        on: LocalDate,
    ): DomainResult<Money?>
}

interface MoexPriceProvider : PriceProvider

interface CoingeckoPriceProvider : PriceProvider
