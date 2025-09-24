package routes.dto

import io.ktor.server.application.ApplicationCall
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable

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

fun ApplicationCall.paramLimit(default: Int = 50): Int {
    val raw = request.queryParameters["limit"] ?: return default
    val value = raw.trim()
    if (value.isEmpty()) {
        throw IllegalArgumentException("limit must be between 1 and 200")
    }
    val parsed = value.toIntOrNull()
        ?: throw IllegalArgumentException("limit must be between 1 and 200")
    if (parsed !in 1..200) {
        throw IllegalArgumentException("limit must be between 1 and 200")
    }
    return parsed
}

fun ApplicationCall.paramOffset(default: Int = 0): Int {
    val raw = request.queryParameters["offset"] ?: return default
    val value = raw.trim()
    if (value.isEmpty()) {
        throw IllegalArgumentException("offset must be a non-negative integer")
    }
    val parsed = value.toIntOrNull()
        ?: throw IllegalArgumentException("offset must be a non-negative integer")
    if (parsed < 0) {
        throw IllegalArgumentException("offset must be a non-negative integer")
    }
    return parsed
}

fun ApplicationCall.paramDate(name: String): LocalDate? {
    val raw = request.queryParameters[name] ?: return null
    val value = raw.trim()
    if (value.isEmpty()) {
        throw IllegalArgumentException("$name must be in format YYYY-MM-DD")
    }
    return try {
        LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
    } catch (ex: DateTimeParseException) {
        throw IllegalArgumentException("$name must be in format YYYY-MM-DD")
    }
}

fun ApplicationCall.paramSide(): String? {
    val raw = request.queryParameters["side"] ?: return null
    val value = raw.trim()
    if (value.isEmpty()) {
        throw IllegalArgumentException("side must be BUY or SELL")
    }
    val normalized = value.uppercase()
    if (normalized !in setOf("BUY", "SELL")) {
        throw IllegalArgumentException("side must be BUY or SELL")
    }
    return normalized
}

fun ApplicationCall.paramSort(default: String = "instrumentId"): String {
    val raw = request.queryParameters["sort"] ?: return default
    val value = raw.trim()
    if (value.isEmpty()) {
        return default
    }
    val allowed = setOf("instrumentId", "qty", "upl")
    if (value !in allowed) {
        throw IllegalArgumentException("sort must be one of: ${allowed.joinToString(",")}")
    }
    return value
}

fun ApplicationCall.paramOrder(default: String = "asc"): String {
    val raw = request.queryParameters["order"] ?: return default
    val value = raw.trim()
    if (value.isEmpty()) {
        return default
    }
    val normalized = value.lowercase()
    if (normalized !in setOf("asc", "desc")) {
        throw IllegalArgumentException("order must be 'asc' or 'desc'")
    }
    return normalized
}
