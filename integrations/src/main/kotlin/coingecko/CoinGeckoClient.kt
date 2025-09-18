package coingecko

import cache.TtlCache
import http.HttpClientError
import http.HttpMetricsRecorder
import http.HttpResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.micrometer.core.instrument.MeterRegistry
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.Duration as JavaDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class CoinGeckoClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    meterRegistry: MeterRegistry,
    private val clock: Clock = Clock.systemUTC(),
    private val minRequestInterval: Duration = 1.seconds
) {
    private val metrics = HttpMetricsRecorder(meterRegistry, "coingecko", javaClass.name)
    private val priceCache = TtlCache<String, Map<String, Map<String, BigDecimal>>>(clock)
    private val chartCache = TtlCache<String, MarketChart>(clock)
    private val rateMutex = Mutex()
    private var lastRequestAt: Instant? = null

    suspend fun getSimplePrice(ids: List<String>, vs: List<String>): HttpResult<Map<String, Map<String, BigDecimal>>> {
        if (ids.isEmpty()) {
            return Result.failure(HttpClientError.ValidationError("ids must not be empty"))
        }
        if (ids.size > MAX_SIMPLE_PRICE_IDS) {
            return Result.failure(HttpClientError.ValidationError("ids limit is $MAX_SIMPLE_PRICE_IDS"))
        }
        if (vs.isEmpty()) {
            return Result.failure(HttpClientError.ValidationError("vs currencies must not be empty"))
        }
        if (vs.size > MAX_SIMPLE_PRICE_CURRENCIES) {
            return Result.failure(HttpClientError.ValidationError("vs currencies limit is $MAX_SIMPLE_PRICE_CURRENCIES"))
        }
        val normalizedIds = ids.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (normalizedIds.isEmpty()) {
            return Result.failure(HttpClientError.ValidationError("ids must contain non-blank values"))
        }
        val normalizedVs = vs.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (normalizedVs.isEmpty()) {
            return Result.failure(HttpClientError.ValidationError("vs currencies must contain non-blank values"))
        }
        val idsParam = normalizedIds.joinToString(",")
        val vsParam = normalizedVs.joinToString(",")
        val cacheKey = "$idsParam|$vsParam"
        return runCatching {
            priceCache.getOrPut(cacheKey, SIMPLE_PRICE_TTL) {
                metrics.record("simplePrice") {
                    rateLimited {
                        requestSimplePrice(idsParam, vsParam)
                    }
                }
            }
        }
    }

    suspend fun getMarketChart(id: String, vs: String, days: Int): HttpResult<MarketChart> {
        if (id.isBlank()) {
            return Result.failure(HttpClientError.ValidationError("id must not be blank"))
        }
        if (vs.isBlank()) {
            return Result.failure(HttpClientError.ValidationError("vs currency must not be blank"))
        }
        if (days <= 0 || days > MAX_MARKET_CHART_DAYS) {
            return Result.failure(HttpClientError.ValidationError("days must be in 1..$MAX_MARKET_CHART_DAYS"))
        }
        val normalizedId = id.trim().lowercase()
        val normalizedVs = vs.trim().lowercase()
        val cacheKey = listOf(normalizedId, normalizedVs, days.toString()).joinToString(":")
        return runCatching {
            chartCache.getOrPut(cacheKey, MARKET_CHART_TTL) {
                metrics.record("marketChart") {
                    rateLimited {
                        requestMarketChart(normalizedId, normalizedVs, days)
                    }
                }
            }
        }
    }

    private suspend fun requestSimplePrice(ids: String, vs: String): Map<String, Map<String, BigDecimal>> {
        val payloadElement: JsonElement = httpClient.get("$baseUrl/api/v3/simple/price") {
            parameter("ids", ids)
            parameter("vs_currencies", vs)
        }.body()
        val payload = payloadElement.asJsonObjectOrNull()
            ?: throw HttpClientError.DeserializationError("Expected JSON object for simple price response")
        return payload.entries.associate { (asset, value) ->
            val priceObject = value as? JsonObject
                ?: throw HttpClientError.DeserializationError("Expected JSON object for $asset simple price response")
            val prices = priceObject.entries.associate { (currency, priceElement) ->
                currency to priceElement.toBigDecimalOrThrow("$asset:$currency")
            }
            asset to prices
        }
    }

    private suspend fun requestMarketChart(id: String, vs: String, days: Int): MarketChart {
        val raw: CoinGeckoMarketChartRaw = httpClient.get("$baseUrl/api/v3/coins/$id/market_chart") {
            parameter("vs_currency", vs)
            parameter("days", days)
        }.body()
        return raw.toDomain()
    }

    private suspend fun <T> rateLimited(block: suspend () -> T): T = rateMutex.withLock {
        val now = clock.instant()
        val last = lastRequestAt
        val minMillis = minRequestInterval.inWholeMilliseconds
        if (last != null && minMillis > 0) {
            val elapsed = JavaDuration.between(last, now).toMillis()
            val waitMillis = minMillis - elapsed
            if (waitMillis > 0) {
                delay(waitMillis)
            }
        }
        val result = block()
        lastRequestAt = clock.instant()
        result
    }

    companion object {
        private const val MAX_SIMPLE_PRICE_IDS = 50
        private const val MAX_SIMPLE_PRICE_CURRENCIES = 10
        private const val MAX_MARKET_CHART_DAYS = 365
        private val SIMPLE_PRICE_TTL: Duration = 15.seconds
        private val MARKET_CHART_TTL: Duration = 30.seconds
    }
}

@Serializable
private data class CoinGeckoMarketChartRaw(
    val prices: List<List<JsonElement>> = emptyList(),
    @SerialName("market_caps") val marketCaps: List<List<JsonElement>> = emptyList(),
    @SerialName("total_volumes") val totalVolumes: List<List<JsonElement>> = emptyList()
) {
    fun toDomain(): MarketChart = MarketChart(
        prices = prices.map { it.toChartPoint("prices") },
        marketCaps = marketCaps.map { it.toChartPoint("market_caps") },
        totalVolumes = totalVolumes.map { it.toChartPoint("total_volumes") }
    )

    private fun List<JsonElement>.toChartPoint(section: String): ChartPoint {
        if (size < 2) {
            throw HttpClientError.DeserializationError("Invalid entry in $section section")
        }
        val timestampValue = this[0].jsonPrimitive.contentOrNull?.trim()
            ?: throw HttpClientError.DeserializationError("Missing timestamp in $section section")
        val timestamp = timestampValue.toLongOrNull()
            ?: throw HttpClientError.DeserializationError("Invalid timestamp '$timestampValue' in $section section")
        val price = this[1].toBigDecimalOrThrow(section)
        return ChartPoint(Instant.ofEpochMilli(timestamp), price)
    }
}

data class MarketChart(
    val prices: List<ChartPoint>,
    val marketCaps: List<ChartPoint>,
    val totalVolumes: List<ChartPoint>
)

data class ChartPoint(val timestamp: Instant, val value: BigDecimal)

private fun JsonElement.toBigDecimalOrThrow(label: String): BigDecimal {
    val primitive = jsonPrimitive
    val raw = primitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw HttpClientError.DeserializationError("Missing numeric value for $label")
    val normalized = raw.replace(',', '.')
    return normalized.toBigDecimalOrNull()
        ?: throw HttpClientError.DeserializationError("Invalid numeric value '$raw' for $label")
}

private fun JsonElement.asJsonObjectOrNull(): JsonObject? = this as? JsonObject
