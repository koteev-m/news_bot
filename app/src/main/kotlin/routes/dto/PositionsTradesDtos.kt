package routes.dto

import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

@Serializable
data class PositionItemResponse(
    val instrumentId: Long,
    val qty: String,
    val avgPrice: MoneyDto,
    val lastPrice: MoneyDto,
    val upl: MoneyDto,
)

@Serializable
data class TradeItemResponse(
    val instrumentId: Long,
    val tradeDate: String,
    val side: String,
    val quantity: String,
    val price: MoneyDto,
    val notional: MoneyDto,
    val fee: MoneyDto,
    val tax: MoneyDto?,
    val extId: String?,
)

@Serializable
data class TradesPageResponse(
    val total: Long,
    val items: List<TradeItemResponse>,
    val limit: Int,
    val offset: Int,
)

fun ApplicationCall.paramLimit(default: Int = DEFAULT_LIMIT): Int {
    val raw = request.queryParameters["limit"] ?: return default
    val value = raw.trim()
    require(value.isNotEmpty()) { "limit must be between 1 and $MAX_LIMIT" }
    val parsed =
        value.toIntOrNull()
            ?: throw IllegalArgumentException("limit must be between 1 and $MAX_LIMIT")
    require(parsed in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }
    return parsed
}

fun ApplicationCall.paramOffset(default: Int = 0): Int {
    val raw = request.queryParameters["offset"] ?: return default
    val value = raw.trim()
    require(value.isNotEmpty()) { "offset must be a non-negative integer" }
    val parsed =
        value.toIntOrNull()
            ?: throw IllegalArgumentException("offset must be a non-negative integer")
    require(parsed >= 0) { "offset must be a non-negative integer" }
    return parsed
}

fun ApplicationCall.paramDate(name: String): LocalDate? {
    val raw = request.queryParameters[name] ?: return null
    val value = raw.trim()
    require(value.isNotEmpty()) { "$name must be in format YYYY-MM-DD" }
    return try {
        LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
    } catch (ex: DateTimeParseException) {
        throw IllegalArgumentException("$name must be in format YYYY-MM-DD")
    }
}

fun ApplicationCall.paramSide(): String? {
    val raw = request.queryParameters["side"] ?: return null
    val value = raw.trim()
    require(value.isNotEmpty()) { "side must be BUY or SELL" }
    val normalized = value.uppercase()
    require(normalized in setOf("BUY", "SELL")) { "side must be BUY or SELL" }
    return normalized
}

fun ApplicationCall.paramSort(default: String = "instrumentId"): String {
    val raw = request.queryParameters["sort"] ?: return default
    val value = raw.trim()
    if (value.isEmpty()) {
        return default
    }
    val allowed = setOf("instrumentId", "qty", "upl")
    require(value in allowed) { "sort must be one of: ${allowed.joinToString(",")}" }
    return value
}

fun ApplicationCall.paramOrder(default: String = "asc"): String {
    val raw = request.queryParameters["order"] ?: return default
    val value = raw.trim()
    if (value.isEmpty()) {
        return default
    }
    val normalized = value.lowercase()
    require(normalized in setOf("asc", "desc")) { "order must be 'asc' or 'desc'" }
    return normalized
}

private const val DEFAULT_LIMIT = 50
private const val MAX_LIMIT = 200
