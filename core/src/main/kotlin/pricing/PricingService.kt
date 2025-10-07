package pricing

import kotlinx.serialization.Serializable

@Serializable
data class Offer(val tier: String, val priceXtr: Long, val starsPackage: Long? = null)

@Serializable
data class PaywallPayload(
    val copyVariant: String,
    val priceVariant: String,
    val headingEn: String,
    val subEn: String,
    val ctaEn: String,
    val offers: List<Offer>,
)

interface PricingPort {
    suspend fun getBasePlans(): Map<String, Long>
    suspend fun listOverrides(key: String, variant: String): List<Offer>
    suspend fun getCopy(key: String, variant: String): Triple<String, String, String>?
}

class PricingService(private val port: PricingPort) {
    suspend fun buildPaywall(userId: Long?, copyVariant: String, priceVariant: String): PaywallPayload {
        val basePlans = port.getBasePlans()
        val paidBasePlans = basePlans.filterKeys { key -> key != "FREE" }
        val overrides = port.listOverrides("price_bundle", priceVariant).associateBy { offer -> offer.tier }
        val offers = paidBasePlans.mapNotNull { (tier, price) ->
            val override = overrides[tier]
            Offer(
                tier = tier,
                priceXtr = override?.priceXtr ?: price,
                starsPackage = override?.starsPackage,
            )
        }.sortedBy { offer -> offer.priceXtr }
        val copy = port.getCopy("paywall_copy", copyVariant)
            ?: Triple("Upgrade for more", "Get Pro features today", "Upgrade now")
        return PaywallPayload(
            copyVariant = copyVariant,
            priceVariant = priceVariant,
            headingEn = copy.first,
            subEn = copy.second,
            ctaEn = copy.third,
            offers = offers,
        )
    }
}
