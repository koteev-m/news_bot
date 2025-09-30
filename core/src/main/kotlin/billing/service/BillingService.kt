package billing.service

import billing.model.BillingPlan
import billing.model.SubStatus
import billing.model.Tier
import billing.model.UserSubscription
import billing.port.BillingRepository
import billing.port.StarsGateway
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory

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

data class ApplyPaymentOutcome(val duplicate: Boolean)

interface BillingServiceWithOutcome : BillingService {
    suspend fun applySuccessfulPaymentWithOutcome(
        userId: Long,
        tier: Tier,
        amountXtr: Long,
        providerPaymentId: String?,
        payload: String?
    ): Result<ApplyPaymentOutcome>
}

suspend fun BillingService.applySuccessfulPaymentOutcome(
    userId: Long,
    tier: Tier,
    amountXtr: Long,
    providerPaymentId: String?,
    payload: String?
): Result<ApplyPaymentOutcome> {
    return if (this is BillingServiceWithOutcome) {
        applySuccessfulPaymentWithOutcome(userId, tier, amountXtr, providerPaymentId, payload)
    } else {
        applySuccessfulPayment(userId, tier, amountXtr, providerPaymentId, payload)
            .map { ApplyPaymentOutcome(duplicate = false) }
    }
}

class BillingServiceImpl(
    private val repo: BillingRepository,
    private val stars: StarsGateway,
    private val defaultDurationDays: Long,
    private val clock: Clock = Clock.systemUTC()
) : BillingService, BillingServiceWithOutcome {

    private val logger = LoggerFactory.getLogger(BillingServiceImpl::class.java)

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
    ): Result<Unit> {
        return applySuccessfulPaymentWithOutcome(userId, tier, amountXtr, providerPaymentId, payload)
            .map { }
    }

    override suspend fun applySuccessfulPaymentWithOutcome(
        userId: Long,
        tier: Tier,
        amountXtr: Long,
        providerPaymentId: String?,
        payload: String?
    ): Result<ApplyPaymentOutcome> = runCatching {
        require(amountXtr >= 0) { "amountXtr must be >= 0" }

        val pid = providerPaymentId ?: deterministicChargeId(userId, tier, payload)

        val isNew = repo.recordStarPaymentIfNew(
            userId = userId,
            tier = tier,
            amountXtr = amountXtr,
            providerPaymentId = pid,
            payload = payload,
            status = SubStatus.ACTIVE
        )

        if (!isNew) {
            logger.info("stars-payment reason=duplicate")
            return@runCatching ApplyPaymentOutcome(duplicate = true)
        }

        val expiresAt = now().plus(Duration.ofDays(defaultDurationDays))
        repo.upsertSubscription(
            userId = userId,
            tier = tier,
            status = SubStatus.ACTIVE,
            expiresAt = expiresAt,
            lastPaymentId = pid
        )
        logger.info("stars-payment reason=applied_ok")
        ApplyPaymentOutcome(duplicate = false)
    }

    override suspend fun getMySubscription(userId: Long): Result<UserSubscription?> =
        runCatching { repo.findSubscription(userId) }

    private fun buildPayload(userId: Long, tier: Tier): String {
        val raw = "$userId:${tier.name}:${UUID.randomUUID().toString().replace("-", "")}"
        return raw.take(64)
    }

    private fun now(): Instant = Instant.now(clock)

    private fun deterministicChargeId(userId: Long, tier: Tier, payload: String?): String {
        val source = "xtr:$userId:${tier.name}:${payload ?: ""}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(StandardCharsets.UTF_8))
        val hex = buildString(digest.size * 2) {
            for (byte in digest) {
                append(String.format("%02x", byte))
            }
        }
        return hex.take(64)
    }
}
