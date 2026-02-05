package security

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.math.min

data class RateLimitConfig(
    val capacity: Int,
    val refillPerMinute: Int,
)

private class TokenBucket(
    private val capacity: Int,
    private val refillPerMinute: Int,
    private val clock: Clock,
) {
    private var tokens: Double = capacity.toDouble()
    private var lastRefill: Instant = Instant.now(clock)

    @Synchronized
    fun tryConsume(): Pair<Boolean, Long?> {
        refill()
        return if (tokens >= 1.0) {
            tokens -= 1.0
            true to null
        } else {
            val need = 1.0 - tokens
            val perSec = refillPerMinute / 60.0
            val seconds = if (perSec > 0) floor(need / perSec).toLong().coerceAtLeast(1) else 60L
            false to seconds
        }
    }

    private fun refill() {
        val now = Instant.now(clock)
        val elapsed = Duration.between(lastRefill, now).toMillis().coerceAtLeast(0)
        if (elapsed == 0L || refillPerMinute <= 0) return
        val perMs = refillPerMinute / 60_000.0
        tokens = min(capacity.toDouble(), tokens + elapsed * perMs)
        lastRefill = now
    }
}

class RateLimiter(
    private val cfg: RateLimitConfig,
    private val clock: Clock,
) {
    private val buckets = ConcurrentHashMap<String, TokenBucket>()

    fun tryAcquire(subject: String): Pair<Boolean, Long?> {
        val bucket =
            buckets.computeIfAbsent(subject) {
                TokenBucket(cfg.capacity, cfg.refillPerMinute, clock)
            }
        return bucket.tryConsume()
    }
}
