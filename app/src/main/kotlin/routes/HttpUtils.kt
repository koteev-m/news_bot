package routes

import errors.AppException
import io.ktor.server.application.ApplicationCall
import org.jetbrains.exposed.exceptions.ExposedSQLException
import portfolio.errors.PortfolioError
import portfolio.errors.PortfolioException
import java.sql.SQLException

fun ApplicationCall.respondBadRequest(details: List<String>): Nothing = throw AppException.BadRequest(details)

fun ApplicationCall.respondUnauthorized(): Nothing = throw AppException.Unauthorized()

fun ApplicationCall.respondNotFound(reason: String? = null): Nothing {
    val details = reason?.let { listOf(it) } ?: emptyList()
    throw AppException.NotFound(details)
}

fun ApplicationCall.respondUnsupportedMediaType(): Nothing = throw AppException.UnsupportedMedia()

fun ApplicationCall.respondPayloadTooLarge(limit: Long): Nothing = throw AppException.PayloadTooLarge(limit)

fun ApplicationCall.respondTooManyRequests(retryAfterSeconds: Long): Nothing =
    throw AppException.RateLimited(
        retryAfterSeconds,
    )

fun ApplicationCall.respondServiceUnavailable(): Nothing = throw AppException.ImportByUrlDisabled()

fun ApplicationCall.respondInternal(): Nothing = throw AppException.Internal()

fun ApplicationCall.handleDomainError(cause: Throwable): Nothing {
    val exception =
        when (cause) {
            is IllegalArgumentException -> AppException.BadRequest(listOfNotNull(cause.message))
            is PortfolioException -> mapPortfolioException(cause)
            else ->
                when {
                    cause.matchesName(ALREADY_EXISTS_NAMES) || cause.isUniqueViolation() ->
                        AppException.Unprocessable()
                    cause.matchesName(NOT_FOUND_NAMES) -> AppException.NotFound()
                    else -> {
                        application.environment.log.error("Unhandled error", cause)
                        AppException.Internal(cause = cause)
                    }
                }
        }
    throw exception
}

private fun ApplicationCall.mapPortfolioException(exception: PortfolioException): AppException =
    when (val error = exception.error) {
        is PortfolioError.Validation -> AppException.Unprocessable(listOf(error.message), exception)
        is PortfolioError.NotFound -> AppException.NotFound(listOf(error.message))
        is PortfolioError.FxRateNotFound ->
            AppException.Custom(
                code = errors.ErrorCode.FX_RATE_UNAVAILABLE,
                details = listOf(error.message),
                cause = exception,
            )
        is PortfolioError.FxRateUnavailable ->
            AppException.Custom(
                code = errors.ErrorCode.FX_RATE_UNAVAILABLE,
                details = listOf(error.message),
                cause = exception,
            )
        is PortfolioError.External -> {
            application.environment.log.error("Portfolio service error", exception)
            AppException.Internal(cause = exception)
        }
    }

private fun Throwable.matchesName(names: Set<String>): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current::class.simpleName in names) {
            return true
        }
        val next = current.cause
        current = if (next != null && next !== current) next else null
    }
    return false
}

private fun Throwable.isUniqueViolation(): Boolean =
    when (this) {
        is ExposedSQLException -> sqlState == UNIQUE_VIOLATION || (cause?.isUniqueViolation() == true)
        is SQLException -> sqlState == UNIQUE_VIOLATION || (cause?.isUniqueViolation() == true)
        else -> cause?.takeIf { it !== this }?.isUniqueViolation() == true
    }

private val ALREADY_EXISTS_NAMES = setOf("AlreadyExists", "AlreadyExistsException")
private val NOT_FOUND_NAMES = setOf("NotFound", "NotFoundException")
private const val UNIQUE_VIOLATION = "23505"
