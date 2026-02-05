package portfolio.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import model.FxRate
import portfolio.errors.DomainResult
import portfolio.errors.PortfolioError
import portfolio.errors.PortfolioException
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

interface FxRateRepository {
    suspend fun findOnOrBefore(
        ccy: String,
        timestamp: Instant,
    ): FxRate?
}

class FxRateService(
    private val repository: FxRateRepository,
    private val zoneId: ZoneId = ZoneOffset.UTC,
) {
    suspend fun rateOn(
        date: LocalDate,
        ccy: String,
        base: String = RUB,
    ): DomainResult<BigDecimal> {
        val normalizedCurrency = normalizeCurrency(ccy)
        val normalizedBase = normalizeCurrency(base)

        val validationError =
            when {
                !isValidCode(normalizedCurrency) -> PortfolioError.Validation("Unknown currency code: $ccy")
                !isValidCode(normalizedBase) -> PortfolioError.Validation("Unknown base currency code: $base")
                else -> null
            }
        if (validationError != null) {
            return failure(validationError)
        }

        if (normalizedCurrency == normalizedBase) {
            return DomainResult.success(BigDecimal.ONE)
        }

        val currencyRate =
            rateToRub(date, normalizedCurrency).fold(
                onSuccess = { it },
                onFailure = { return DomainResult.failure(it) },
            )

        if (normalizedBase == RUB) {
            return DomainResult.success(currencyRate.value)
        }

        val baseRate =
            rateToRub(date, normalizedBase).fold(
                onSuccess = { it },
                onFailure = { return DomainResult.failure(it) },
            )

        if (baseRate.value.compareTo(BigDecimal.ZERO) == 0) {
            return failure(
                PortfolioError.Validation("FX rate for $normalizedBase equals zero and cannot be used for conversion"),
            )
        }

        val cross = currencyRate.value.divide(baseRate.value, MathContext.DECIMAL128)
        return DomainResult.success(normalize(cross))
    }

    private suspend fun rateToRub(
        date: LocalDate,
        currency: String,
    ): DomainResult<CachedRate> {
        if (currency == RUB) {
            return DomainResult.success(CachedRate(date, BigDecimal.ONE))
        }

        val cached = readCache(currency, date)
        if (cached != null) {
            return DomainResult.success(cached)
        }

        val rate =
            repository.findOnOrBefore(currency, endOfDay(date))
                ?: return failure(PortfolioError.NotFound("No FX rate for $currency on or before $date"))

        val asOf = rate.ts.atZone(zoneId).toLocalDate()
        val entry = CachedRate(asOf = asOf, value = normalize(rate.rateRub))

        storeInCache(currency, asOf, date, entry)
        return DomainResult.success(entry)
    }

    private suspend fun readCache(
        currency: String,
        date: LocalDate,
    ): CachedRate? =
        mutex.withLock {
            cache[currency]?.get(date)
        }

    private suspend fun storeInCache(
        currency: String,
        asOf: LocalDate,
        requested: LocalDate,
        entry: CachedRate,
    ) {
        val start = if (asOf <= requested) asOf else requested
        val end = if (asOf >= requested) asOf else requested
        val dates = datesBetween(start, end)

        mutex.withLock {
            val currencyCache = cache.getOrPut(currency) { mutableMapOf() }
            for (current in dates) {
                currencyCache[current] = entry
            }
        }
    }

    private fun normalizeCurrency(value: String): String = value.trim().uppercase(Locale.ROOT)

    private fun isValidCode(code: String): Boolean = CURRENCY_REGEX.matches(code)

    private fun endOfDay(date: LocalDate): Instant = date.atTime(LocalTime.MAX).atZone(zoneId).toInstant()

    private fun datesBetween(
        start: LocalDate,
        endInclusive: LocalDate,
    ): Sequence<LocalDate> {
        if (start > endInclusive) return emptySequence()
        return generateSequence(start) { previous ->
            if (previous >= endInclusive) null else previous.plusDays(1)
        }
    }

    private fun normalize(value: BigDecimal): BigDecimal {
        val stripped = value.stripTrailingZeros()
        return if (stripped.scale() < 0) stripped.setScale(0) else stripped
    }

    private fun <T> failure(error: PortfolioError): DomainResult<T> = DomainResult.failure(PortfolioException(error))

    private data class CachedRate(
        val asOf: LocalDate,
        val value: BigDecimal,
    )

    private companion object {
        private const val RUB = "RUB"
        private val CURRENCY_REGEX = Regex("^[A-Z]{3}$")
    }

    private val mutex = Mutex()
    private val cache = mutableMapOf<String, MutableMap<LocalDate, CachedRate>>()
}
