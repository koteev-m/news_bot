package billing.service

import billing.model.BillingPlan
import billing.model.SubStatus
import billing.model.Tier
import billing.model.UserSubscription
import billing.port.BillingRepository
import billing.port.StarsGateway
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

interface BillingService {
    /** Активные планы, отсортированы по возрастанию уровня tier. */
    suspend fun listPlans(): Result<List<BillingPlan>>

    /**
     * Создаёт invoice-link для тарифа (Stars/XTR).
     * Требования: tier != FREE, план активен, payload ≤ 64 байт.
     * Возвращает Result.success(url) либо Result.failure(e).
     */
    suspend fun createInvoiceFor(userId: Long, tier: Tier): Result<String>

    /**
     * Применяет успешный платёж XTR (идемпотентно по providerPaymentId) и активирует/продлевает подписку.
     * Если запись платежа уже существует (повторная доставка) — вернуть success.
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

    override suspend fun listPlans(): Result<List<BillingPlan>> = runCatching {
        repo.getActivePlans().sortedBy { it.tier.level() }
    }

    override suspend fun createInvoiceFor(userId: Long, tier: Tier): Result<String> = runCatching {
        require(tier != Tier.FREE) { "Cannot create invoice for FREE tier" }
        val plan = repo.getActivePlans().firstOrNull { it.tier == tier && it.isActive }
            ?: throw NoSuchElementException("plan not found: $tier")
        val priceXtr = plan.priceXtr.value
        require(priceXtr >= 0) { "priceXtr must be >= 0" }
        val payload = buildPayload(userId, tier)
        stars.createInvoiceLink(tier, priceXtr, payload).getOrThrow()
    }

    override suspend fun applySuccessfulPayment(
        userId: Long,
        tier: Tier,
        amountXtr: Long,
        providerPaymentId: String?,
        payload: String?
    ): Result<Unit> = runCatching {
        require(amountXtr >= 0) { "amountXtr must be >= 0" }

        repo.recordStarPaymentIfNew(
            userId = userId,
            tier = tier,
            amountXtr = amountXtr,
            providerPaymentId = providerPaymentId,
            payload = payload,
            status = SubStatus.ACTIVE
        )

        val expiresAt = now().plus(Duration.ofDays(defaultDurationDays))
        repo.upsertSubscription(
            userId = userId,
            tier = tier,
            status = SubStatus.ACTIVE,
            expiresAt = expiresAt,
            lastPaymentId = providerPaymentId
        )
    }

    override suspend fun getMySubscription(userId: Long): Result<UserSubscription?> =
        runCatching { repo.findSubscription(userId) }

    private fun buildPayload(userId: Long, tier: Tier): String {
        val raw = "$userId:${tier.name}:${UUID.randomUUID().toString().replace("-", "")}"
        return raw.take(64)
    }

    private fun now(): Instant = Instant.now(clock)
}
