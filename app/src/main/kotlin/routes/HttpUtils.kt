package routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.exceptions.ExposedSQLException
import portfolio.errors.PortfolioError
import portfolio.errors.PortfolioException
import routes.dto.ValidationError
import java.sql.SQLException
import kotlin.jvm.JvmName

@Serializable
data class ApiErrorResponse(
    val error: String,
    val message: String? = null,
    val details: List<ValidationError> = emptyList(),
)

@Serializable
data class HttpErrorResponse(
    val error: String,
    val reason: String? = null,
    val details: List<String>? = null,
)

@JvmName("respondBadRequestWithStrings")
suspend fun ApplicationCall.respondBadRequest(details: List<String>) {
    respond(HttpStatusCode.BadRequest, HttpErrorResponse(error = "bad_request", details = details))
}

@JvmName("respondBadRequestWithValidation")
suspend fun ApplicationCall.respondBadRequest(details: List<ValidationError>) {
    respondBadRequest(details.map { "${it.field}: ${it.message}" })
}

suspend fun ApplicationCall.respondUnauthorized() {
    respond(HttpStatusCode.Unauthorized, HttpErrorResponse(error = "unauthorized"))
}

suspend fun ApplicationCall.respondConflict(reason: String) {
    respond(HttpStatusCode.Conflict, HttpErrorResponse(error = "conflict", reason = reason))
}

suspend fun ApplicationCall.respondNotFound(reason: String?) {
    respond(HttpStatusCode.NotFound, HttpErrorResponse(error = "not_found", reason = reason))
}

suspend fun ApplicationCall.respondInternal() {
    respond(HttpStatusCode.InternalServerError, HttpErrorResponse(error = "internal"))
}

suspend fun ApplicationCall.handleDomainError(cause: Throwable) {
    when (cause) {
        is IllegalArgumentException -> {
            respondBadRequest(listOf(cause.message ?: "invalid_request"))
            return
        }
        is PortfolioException -> {
            handlePortfolioException(cause)
            return
        }
    }

    when {
        cause.isConflictException() || cause.isUniqueViolation() -> {
            respondConflict(cause.message ?: "conflict")
        }
        cause.isNotFoundException() -> {
            respondNotFound(cause.message)
        }
        else -> {
            application.environment.log.error("Unhandled error", cause)
            respondInternal()
        }
    }
}

private suspend fun ApplicationCall.handlePortfolioException(exception: PortfolioException) {
    when (val error = exception.error) {
        is PortfolioError.Validation -> respondBadRequest(listOf(error.message))
        is PortfolioError.NotFound -> respondNotFound(error.message)
        is PortfolioError.External -> {
            application.environment.log.error("Portfolio service error", exception)
            respondInternal()
        }
    }
}

private fun Throwable.isConflictException(): Boolean =
    matchesName(setOf("AlreadyExists", "AlreadyExistsException"))

private fun Throwable.isNotFoundException(): Boolean =
    matchesName(setOf("NotFound", "NotFoundException"))

private tailrec fun Throwable.matchesName(names: Set<String>): Boolean {
    val simple = this::class.simpleName
    if (simple != null && simple in names) {
        return true
    }
    val nested = cause
    return nested != null && nested !== this && nested.matchesName(names)
}

private fun Throwable.isUniqueViolation(): Boolean = when (this) {
    is ExposedSQLException -> sqlState == UNIQUE_VIOLATION || (cause?.isUniqueViolation() == true)
    is SQLException -> sqlState == UNIQUE_VIOLATION || (cause?.isUniqueViolation() == true)
    else -> cause?.takeIf { it !== this }?.isUniqueViolation() == true
}

private const val UNIQUE_VIOLATION = "23505"
