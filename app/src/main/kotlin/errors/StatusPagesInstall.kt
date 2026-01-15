package errors

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.header
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

fun Application.installErrorPages() {
    install(StatusPages) {
        exception<AppException> { call, cause ->
            call.respondError(
                code = cause.errorCode,
                status = ErrorCatalog.status(cause.errorCode),
                details = cause.details,
                headers = cause.headers,
                overrideMessage = cause.overrideMessage
            )
        }
        exception<SerializationException> { call, cause ->
            call.respondError(
                code = ErrorCode.BAD_REQUEST,
                status = HttpStatusCode.BadRequest,
                details = listOfNotNull(cause.message)
            )
        }
        exception<ContentTransformationException> { call, cause ->
            call.respondError(
                code = ErrorCode.BAD_REQUEST,
                status = HttpStatusCode.BadRequest,
                details = listOfNotNull(cause.message)
            )
        }
        this.status(HttpStatusCode.NotFound) { call, _ ->
            call.respondError(ErrorCode.NOT_FOUND, HttpStatusCode.NotFound)
        }
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            if (cause is Error) throw cause
            call.respondError(ErrorCode.INTERNAL, HttpStatusCode.InternalServerError)
        }
    }
}

private suspend fun ApplicationCall.respondError(
    code: ErrorCode,
    status: HttpStatusCode = ErrorCatalog.status(code),
    details: List<String> = emptyList(),
    headers: Map<String, String> = emptyMap(),
    overrideMessage: String? = null
) {
    val message = overrideMessage ?: ErrorCatalog.message(code)
    val traceId = callId
        ?: request.header(HttpHeaders.XRequestId)
        ?: request.header("Trace-Id")
        ?: "-"
    headers.forEach { (key, value) -> response.headers.append(key, value, safeOnly = false) }
    respond(
        status,
        ErrorResponse(
            code = code.name,
            message = message,
            details = details,
            traceId = traceId
        )
    )
}
