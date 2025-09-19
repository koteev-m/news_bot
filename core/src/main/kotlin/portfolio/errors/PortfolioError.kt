package portfolio.errors

sealed interface PortfolioError {
    val message: String

    data class Validation(override val message: String) : PortfolioError
    data class NotFound(override val message: String) : PortfolioError
    data class External(
        override val message: String,
        val cause: Throwable? = null
    ) : PortfolioError
}

typealias DomainResult<T> = Result<T>

class PortfolioException(val error: PortfolioError) : RuntimeException(error.message)
