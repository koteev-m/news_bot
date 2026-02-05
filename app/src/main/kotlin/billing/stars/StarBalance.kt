package billing.stars

import kotlinx.serialization.Serializable

@Serializable
data class StarBalance(
    val userId: Long,
    val available: Long,
    val pending: Long,
    val updatedAtEpochSeconds: Long,
)

interface StarBalancePort {
    suspend fun getMyStarBalance(userId: Long): StarBalance
}

@Serializable
data class BotStarBalance(
    val available: Long,
    val pending: Long,
    val updatedAtEpochSeconds: Long,
    val stale: Boolean = false,
)

data class BotStarBalanceResult(
    val balance: BotStarBalance,
    val cacheState: CacheState,
    val cacheAgeSeconds: Long? = null,
)

enum class CacheState { HIT, MISS, STALE }

fun CacheState.label(): String =
    when (this) {
        CacheState.HIT -> "hit"
        CacheState.MISS -> "miss"
        CacheState.STALE -> "stale"
    }

interface BotStarBalancePort {
    suspend fun getBotStarBalance(): BotStarBalanceResult
}

interface StarSubscriptionRepository {
    suspend fun save(subscription: StarSubscription)

    suspend fun findActiveByUser(userId: Long): StarSubscription?
}

@Serializable
data class StarSubscription(
    val userId: Long,
    val planCode: String,
    val startedAtEpochSeconds: Long,
    val autoRenew: Boolean,
)

class InMemoryStarBalancePort : StarBalancePort {
    private val balances = mutableMapOf<Long, StarBalance>()

    override suspend fun getMyStarBalance(userId: Long): StarBalance =
        balances[userId] ?: StarBalance(userId, available = 0, pending = 0, updatedAtEpochSeconds = now())

    fun update(balance: StarBalance) {
        balances[balance.userId] = balance.copy(updatedAtEpochSeconds = now())
    }

    private fun now(): Long = System.currentTimeMillis() / MILLIS_IN_SECOND

    companion object {
        private const val MILLIS_IN_SECOND = 1000L
    }
}

class ZeroStarBalancePort : StarBalancePort {
    override suspend fun getMyStarBalance(userId: Long): StarBalance =
        StarBalance(userId, available = 0, pending = 0, updatedAtEpochSeconds = now())

    private fun now(): Long = System.currentTimeMillis() / MILLIS_IN_SECOND

    companion object {
        private const val MILLIS_IN_SECOND = 1000L
    }
}
