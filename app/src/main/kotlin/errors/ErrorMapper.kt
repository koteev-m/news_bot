package errors

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.ParameterConversionException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.request.header
import io.ktor.server.plugins.callid.callId
import org.slf4j.Logger

class ErrorMapper(private val logger: Logger) {
    data class Mapped(val status: HttpStatusCode, val response: ErrorResponse, val headers: Map<String, String>)

    fun map(call: ApplicationCall, cause: Throwable): Mapped {
        return when (cause) {
            is AppException -> mapAppException(call, cause)
            is BadRequestException, is ContentTransformationException, is ParameterConversionException ->
                mapPredefined(call, ErrorCode.BAD_REQUEST)
            is NotFoundException -> mapPredefined(call, ErrorCode.NOT_FOUND)
            is UnsupportedMediaTypeException -> mapPredefined(call, ErrorCode.UNSUPPORTED_MEDIA)
            else -> mapUnexpected(call, cause)
        }
    }

    private fun mapAppException(call: ApplicationCall, exception: AppException): Mapped {
        if (exception.errorCode == ErrorCode.INTERNAL && exception.cause != null) {
            logger.error("internal_error", exception)
        }
        val status = ErrorCatalog.status(exception.errorCode)
        val message = exception.overrideMessage ?: ErrorCatalog.message(exception.errorCode)
        return Mapped(status, buildResponse(call, exception.errorCode, message, exception.details), exception.headers)
    }

    private fun mapPredefined(call: ApplicationCall, code: ErrorCode): Mapped {
        val status = ErrorCatalog.status(code)
        val message = ErrorCatalog.message(code)
        return Mapped(status, buildResponse(call, code, message, emptyList()), emptyMap())
    }

    private fun mapUnexpected(call: ApplicationCall, cause: Throwable): Mapped {
        logger.error("unhandled_error", cause)
        val status = ErrorCatalog.status(ErrorCode.INTERNAL)
        val message = ErrorCatalog.message(ErrorCode.INTERNAL)
        return Mapped(status, buildResponse(call, ErrorCode.INTERNAL, message, emptyList()), emptyMap())
    }

    private fun buildResponse(
        call: ApplicationCall,
        code: ErrorCode,
        message: String,
        details: List<String>
    ): ErrorResponse {
        val traceId = call.callId ?: call.request.header(HttpHeaders.XRequestId) ?: call.request.header("Trace-Id") ?: "-"
        return ErrorResponse(
            code = code.name,
            message = message,
            details = details,
            traceId = traceId
        )
    }
}
