package routes

import di.portfolioModule
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import portfolio.errors.PortfolioError
import portfolio.errors.PortfolioException
import portfolio.service.PricingService
import routes.dto.toDto
import routes.quotes.Services

private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
private val UTC_ZONE: ZoneOffset = ZoneOffset.UTC

fun Route.quotesRoutes() {
    get("/api/quotes/closeOrLast") {
        val instrumentId = call.parseInstrumentId() ?: return@get
        val date = call.parseDateOrDefault() ?: return@get
        val pricingService = call.pricingService()

        pricingService.closeOrLast(instrumentId, date).fold(
            onSuccess = { money -> call.respond(money.toDto()) },
            onFailure = { throwable -> call.handleQuoteFailure(throwable) },
        )
    }
}

private suspend fun ApplicationCall.handleQuoteFailure(cause: Throwable) {
    when (cause) {
        is PortfolioException -> when (val error = cause.error) {
            is PortfolioError.NotFound -> respondNotFound("price_not_available")
            is PortfolioError.Validation -> respondBadRequest(listOf(error.message))
            is PortfolioError.FxRateNotFound,
            is PortfolioError.FxRateUnavailable -> {
                application.environment.log.error("Pricing service failure", cause)
                respondInternal()
            }
            is PortfolioError.External -> {
                application.environment.log.error("Pricing service failure", cause)
                respondInternal()
            }
        }
        is IllegalArgumentException -> respondBadRequest(listOf(cause.message ?: "invalid_request"))
        else -> {
            application.environment.log.error("Unexpected pricing failure", cause)
            respondInternal()
        }
    }
}

private suspend fun ApplicationCall.parseInstrumentId(): Long? {
    val raw = request.queryParameters["instrumentId"]?.trim()
    if (raw.isNullOrEmpty()) {
        respondBadRequest(listOf("instrumentId invalid"))
        return null
    }
    val parsed = raw.toLongOrNull()
    if (parsed == null || parsed <= 0) {
        respondBadRequest(listOf("instrumentId invalid"))
        return null
    }
    return parsed
}

private suspend fun ApplicationCall.parseDateOrDefault(): LocalDate? {
    val raw = request.queryParameters["date"] ?: return LocalDate.now(UTC_ZONE)
    val value = raw.trim()
    if (value.isEmpty()) {
        respondBadRequest(listOf("date invalid"))
        return null
    }
    return try {
        LocalDate.parse(value, DATE_FORMATTER)
    } catch (_: DateTimeParseException) {
        respondBadRequest(listOf("date invalid"))
        null
    }
}

private fun ApplicationCall.pricingService(): PricingService {
    val attributes = application.attributes
    if (attributes.contains(Services.Key)) {
        return attributes[Services.Key]
    }

    val service = application.portfolioModule().services.pricingService
    attributes.put(Services.Key, service)
    return service
}
