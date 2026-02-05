package repo

import kotlin.test.Test
import kotlin.test.assertEquals

class PricingRepositoryTest {
    @Test
    fun `price override data class preserves values`() {
        val override =
            PriceOverride(
                key = "price_bundle",
                variant = "A",
                tier = "PRO",
                priceXtr = 1000L,
                starsPackage = 10L,
            )
        assertEquals("price_bundle", override.key)
        assertEquals("A", override.variant)
        assertEquals("PRO", override.tier)
        assertEquals(1000L, override.priceXtr)
        assertEquals(10L, override.starsPackage)
    }

    @Test
    fun `paywall copy data class preserves values`() {
        val copy =
            PaywallCopy(
                key = "paywall_copy",
                variant = "B",
                headingEn = "Heading",
                subEn = "Subtitle",
                ctaEn = "CTA",
            )
        assertEquals("paywall_copy", copy.key)
        assertEquals("B", copy.variant)
        assertEquals("Heading", copy.headingEn)
        assertEquals("Subtitle", copy.subEn)
        assertEquals("CTA", copy.ctaEn)
    }
}
