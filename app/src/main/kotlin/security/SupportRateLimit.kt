package security

import java.time.Clock

object SupportRateLimit {
    @Volatile
    private var limiter: RateLimiter? = null

    fun get(
        config: RateLimitConfig,
        clock: Clock = Clock.systemUTC(),
    ): RateLimiter {
        val existing = limiter
        if (existing != null) {
            return existing
        }
        return synchronized(this) {
            limiter ?: RateLimiter(config, clock).also { created ->
                limiter = created
            }
        }
    }
}
