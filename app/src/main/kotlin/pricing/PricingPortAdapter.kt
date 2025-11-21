package pricing

import repo.PaywallCopy
import repo.PriceOverride
import repo.PricingRepository

class PricingPortAdapter(
    private val repository: PricingRepository,
) : PricingPort {
    override suspend fun getBasePlans(): Map<String, Long> = repository.getBasePlans()

    override suspend fun listOverrides(
        key: String,
        variant: String,
    ): List<Offer> = repository.listOverrides(key, variant).map(::toOffer)

    override suspend fun getCopy(
        key: String,
        variant: String,
    ): Triple<String, String, String>? = repository.getCopy(key, variant)?.let(::toCopy)

    private fun toOffer(override: PriceOverride): Offer =
        Offer(
            tier = override.tier,
            priceXtr = override.priceXtr,
            starsPackage = override.starsPackage,
        )

    private fun toCopy(copy: PaywallCopy): Triple<String, String, String> =
        Triple(copy.headingEn, copy.subEn, copy.ctaEn)
}
