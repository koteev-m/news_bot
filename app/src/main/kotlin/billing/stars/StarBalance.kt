package billing.stars

import kotlinx.serialization.Serializable

@Serializable
data class StarBalance(
    val userId: Long,
    val available: Long,
    val pending: Long,
    val updatedAtEpochSeconds: Long
)

interface StarBalancePort {
    suspend fun getMyStarBalance(userId: Long): StarBalance
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
    val autoRenew: Boolean
)

class InMemoryStarBalancePort : StarBalancePort {
    private val balances = mutableMapOf<Long, StarBalance>()

    override suspend fun getMyStarBalance(userId: Long): StarBalance {
        return balances[userId] ?: StarBalance(userId, available = 0, pending = 0, updatedAtEpochSeconds = now())
    }

    fun update(balance: StarBalance) {
        balances[balance.userId] = balance.copy(updatedAtEpochSeconds = now())
    }

    private fun now(): Long = System.currentTimeMillis() / 1000
}
