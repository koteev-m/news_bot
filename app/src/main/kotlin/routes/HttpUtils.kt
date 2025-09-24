package routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmName
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

@Serializable
data class HttpErrorResponse(
    val error: String,
    val reason: String? = null,
    val details: List<String>? = null,
    val limit: Long? = null,
)

@JvmName("respondBadRequestWithStrings")
suspend fun ApplicationCall.respondBadRequest(details: List<String>) {
    respond(HttpStatusCode.BadRequest, HttpErrorResponse(error = "bad_request", details = details))
}

suspend fun ApplicationCall.respondBadRequest(details: List<ValidationError>) {
    val messages = details.map { validation ->
        val field = validation.field?.let { "$it: " } ?: ""
        "$field${validation.message}"
    }
    respondBadRequest(messages)
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

suspend fun ApplicationCall.respondUnsupportedMediaType() {
    respond(HttpStatusCode.UnsupportedMediaType, HttpErrorResponse(error = "unsupported_media_type"))
}

suspend fun ApplicationCall.respondPayloadTooLarge(limit: Long) {
    respond(HttpStatusCode.PayloadTooLarge, HttpErrorResponse(error = "payload_too_large", limit = limit))
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
        cause.matchesName(ALREADY_EXISTS_NAMES) || cause.isUniqueViolation() -> {
            respondConflict(cause.message ?: "conflict")
        }
        cause.matchesName(NOT_FOUND_NAMES) -> {
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

private fun Throwable.isUniqueViolation(): Boolean = when (this) {
    is ExposedSQLException -> sqlState == UNIQUE_VIOLATION || (cause?.isUniqueViolation() == true)
    is SQLException -> sqlState == UNIQUE_VIOLATION || (cause?.isUniqueViolation() == true)
    else -> cause?.takeIf { it !== this }?.isUniqueViolation() == true
}

private val ALREADY_EXISTS_NAMES = setOf("AlreadyExists", "AlreadyExistsException")
private val NOT_FOUND_NAMES = setOf("NotFound", "NotFoundException")
private const val UNIQUE_VIOLATION = "23505"
