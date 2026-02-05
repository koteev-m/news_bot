package portfolio.errors

import java.time.LocalDate

sealed interface PortfolioError {
    val message: String

    data class Validation(
        override val message: String,
    ) : PortfolioError

    data class NotFound(
        override val message: String,
    ) : PortfolioError

    data class FxRateNotFound(
        val currency: String,
        val requestedDate: LocalDate,
        val effectiveDate: LocalDate,
    ) : PortfolioError {
        override val message: String =
            "FX rate for $currency not found on $requestedDate (effective $effectiveDate)"
    }

    data class FxRateUnavailable(
        override val message: String,
        val cause: Throwable? = null,
    ) : PortfolioError

    data class External(
        override val message: String,
        val cause: Throwable? = null,
    ) : PortfolioError
}

typealias DomainResult<T> = Result<T>

class PortfolioException(
    val error: PortfolioError,
) : RuntimeException(error.message)
