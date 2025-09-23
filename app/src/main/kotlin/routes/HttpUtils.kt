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

@Serializable
data class ApiErrorResponse(
    val error: String,
    val message: String? = null,
    val details: List<ValidationError> = emptyList(),
)

suspend fun ApplicationCall.respondBadRequest(details: List<ValidationError>) {
    respond(
        HttpStatusCode.BadRequest,
        ApiErrorResponse(
            error = "bad_request",
            message = "Invalid request payload",
            details = details,
        ),
    )
}

suspend fun ApplicationCall.respondUnauthorized() {
    respond(
        HttpStatusCode.Unauthorized,
        ApiErrorResponse(error = "unauthorized", message = "Authentication required"),
    )
}

suspend fun ApplicationCall.respondConflict(reason: String) {
    respond(
        HttpStatusCode.Conflict,
        ApiErrorResponse(error = "conflict", message = reason),
    )
}

suspend fun ApplicationCall.respondInternal() {
    respond(
        HttpStatusCode.InternalServerError,
        ApiErrorResponse(error = "internal_error", message = "Internal server error"),
    )
}

suspend fun ApplicationCall.handleDomainError(cause: Throwable) {
    when (val error = (cause as? PortfolioException)?.error) {
        is PortfolioError.Validation -> {
            respondBadRequest(listOf(ValidationError(field = "general", message = error.message)))
        }
        is PortfolioError.NotFound -> {
            respond(
                HttpStatusCode.NotFound,
                ApiErrorResponse(error = "not_found", message = error.message),
            )
        }
        is PortfolioError.External -> {
            application.environment.log.error("External portfolio error", cause)
            respondInternal()
        }
        null -> {
            if (cause.isUniqueViolation()) {
                respondConflict("Resource already exists")
            } else {
                application.environment.log.error("Unhandled error", cause)
                respondInternal()
            }
        }
    }
}

private fun Throwable.isUniqueViolation(): Boolean {
    return when (this) {
        is ExposedSQLException -> sqlState == UNIQUE_VIOLATION || (cause?.isUniqueViolation() == true)
        is SQLException -> sqlState == UNIQUE_VIOLATION || (cause?.isUniqueViolation() == true)
        else -> cause?.takeIf { it !== this }?.isUniqueViolation() == true
    }
}

private const val UNIQUE_VIOLATION = "23505"
