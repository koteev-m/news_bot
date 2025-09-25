package billing

import billing.model.BillingPlan
import billing.model.SubStatus
import billing.model.Tier
import billing.model.UserSubscription
import billing.model.Xtr
import billing.port.BillingRepository
import billing.port.StarsGateway
import billing.service.BillingService
import billing.service.BillingServiceImpl
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class BillingServiceTest {

    private val fixedNow = Instant.parse("2024-01-01T00:00:00Z")
    private val clock: Clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    @Test
    fun `listPlans sorts tiers ascending`() = runBlocking {
        val repo = FakeRepo(fixedNow).apply {
            plans += BillingPlan(Tier.VIP, "VIP", Xtr(9999), isActive = true)
            plans += BillingPlan(Tier.PRO, "Pro", Xtr(1000), isActive = true)
            plans += BillingPlan(Tier.PRO_PLUS, "Pro Plus", Xtr(2500), isActive = true)
            plans += BillingPlan(Tier.FREE, "Free", Xtr(0), isActive = true)
        }
        val stars = FakeStarsGateway()
        val service = createService(repo, stars)

        val result = service.listPlans()

        assertTrue(result.isSuccess)
        val ordered = result.getOrNull()!!.map { it.tier }
        assertEquals(listOf(Tier.FREE, Tier.PRO, Tier.PRO_PLUS, Tier.VIP), ordered)
    }

    @Test
    fun `createInvoiceFor rejects free tier`() = runBlocking {
        val repo = FakeRepo(fixedNow)
        val stars = FakeStarsGateway()
        val service = createService(repo, stars)

        val failure = service.createInvoiceFor(userId = 1L, tier = Tier.FREE)

        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `createInvoiceFor fails when plan missing or inactive`() = runBlocking {
        val repo = FakeRepo(fixedNow).apply {
            plans += BillingPlan(Tier.PRO, "Pro", Xtr(1000), isActive = false)
        }
        val stars = FakeStarsGateway()
        val service = createService(repo, stars)

        val failure = service.createInvoiceFor(userId = 42L, tier = Tier.PRO)

        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull() is NoSuchElementException)
    }

    @Test
    fun `createInvoiceFor succeeds with payload under limit`() = runBlocking {
        val repo = FakeRepo(fixedNow).apply {
            plans += BillingPlan(Tier.PRO_PLUS, "Pro Plus", Xtr(3200), isActive = true)
        }
        val stars = FakeStarsGateway()
        val service = createService(repo, stars)

        val result = service.createInvoiceFor(userId = 77L, tier = Tier.PRO_PLUS)

        assertTrue(result.isSuccess)
        assertEquals("https://t.me/pay/ok", result.getOrNull())
        val payload = stars.lastPayload
        assertTrue(payload != null && payload.length <= 64)
    }

    @Test
    fun `applySuccessfulPayment activates subscription and is idempotent`() = runBlocking {
        val repo = FakeRepo(fixedNow)
        val stars = FakeStarsGateway()
        val service = createService(repo, stars)

        val first = service.applySuccessfulPayment(
            userId = 55L,
            tier = Tier.VIP,
            amountXtr = 9999,
            providerPaymentId = "pay-1",
            payload = "55:VIP:${UUID.randomUUID()}"
        )

        assertTrue(first.isSuccess)
        assertEquals(1, repo.payments.size)
        val subscription = repo.subscriptions[55L]
        assertEquals(Tier.VIP, subscription?.tier)
        assertEquals(SubStatus.ACTIVE, subscription?.status)
        assertEquals(fixedNow.plus(Duration.ofDays(30)), subscription?.expiresAt)

        val second = service.applySuccessfulPayment(
            userId = 55L,
            tier = Tier.VIP,
            amountXtr = 9999,
            providerPaymentId = "pay-1",
            payload = "55:VIP:duplicate"
        )

        assertTrue(second.isSuccess)
        assertEquals(1, repo.payments.size)
        assertEquals(subscription, repo.subscriptions[55L])
    }

    @Test
    fun `getMySubscription returns stored value or null`() = runBlocking {
        val repo = FakeRepo(fixedNow)
        val stored = UserSubscription(
            userId = 101L,
            tier = Tier.PRO,
            status = SubStatus.ACTIVE,
            startedAt = fixedNow,
            expiresAt = fixedNow.plus(Duration.ofDays(10))
        )
        repo.subscriptions[101L] = stored
        val service = createService(repo, FakeStarsGateway())

        val present = service.getMySubscription(101L)
        val missing = service.getMySubscription(202L)

        assertEquals(stored, present.getOrNull())
        assertNull(missing.getOrNull())
    }

    @Test
    fun `applySuccessfulPayment rejects negative amount`() = runBlocking {
        val repo = FakeRepo(fixedNow)
        val service = createService(repo, FakeStarsGateway())

        val failure = service.applySuccessfulPayment(
            userId = 7L,
            tier = Tier.PRO,
            amountXtr = -10,
            providerPaymentId = "neg",
            payload = "7:PRO:oops"
        )

        assertTrue(failure.isFailure)
        assertFailsWith<IllegalArgumentException> { failure.getOrThrow() }
    }

    private fun createService(repo: FakeRepo, stars: FakeStarsGateway): BillingService {
        return BillingServiceImpl(repo, stars, defaultDurationDays = 30, clock = clock)
    }

    private class FakeRepo(private val defaultStartedAt: Instant) : BillingRepository {
        val plans = mutableListOf<BillingPlan>()
        val subscriptions = mutableMapOf<Long, UserSubscription>()
        val payments = mutableListOf<PaymentRecord>()
        private val seenPaymentIds = mutableSetOf<String>()

        override suspend fun getActivePlans(): List<BillingPlan> = plans.toList()

        override suspend fun upsertSubscription(
            userId: Long,
            tier: Tier,
            status: SubStatus,
            expiresAt: Instant?,
            lastPaymentId: String?
        ) {
            val existing = subscriptions[userId]
            val startedAt = existing?.startedAt ?: defaultStartedAt
            val updated = UserSubscription(
                userId = userId,
                tier = tier,
                status = status,
                startedAt = startedAt,
                expiresAt = expiresAt
            )
            subscriptions[userId] = updated
        }

        override suspend fun findSubscription(userId: Long): UserSubscription? = subscriptions[userId]

        override suspend fun recordStarPaymentIfNew(
            userId: Long,
            tier: Tier,
            amountXtr: Long,
            providerPaymentId: String?,
            payload: String?,
            status: SubStatus
        ): Boolean {
            if (providerPaymentId != null && !seenPaymentIds.add(providerPaymentId)) {
                return false
            }
            payments += PaymentRecord(userId, tier, amountXtr, providerPaymentId, payload, status)
            return true
        }
    }

    private class FakeStarsGateway : StarsGateway {
        var lastTier: Tier? = null
        var lastPriceXtr: Long? = null
        var lastPayload: String? = null
        var nextResult: Result<String> = Result.success("https://t.me/pay/ok")

        override suspend fun createInvoiceLink(tier: Tier, priceXtr: Long, payload: String): Result<String> {
            lastTier = tier
            lastPriceXtr = priceXtr
            lastPayload = payload
            return nextResult
        }
    }

    private data class PaymentRecord(
        val userId: Long,
        val tier: Tier,
        val amountXtr: Long,
        val providerPaymentId: String?,
        val payload: String?,
        val status: SubStatus
    )
}
