package moex

import common.runCatchingNonFatal
import http.CircuitBreaker
import http.HttpClientError
import http.HttpClients
import http.HttpResult
import http.IntegrationsMetrics
import http.SimpleCache
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.jvm.Volatile

class MoexIssClient(
    private val client: HttpClient,
    private val cb: CircuitBreaker,
    private val metrics: IntegrationsMetrics,
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
    private val statusCacheTtlMs: Long = DEFAULT_STATUS_CACHE_TTL_MS,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Volatile
    private var baseUrl: String = DEFAULT_BASE_URL
    private val securitiesCache = SimpleCache<String, MoexSecuritiesResponse>(cacheTtlMs, clock)
    private val candlesCache = SimpleCache<String, MoexCandlesResponse>(cacheTtlMs, clock)
    private val statusCache = SimpleCache<String, MoexStatusResponse>(statusCacheTtlMs, clock)

    init {
        metrics.requestTimer(SERVICE, "success")
        metrics.requestTimer(SERVICE, "error")
    }

    fun setBaseUrl(url: String) {
        baseUrl = normalizeBaseUrl(url)
    }

    suspend fun getSecuritiesTqbr(securities: List<String>): HttpResult<MoexSecuritiesResponse> {
        if (securities.isEmpty()) {
            return Result.failure(HttpClientError.ValidationError("securities list must not be empty"))
        }
        val normalized = securities.map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        if (normalized.isEmpty()) {
            return Result.failure(HttpClientError.ValidationError("securities list must contain non-blank values"))
        }
        val cacheKey = normalized.sorted().joinToString(",")
        return runCatchingNonFatal {
            securitiesCache.getOrPut(cacheKey) {
                requestSecurities(cacheKey)
            }
        }
    }

    suspend fun getCandlesDaily(
        ticker: String,
        from: LocalDate,
        till: LocalDate,
    ): HttpResult<MoexCandlesResponse> {
        if (ticker.isBlank()) {
            return Result.failure(HttpClientError.ValidationError("ticker must not be blank"))
        }
        if (from.isAfter(till)) {
            return Result.failure(HttpClientError.ValidationError("from date must not be after till date"))
        }
        val normalizedTicker = ticker.trim().uppercase()
        val cacheKey = listOf(normalizedTicker, from.toString(), till.toString()).joinToString(":")
        return runCatchingNonFatal {
            candlesCache.getOrPut(cacheKey) {
                requestCandles(normalizedTicker, from, till)
            }
        }
    }

    suspend fun getMarketStatus(): HttpResult<MoexStatusResponse> =
        runCatchingNonFatal {
            statusCache.getOrPut("status") {
                requestMarketStatus()
            }
        }

    private suspend fun requestSecurities(key: String): MoexSecuritiesResponse =
        cb.withPermit {
            HttpClients.measure(SERVICE) {
                client
                    .get("${baseUrl}$SECURITIES_PATH") {
                        parameter("securities", key)
                        parameter("iss.meta", "off")
                    }.body<MoexSecuritiesRaw>()
                    .toDomain()
            }
        }

    private suspend fun requestCandles(
        ticker: String,
        from: LocalDate,
        till: LocalDate,
    ): MoexCandlesResponse =
        cb.withPermit {
            HttpClients.measure(SERVICE) {
                client
                    .get("${baseUrl}$CANDLES_PREFIX$ticker$CANDLES_SUFFIX") {
                        parameter("interval", "24")
                        parameter("from", from.toString())
                        parameter("till", till.toString())
                        parameter("iss.meta", "off")
                    }.body<MoexCandlesRaw>()
                    .toDomain()
            }
        }

    private suspend fun requestMarketStatus(): MoexStatusResponse =
        cb.withPermit {
            HttpClients.measure(SERVICE) {
                client
                    .get("${baseUrl}$MARKET_STATUS_PATH") {
                        parameter("iss.meta", "off")
                    }.body<MoexStatusRaw>()
                    .toDomain()
            }
        }

    private fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return DEFAULT_BASE_URL
        }
        return trimmed.trimEnd('/')
    }

    companion object {
        private const val SERVICE: String = "moex"
        private const val DEFAULT_BASE_URL: String = "https://iss.moex.com"
        private const val SECURITIES_PATH: String = "/iss/engines/stock/markets/shares/boards/TQBR/securities.json"
        private const val CANDLES_PREFIX: String = "/iss/engines/stock/markets/shares/securities/"
        private const val CANDLES_SUFFIX: String = "/candles.json"
        private const val MARKET_STATUS_PATH: String = "/iss/engines/stock/markets/shares/marketstatus.json"
        private const val DEFAULT_CACHE_TTL_MS: Long = 30_000
        private const val DEFAULT_STATUS_CACHE_TTL_MS: Long = 10_000
        private val MOSCOW_ZONE: ZoneId = ZoneId.of("Europe/Moscow")
        private val MOEX_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val MOEX_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }

    private fun parseInstant(value: String): Instant {
        val trimmed = value.trim()
        try {
            return Instant.parse(trimmed)
        } catch (_: DateTimeParseException) {
            // ignored
        }
        try {
            val local = LocalDateTime.parse(trimmed, MOEX_DATE_TIME)
            return local.atZone(MOSCOW_ZONE).toInstant()
        } catch (_: DateTimeParseException) {
            // ignored
        }
        try {
            val date = LocalDate.parse(trimmed, MOEX_DATE)
            return date.atStartOfDay(MOSCOW_ZONE).toInstant()
        } catch (ex: DateTimeParseException) {
            throw HttpClientError.DeserializationError("Unable to parse timestamp '$value'", ex)
        }
    }

    private fun MoexTableDto.asRecords(): List<Map<String, JsonElement?>> =
        data.map { row ->
            columns
                .mapIndexed { index, column ->
                    column.uppercase() to row.getOrNull(index)
                }.toMap()
        }

    private fun Map<String, JsonElement?>.string(vararg keys: String): String? =
        keys
            .asSequence()
            .mapNotNull { key ->
                get(key.uppercase())?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            }.firstOrNull()

    private fun Map<String, JsonElement?>.decimal(vararg keys: String): BigDecimal? =
        string(*keys)?.let { raw ->
            raw.replace(',', '.').toBigDecimalOrNull()
        }

    private fun Map<String, JsonElement?>.int(vararg keys: String): Int? = string(*keys)?.toIntOrNull()

    private fun Map<String, JsonElement?>.instantOrNull(vararg keys: String): Instant? =
        string(*keys)?.let {
            parseInstant(it)
        }

    private fun Map<String, JsonElement?>.booleanOrNull(vararg keys: String): Boolean? =
        keys
            .asSequence()
            .mapNotNull { key -> get(key.uppercase())?.jsonPrimitive?.booleanOrNull }
            .firstOrNull()

    private fun MoexSecuritiesRaw.toDomain(): MoexSecuritiesResponse {
        val records = securities.asRecords()
        if (securities.columns.none { it.equals("SECID", ignoreCase = true) }) {
            throw HttpClientError.DeserializationError("SECID column is missing in MOEX securities payload")
        }
        val items =
            records.mapNotNull { record ->
                val code = record.string("SECID") ?: return@mapNotNull null
                MoexSecurity(
                    code = code,
                    shortName = record.string("SHORTNAME"),
                    boardId = record.string("BOARDID"),
                    lotSize = record.int("LOTSIZE"),
                    currency = record.string("FACEUNIT"),
                    prevClosePrice = record.decimal("PREVPRICE"),
                    marketPrice = record.decimal("LAST"),
                    active = record.booleanOrNull("ISSUESIZEPLACED"),
                    type = record.string("SECTYPE"),
                )
            }
        return MoexSecuritiesResponse(items)
    }

    private fun MoexCandlesRaw.toDomain(): MoexCandlesResponse {
        val records = candles.asRecords()
        val candlesList =
            records.map { record ->
                MoexCandle(
                    open =
                    record.decimal("OPEN") ?: throw HttpClientError.DeserializationError("Missing OPEN in candle"),
                    close =
                    record.decimal(
                        "CLOSE",
                    ) ?: throw HttpClientError.DeserializationError("Missing CLOSE in candle"),
                    high =
                    record.decimal("HIGH") ?: throw HttpClientError.DeserializationError("Missing HIGH in candle"),
                    low = record.decimal("LOW") ?: throw HttpClientError.DeserializationError("Missing LOW in candle"),
                    value = record.decimal("VALUE"),
                    volume = record.decimal("VOLUME"),
                    begin =
                    record.instantOrNull("BEGIN")
                        ?: throw HttpClientError.DeserializationError("Missing BEGIN in candle"),
                    end =
                    record.instantOrNull("END")
                        ?: throw HttpClientError.DeserializationError("Missing END in candle"),
                )
            }
        return MoexCandlesResponse(candlesList)
    }

    private fun MoexStatusRaw.toDomain(): MoexStatusResponse {
        val table = marketStatus ?: marketData ?: MoexTableDto()
        val records = table.asRecords()
        val statuses =
            records.map { record ->
                MoexMarketStatus(
                    boardId = record.string("BOARDID", "BOARD"),
                    market = record.string("MARKET"),
                    state = record.string("STATE", "MARKETSTATE", "TRADE_STATUS"),
                    title = record.string("TITLE", "NAME"),
                    startTime = record.instantOrNull("BEGIN", "STARTTIME", "FROM"),
                    endTime = record.instantOrNull("END", "TILL", "ENDTIME"),
                    isTrading =
                    record.string("TRADINGSESSION", "IS_TRADING")?.let {
                        it == "1" ||
                            it.equals("true", true)
                    },
                )
            }
        return MoexStatusResponse(statuses)
    }
}

