package billing.stars

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class StarsService(
    private val client: StarsClient,
    private val ttlSeconds: Long,
    meterRegistry: MeterRegistry? = null,
) : StarBalancePort {

    private val cache = ConcurrentHashMap<Long, CachedBalance>()
    private val registry = meterRegistry
    private val timer: Timer? = registry?.let { Timer.builder("stars_balance_fetch_seconds").register(it) }

    override suspend fun getMyStarBalance(userId: Long): StarBalance {
        val cached = cache[userId]
        if (cached != null && !cached.isExpired(ttlSeconds)) {
            return cached.balance
        }

        val sample = registry?.let { Timer.start(it) }
        val fetched = client.getMyStarBalance(userId)
        timer?.let { t -> sample?.stop(t) }
        cache[userId] = CachedBalance(fetched, System.currentTimeMillis())
        return fetched
    }

    private data class CachedBalance(val balance: StarBalance, val storedAtMs: Long) {
        fun isExpired(ttlSeconds: Long): Boolean {
            val now = System.currentTimeMillis()
            return now - storedAtMs > TimeUnit.SECONDS.toMillis(ttlSeconds)
        }
    }
}
