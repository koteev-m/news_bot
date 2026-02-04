package billing.stars

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.min

class BotBalanceRateLimiter(
    private val capacity: Int,
    private val refillPerMinute: Int,
    private val cleanupThreshold: Int = 1024,
    private val staleAfterNanos: Long = TimeUnit.MINUTES.toNanos(30),
    private val cleanupIntervalNanos: Long = TimeUnit.MINUTES.toNanos(5),
    private val nanoTimeProvider: () -> Long = System::nanoTime,
) {
    private val buckets = ConcurrentHashMap<Long, TokenBucket>()
    private val lastSeen = ConcurrentHashMap<Long, Long>()

    @Volatile
    private var lastCleanupNanos: Long = 0

    init {
        require(capacity > 0) { "capacity must be positive" }
        require(refillPerMinute > 0) { "refillPerMinute must be positive" }
    }

    fun check(subjectId: Long): RateLimitVerdict {
        val now = nanoTimeProvider()
        lastSeen[subjectId] = now
        maybeCleanup(now)
        val bucket = buckets.computeIfAbsent(subjectId) { TokenBucket(capacity, refillPerMinute, nanoTimeProvider) }
        return bucket.tryConsume(now)
    }

    internal fun bucketCount(): Int = buckets.size

    private fun maybeCleanup(now: Long) {
        if (buckets.size < cleanupThreshold) return
        if (now - lastCleanupNanos < cleanupIntervalNanos) return

        lastCleanupNanos = now
        val cutoff = now - staleAfterNanos
        lastSeen.entries.removeIf { (subjectId, seenAt) ->
            if (seenAt < cutoff) {
                buckets.remove(subjectId)
                true
            } else {
                false
            }
        }
    }
}

sealed interface RateLimitVerdict {
    object Allowed : RateLimitVerdict
    data class Denied(val retryAfterSeconds: Int) : RateLimitVerdict
}

private class TokenBucket(
    capacity: Int,
    refillPerMinute: Int,
    private val nanoTimeProvider: () -> Long,
) {
    private val maxTokens = capacity.toDouble()
    private val refillPerSecond = refillPerMinute / 60.0

    @Volatile
    private var tokens = maxTokens

    @Volatile
    private var lastRefillNanos = nanoTimeProvider()

    fun tryConsume(nowNanos: Long = nanoTimeProvider()): RateLimitVerdict {
        synchronized(this) {
            refill(nowNanos)
            if (tokens >= 1.0) {
                tokens -= 1.0
                return RateLimitVerdict.Allowed
            }

            val needed = 1.0 - tokens
            val seconds = if (refillPerSecond <= 0.0) {
                1.0
            } else {
                needed / refillPerSecond
            }
            val retryAfter = ceil(seconds).toInt().coerceAtLeast(1)
            return RateLimitVerdict.Denied(retryAfter)
        }
    }

    private fun refill(nowNanos: Long) {
        val elapsedSeconds = (nowNanos - lastRefillNanos) / 1_000_000_000.0
        if (elapsedSeconds <= 0.0) {
            return
        }

        val tokensToAdd = elapsedSeconds * refillPerSecond
        tokens = min(maxTokens, tokens + tokensToAdd)
        lastRefillNanos = nowNanos
    }
}
