package billing

import billing.model.BillingPlan
import billing.model.BillingPlanDto
import billing.model.SubStatus
import billing.model.Tier
import billing.model.UserSubscription
import billing.model.UserSubscriptionDto
import billing.model.Xtr
import billing.model.toDomain
import billing.model.toDto
import billing.model.toInstantUnsafe
import billing.model.toIsoString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BillingModelsTest {
    private val json = Json

    @Test
    fun `tier parse handles different cases`() {
        assertEquals(Tier.FREE, Tier.parse("free"))
        assertEquals(Tier.PRO, Tier.parse("Pro"))
        assertEquals(Tier.PRO_PLUS, Tier.parse("PRO_PLUS"))
        assertEquals(Tier.VIP, Tier.parse("vip"))
    }

    @Test
    fun `tier parse throws on unknown value`() {
        val ex = assertFailsWith<IllegalArgumentException> { Tier.parse("gold") }
        assertTrue(ex.message!!.contains("Unknown tier"))
    }

    @Test
    fun `sub status parse handles different cases`() {
        assertEquals(SubStatus.ACTIVE, SubStatus.parse("active"))
        assertEquals(SubStatus.EXPIRED, SubStatus.parse("Expired"))
        assertEquals(SubStatus.CANCELLED, SubStatus.parse("CANCELLED"))
        assertEquals(SubStatus.PENDING, SubStatus.parse("pending"))
    }

    @Test
    fun `sub status parse throws on unknown value`() {
        val ex = assertFailsWith<IllegalArgumentException> { SubStatus.parse("unknown") }
        assertTrue(ex.message!!.contains("Unknown subscription status"))
    }

    @Test
    fun `tier meets respects ordering`() {
        assertFalse(Tier.FREE.meets(Tier.PRO))
        assertTrue(Tier.PRO.meets(Tier.FREE))
        assertTrue(Tier.PRO_PLUS.meets(Tier.PRO))
        assertTrue(Tier.VIP.meets(Tier.PRO_PLUS))
        assertTrue(Tier.VIP.meets(Tier.VIP))
    }

    @Test
    fun `xtr rejects negative values`() {
        val ex = assertFailsWith<IllegalArgumentException> { Xtr(-1) }
        assertTrue(ex.message!!.contains("XTR must be >= 0"))
    }

    @Test
    fun `xtr accepts zero and positive`() {
        assertEquals(0, Xtr(0).value)
        assertEquals(100L, Xtr(100).value)
    }

    @Test
    fun `instant helpers convert to and from iso`() {
        val instant = Instant.parse("2024-01-01T00:00:00Z")
        val iso = instant.toIsoString()
        assertEquals("2024-01-01T00:00:00Z", iso)
        assertEquals(instant, iso.toInstantUnsafe())
    }

    @Test
    fun `billing plan dto serialization round trip`() {
        val dto =
            BillingPlanDto(
                tier = "PRO",
                title = "Pro Plan",
                priceXtr = 1999,
                isActive = true,
            )
        val serialized = json.encodeToString(dto)
        val deserialized = json.decodeFromString(BillingPlanDto.serializer(), serialized)
        assertEquals(dto, deserialized)
    }

    @Test
    fun `user subscription dto serialization round trip`() {
        val dto =
            UserSubscriptionDto(
                userId = 42L,
                tier = "VIP",
                status = "ACTIVE",
                startedAt = "2024-01-01T00:00:00Z",
                expiresAt = "2024-02-01T00:00:00Z",
            )
        val serialized = json.encodeToString(dto)
        val deserialized = json.decodeFromString(UserSubscriptionDto.serializer(), serialized)
        assertEquals(dto, deserialized)
    }

    @Test
    fun `domain to dto and back`() {
        val subscription =
            UserSubscription(
                userId = 1L,
                tier = Tier.PRO_PLUS,
                status = SubStatus.PENDING,
                startedAt = Instant.parse("2024-01-10T10:15:30Z"),
                expiresAt = Instant.parse("2024-02-10T10:15:30Z"),
            )
        val plan =
            BillingPlan(
                tier = Tier.PRO_PLUS,
                title = "Pro Plus",
                priceXtr = Xtr(5000),
                isActive = true,
            )

        val planDto = plan.toDto()
        val planDomain = planDto.toDomain()
        assertEquals(plan, planDomain)

        val subscriptionDto = subscription.toDto()
        assertEquals(subscription.userId, subscriptionDto.userId)
        assertEquals(subscription.tier.name, subscriptionDto.tier)
        assertEquals(subscription.status.name, subscriptionDto.status)
        assertEquals(subscription.startedAt.toIsoString(), subscriptionDto.startedAt)
        assertEquals(subscription.expiresAt?.toIsoString(), subscriptionDto.expiresAt)

        val subscriptionDomain = subscriptionDto.toDomain()
        assertEquals(subscription, subscriptionDomain)
    }
}
