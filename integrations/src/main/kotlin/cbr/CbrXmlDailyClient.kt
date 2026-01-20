package cbr

import cache.TtlCache
import http.HttpClientError
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class CbrXmlDailyClient(
    private val client: CbrClient,
    cacheSize: Int = DEFAULT_CACHE_SIZE,
    private val latestTtl: Duration = DEFAULT_LATEST_TTL,
    private val zoneId: ZoneId = DEFAULT_ZONE,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val historicalCache = LruCache<LocalDate, CbrRatesResult>(cacheSize)
    private val latestCache = TtlCache<String, CbrRatesResult>(clock)

    suspend fun fetchDailyRates(date: LocalDate? = null): CbrRatesResult {
        if (date == null) {
            return latestCache.getOrPut(LATEST_KEY, latestTtl) { fetchAndMap(null) }
        }
        historicalCache.get(date)?.let { return it }
        val result = fetchAndMap(date)
        historicalCache.put(date, result)
        return result
    }

    private suspend fun fetchAndMap(date: LocalDate?): CbrRatesResult {
        val rates = client.getXmlDaily(date).getOrElse { throw it }
        if (rates.isEmpty()) {
            throw HttpClientError.DeserializationError("Empty CBR XML_daily response")
        }
        val effectiveDate = rates.first().asOf.atZone(zoneId).toLocalDate()
        val delayed = date == null || effectiveDate != date
        val mapped = rates.associate { rate -> rate.currencyCode.uppercase() to rate.rateRub } + (RUB to BigDecimal.ONE)
        return CbrRatesResult(
            effectiveDate = effectiveDate,
            ratesToRub = mapped,
            delayed = delayed,
        )
    }

    private class LruCache<K : Any, V : Any>(
        private val maxSize: Int,
    ) {
        private val store = object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > maxSize
        }

        @Synchronized
        fun get(key: K): V? = store[key]

        @Synchronized
        fun put(key: K, value: V) {
            store[key] = value
        }
    }

    companion object {
        private const val RUB = "RUB"
        private const val LATEST_KEY = "latest"
        private const val DEFAULT_CACHE_SIZE = 600
        private val DEFAULT_LATEST_TTL: Duration = 15.minutes
        private val DEFAULT_ZONE: ZoneId = ZoneId.of("Europe/Moscow")
    }
}

data class CbrRatesResult(
    val effectiveDate: LocalDate,
    val ratesToRub: Map<String, BigDecimal>,
    val delayed: Boolean,
)