@Serializable
private data class MoexTableDto(
    val columns: List<String> = emptyList(),
    val data: List<List<JsonElement>> = emptyList(),
)

@Serializable
private data class MoexSecuritiesRaw(
    val securities: MoexTableDto = MoexTableDto(),
)

@Serializable
private data class MoexCandlesRaw(
    val candles: MoexTableDto = MoexTableDto(),
)

@Serializable
private data class MoexStatusRaw(
    @SerialName("marketstatus") val marketStatus: MoexTableDto? = null,
    @SerialName("marketdata") val marketData: MoexTableDto? = null,
)

data class MoexSecuritiesResponse(
    val securities: List<MoexSecurity>,
)

data class MoexSecurity(
    val code: String,
    val shortName: String?,
    val boardId: String?,
    val lotSize: Int?,
    val currency: String?,
    val prevClosePrice: BigDecimal?,
    val marketPrice: BigDecimal?,
    val active: Boolean?,
    val type: String?,
)

data class MoexCandlesResponse(
    val candles: List<MoexCandle>,
)

data class MoexCandle(
    val open: BigDecimal,
    val close: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val value: BigDecimal?,
    val volume: BigDecimal?,
    val begin: Instant,
    val end: Instant,
)

data class MoexStatusResponse(
    val statuses: List<MoexMarketStatus>,
)

data class MoexMarketStatus(
    val boardId: String?,
    val market: String?,
    val state: String?,
    val title: String?,
    val startTime: Instant?,
    val endTime: Instant?,
    val isTrading: Boolean?,
)
