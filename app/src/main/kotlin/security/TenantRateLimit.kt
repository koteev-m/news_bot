package security

import tenancy.TenantContext
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

data class Bucket(var tokens: Double, var lastRefillMs: Long)

class TenantRateLimiter(
    private val rpsSoft: Int,
    private val rpsHard: Int,
    private val clock: Clock = Clock.systemUTC()
) {
    private val buckets = ConcurrentHashMap<Long, Bucket>()

    fun allow(ctx: TenantContext): Boolean {
        val now = clock.millis()
        val bucket = buckets.computeIfAbsent(ctx.tenant.tenantId) { Bucket(tokens = rpsHard.toDouble(), lastRefillMs = now) }
        val elapsed = (now - bucket.lastRefillMs).coerceAtLeast(0)
        val refill = elapsed / 1000.0 * rpsHard
        bucket.tokens = (bucket.tokens + refill).coerceAtMost(rpsHard.toDouble())
        bucket.lastRefillMs = now
        return if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0
            true
        } else {
            false
        }
    }

    fun softExceeded(ctx: TenantContext): Boolean {
        return false
    }
}
