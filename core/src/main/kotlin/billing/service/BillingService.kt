package billing.service

import billing.StarsGateway
import billing.model.BillingPlan
import billing.model.SubStatus
import billing.model.Tier
import billing.model.UserSubscription
import repo.BillingRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

interface BillingService {
    /** Активные планы, отсортированы по возрастанию уровня. */
    suspend fun listPlans(): Result<List<BillingPlan>>

    /**
     * Создать invoice-link для оплаты Stars (XTR).
     * Требования: tier != FREE, план активен, payload ≤ 64 байт.
     * Возвращает Result.success(url) либо Result.failure(e).
     */
    suspend fun createInvoiceFor(userId: Long, tier: Tier): Result<String>

    /**
     * Принять успешный платёж (идемпотентно по providerPaymentId) и активировать/продлить подписку.
     * Если recordStarPaymentIfNew вернул false — считать операцию успешной (повторная доставка), вернуть success.
     */
    suspend fun applySuccessfulPayment(
        userId: Long,
        tier: Tier,
        amountXtr: Long,
        providerPaymentId: String?,
        payload: String?
    ): Result<Unit>

    /** Текущая подписка пользователя (или null, если нет). */
    suspend fun getMySubscription(userId: Long): Result<UserSubscription?>
}

class BillingServiceImpl(
    private val repo: BillingRepository,
    private val stars: StarsGateway,
    private val defaultDurationDays: Long,
    private val clock: Clock = Clock.systemUTC()
) : BillingService {

    init {
        require(defaultDurationDays >= 1) { "defaultDurationDays must be >= 1" }
    }

    override suspend fun listPlans(): Result<List<BillingPlan>> {
        return try {
            val plans = repo.getActivePlans().sortedBy { it.tier.level() }
            Result.success(plans)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    override suspend fun createInvoiceFor(userId: Long, tier: Tier): Result<String> {
        return try {
            require(tier != Tier.FREE) { "tier must not be FREE" }
            val plan = repo.getActivePlans()
                .firstOrNull { it.tier == tier && it.isActive }
                ?: return Result.failure(NoSuchElementException("plan not found: $tier"))
            val payload = buildPayload(userId, tier)
            val priceXtr = plan.priceXtr.value
            stars.createInvoiceLink(tier, priceXtr, payload)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    override suspend fun applySuccessfulPayment(
        userId: Long,
        tier: Tier,
        amountXtr: Long,
        providerPaymentId: String?,
        payload: String?
    ): Result<Unit> {
        return try {
            require(amountXtr >= 0) { "amountXtr must be >= 0" }
            val inserted = repo.recordStarPaymentIfNew(
                userId,
                tier,
                amountXtr,
                providerPaymentId,
                payload,
                SubStatus.ACTIVE
            )
            if (!inserted) {
                return Result.success(Unit)
            }
            val expiresAt = now().plus(Duration.ofDays(defaultDurationDays))
            repo.upsertSubscription(userId, tier, SubStatus.ACTIVE, expiresAt, providerPaymentId)
            Result.success(Unit)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    override suspend fun getMySubscription(userId: Long): Result<UserSubscription?> {
        return try {
            Result.success(repo.findSubscription(userId))
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    private fun buildPayload(userId: Long, tier: Tier): String {
        val seed = UUID.randomUUID().toString().replace("-", "")
        val raw = "$userId:${tier.name}:$seed"
        return if (raw.length <= 64) raw else raw.substring(0, 64)
    }

    private fun now(): Instant = Instant.now(clock)
}
