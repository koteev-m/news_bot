package billing.stars

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

class StarsService(
    private val client: StarsClient,
    private val ttlSeconds: Long,
    private val maxStaleSeconds: Long? = null,
    meterRegistry: MeterRegistry? = null,
) : BotStarBalancePort {

    private val logger = LoggerFactory.getLogger(StarsService::class.java)
    @Volatile
    private var cache: CachedBalance? = null
    private val fetchMutex = Mutex()
    private val registry = meterRegistry
    @Volatile
    private var rateLimitedUntilEpochSeconds: Long = 0
    private val botTimer: Timer? = registry?.let { Timer.builder(StarsMetrics.TIMER_BOT).register(it) }
    private val legacyTimer: Timer? = registry?.let { Timer.builder(StarsMetrics.TIMER_LEGACY).register(it) }
    @Suppress("unused")
    private val rateLimitWindowGauge = registry?.gauge(
        StarsMetrics.GAUGE_RATE_LIMIT_REMAINING,
        this,
    ) { svc ->
        val remaining = svc.rateLimitedUntilEpochSeconds - svc.nowEpochSeconds()
        remaining.coerceAtLeast(0).toDouble()
    }
    @Suppress("unused")
    private val cacheAgeGauge = registry?.gauge(
        StarsMetrics.GAUGE_CACHE_AGE,
        this,
    ) { svc ->
        val cached = svc.cache ?: return@gauge 0.0
        cached.ageSeconds().toDouble()
    }
    @Suppress("unused")
    private val cacheTtlGauge = registry?.gauge(
        StarsMetrics.GAUGE_CACHE_TTL,
        this,
    ) { _ -> ttlSeconds.toDouble() }

    override suspend fun getBotStarBalance(): BotStarBalanceResult {
        cache?.takeIf { !it.isExpired(ttlSeconds) }?.let {
            recordCache(CacheState.HIT)
            return BotStarBalanceResult(it.balance, CacheState.HIT, cacheAgeSeconds = it.ageSeconds())
        }

        return fetchMutex.withLock {
            cache?.takeIf { !it.isExpired(ttlSeconds) }?.let {
                recordCache(CacheState.HIT)
                return@withLock BotStarBalanceResult(it.balance, CacheState.HIT, cacheAgeSeconds = it.ageSeconds())
            }
            fetchWithMetricsAndFallback()
        }
    }

    private suspend fun fetchWithMetricsAndFallback(): BotStarBalanceResult {
        val sample = registry?.let { Timer.start(it) }
        val cached = cache
        val nowSeconds = nowEpochSeconds()
        if (nowSeconds < rateLimitedUntilEpochSeconds) {
            cached?.let {
                val ageSeconds = it.ageSeconds()
                if (maxStaleSeconds != null && ageSeconds > maxStaleSeconds) {
                    recordOutcome(StarsOutcomes.RATE_LIMITED)
                    recordBoundedStale(StarsOutcomes.RATE_LIMITED)
                    logStaleCutoff(StarsOutcomes.RATE_LIMITED, it, maxStaleSeconds)
                    val remainingSeconds = (rateLimitedUntilEpochSeconds - nowSeconds).coerceAtLeast(1)
                    throw StarsClientRateLimited(remainingSeconds)
                }
                recordOutcome(StarsOutcomes.STALE_RETURNED)
                recordCache(CacheState.STALE)
                logStale(StarsOutcomes.RATE_LIMITED, it)
                return BotStarBalanceResult(it.balance.copy(stale = true), CacheState.STALE, cacheAgeSeconds = ageSeconds)
            }
            recordOutcome(StarsOutcomes.RATE_LIMITED)
            val remainingSeconds = (rateLimitedUntilEpochSeconds - nowSeconds).coerceAtLeast(1)
            throw StarsClientRateLimited(remainingSeconds)
        }
        try {
            val fresh = client.getBotStarBalance()
            recordOutcome(StarsOutcomes.SUCCESS)
            cache = CachedBalance(fresh, System.currentTimeMillis())
            if (rateLimitedUntilEpochSeconds > 0) {
                logger.debug("stars: cleared RL window")
            }
            rateLimitedUntilEpochSeconds = 0
            recordCache(CacheState.MISS)
            return BotStarBalanceResult(fresh, CacheState.MISS, cacheAgeSeconds = 0)
        } catch (e: StarsClientRateLimited) {
            if (e.retryAfterSeconds != null) {
                val until = nowEpochSeconds() + e.retryAfterSeconds
                rateLimitedUntilEpochSeconds = maxOf(rateLimitedUntilEpochSeconds, until)
                logger.debug(
                    "stars: set RL window to {}s (until={})",
                    e.retryAfterSeconds,
                    rateLimitedUntilEpochSeconds,
                )
            }
            cached?.let {
                val ageSeconds = it.ageSeconds()
                if (maxStaleSeconds != null && ageSeconds > maxStaleSeconds) {
                    recordOutcome(StarsOutcomes.RATE_LIMITED)
                    recordBoundedStale(StarsOutcomes.RATE_LIMITED)
                    logStaleCutoff(StarsOutcomes.RATE_LIMITED, it, maxStaleSeconds)
                    throw e
                }
                recordOutcome(StarsOutcomes.STALE_RETURNED)
                recordCache(CacheState.STALE)
                logStale(StarsOutcomes.RATE_LIMITED, it)
                return BotStarBalanceResult(it.balance.copy(stale = true), CacheState.STALE, cacheAgeSeconds = ageSeconds)
            }
            recordOutcome(StarsOutcomes.RATE_LIMITED)
            throw e
        } catch (e: StarsClientServerError) {
            cached?.let {
                val ageSeconds = it.ageSeconds()
                if (maxStaleSeconds != null && ageSeconds > maxStaleSeconds) {
                    recordOutcome(StarsOutcomes.SERVER)
                    recordBoundedStale(StarsOutcomes.SERVER)
                    logStaleCutoff(StarsOutcomes.SERVER, it, maxStaleSeconds)
                    throw e
                }
                recordOutcome(StarsOutcomes.STALE_RETURNED)
                recordCache(CacheState.STALE)
                logStale(StarsOutcomes.SERVER, it)
                return BotStarBalanceResult(it.balance.copy(stale = true), CacheState.STALE, cacheAgeSeconds = ageSeconds)
            }
            recordOutcome(StarsOutcomes.SERVER)
            throw e
        } catch (e: StarsClientBadRequest) {
            cached?.let {
                val ageSeconds = it.ageSeconds()
                if (maxStaleSeconds != null && ageSeconds > maxStaleSeconds) {
                    recordOutcome(StarsOutcomes.BAD_REQUEST)
                    recordBoundedStale(StarsOutcomes.BAD_REQUEST)
                    logStaleCutoff(StarsOutcomes.BAD_REQUEST, it, maxStaleSeconds)
                    throw e
                }
                recordOutcome(StarsOutcomes.STALE_RETURNED)
                recordCache(CacheState.STALE)
                logStale(StarsOutcomes.BAD_REQUEST, it)
                return BotStarBalanceResult(it.balance.copy(stale = true), CacheState.STALE, cacheAgeSeconds = ageSeconds)
            }
            recordOutcome(StarsOutcomes.BAD_REQUEST)
            throw e
        } catch (e: StarsClientDecodeError) {
            cached?.let {
                val ageSeconds = it.ageSeconds()
                if (maxStaleSeconds != null && ageSeconds > maxStaleSeconds) {
                    recordOutcome(StarsOutcomes.DECODE_ERROR)
                    recordBoundedStale(StarsOutcomes.DECODE_ERROR)
                    logStaleCutoff(StarsOutcomes.DECODE_ERROR, it, maxStaleSeconds)
                    throw e
                }
                recordOutcome(StarsOutcomes.STALE_RETURNED)
                recordCache(CacheState.STALE)
                logStale(StarsOutcomes.DECODE_ERROR, it)
                return BotStarBalanceResult(it.balance.copy(stale = true), CacheState.STALE, cacheAgeSeconds = ageSeconds)
            }
            recordOutcome(StarsOutcomes.DECODE_ERROR)
            throw e
        } catch (e: Exception) {
            cached?.let {
                val ageSeconds = it.ageSeconds()
                if (maxStaleSeconds != null && ageSeconds > maxStaleSeconds) {
                    recordOutcome(StarsOutcomes.OTHER)
                    recordBoundedStale(StarsOutcomes.OTHER)
                    logStaleCutoff(StarsOutcomes.OTHER, it, maxStaleSeconds)
                    throw e
                }
                recordOutcome(StarsOutcomes.STALE_RETURNED)
                recordCache(CacheState.STALE)
                logStale(StarsOutcomes.OTHER, it)
                return BotStarBalanceResult(it.balance.copy(stale = true), CacheState.STALE, cacheAgeSeconds = ageSeconds)
            }
            recordOutcome(StarsOutcomes.OTHER)
            throw e
        } finally {
            // Intentional double stop to publish into both the new and legacy timers.
            botTimer?.let { timer -> sample?.stop(timer) }
            legacyTimer?.let { timer -> sample?.stop(timer) }
        }
    }

    private fun recordOutcome(outcome: String) {
        registry?.counter(StarsMetrics.CNT_OUTCOME, StarsMetrics.LABEL_OUTCOME, outcome)?.increment()
    }

    private fun recordCache(state: CacheState) {
        registry?.counter(StarsMetrics.CNT_CACHE, StarsMetrics.LABEL_STATE, state.label())?.increment()
    }

    private fun recordBoundedStale(reason: String) {
        registry?.counter(StarsMetrics.CNT_BOUNDED_STALE, StarsMetrics.LABEL_REASON, reason)?.increment()
    }

    private fun logStale(reason: String, cached: CachedBalance) {
        logger.debug(
            "stars: serving STALE cache (reason={}, age_s={})",
            reason,
            (System.currentTimeMillis() - cached.storedAtMs) / 1000,
        )
    }

    private fun logStaleCutoff(reason: String, cached: CachedBalance, maxAgeSeconds: Long) {
        logger.warn(
            "stars: cache too old for stale fallback (reason={}, age_s={}, max_s={})",
            reason,
            (System.currentTimeMillis() - cached.storedAtMs) / 1000,
            maxAgeSeconds,
        )
    }

    private fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1000

    private data class CachedBalance(val balance: BotStarBalance, val storedAtMs: Long) {
        fun isExpired(ttlSeconds: Long): Boolean {
            val now = System.currentTimeMillis()
            return now - storedAtMs >= TimeUnit.SECONDS.toMillis(ttlSeconds)
        }

        fun ageSeconds(): Long = ((System.currentTimeMillis() - storedAtMs) / 1000).coerceAtLeast(0)
    }
}
