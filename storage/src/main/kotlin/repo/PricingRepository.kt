package repo

import db.DatabaseFactory.dbQuery
import java.time.Instant
import java.time.ZoneOffset
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update

object PricingOverridesTable : Table("pricing_overrides") {
    val key = text("key")
    val variant = text("variant")
    val tier = text("tier")
    val priceXtr = long("price_xtr")
    val starsPackage = long("stars_package").nullable()
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(key, variant, tier)
}

object PaywallCopyTable : Table("paywall_copy") {
    val key = text("key")
    val variant = text("variant")
    val headingEn = text("heading_en")
    val subEn = text("sub_en")
    val ctaEn = text("cta_en")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(key, variant)
}

data class PriceOverride(
    val key: String,
    val variant: String,
    val tier: String,
    val priceXtr: Long,
    val starsPackage: Long?,
)

data class PaywallCopy(
    val key: String,
    val variant: String,
    val headingEn: String,
    val subEn: String,
    val ctaEn: String,
)

class PricingRepository {
    suspend fun getBasePlans(): Map<String, Long> = dbQuery {
        BillingPlansTable
            .select { BillingPlansTable.isActive eq true }
            .associate { row -> row[BillingPlansTable.tier] to row[BillingPlansTable.priceXtr] }
    }

    suspend fun listOverrides(key: String, variant: String): List<PriceOverride> = dbQuery {
        PricingOverridesTable
            .select { (PricingOverridesTable.key eq key) and (PricingOverridesTable.variant eq variant) }
            .map { row ->
                PriceOverride(
                    key = row[PricingOverridesTable.key],
                    variant = row[PricingOverridesTable.variant],
                    tier = row[PricingOverridesTable.tier],
                    priceXtr = row[PricingOverridesTable.priceXtr],
                    starsPackage = row[PricingOverridesTable.starsPackage],
                )
            }
    }

    suspend fun upsertOverride(override: PriceOverride) = dbQuery {
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        val updated = PricingOverridesTable.update({
            (PricingOverridesTable.key eq override.key) and
                (PricingOverridesTable.variant eq override.variant) and
                (PricingOverridesTable.tier eq override.tier)
        }) { statement ->
            statement[priceXtr] = override.priceXtr
            statement[starsPackage] = override.starsPackage
            statement[updatedAt] = now
        }
        if (updated == 0) {
            PricingOverridesTable.insert { statement ->
                statement[key] = override.key
                statement[variant] = override.variant
                statement[tier] = override.tier
                statement[priceXtr] = override.priceXtr
                statement[starsPackage] = override.starsPackage
                statement[updatedAt] = now
            }
        }
    }

    suspend fun getCopy(key: String, variant: String): PaywallCopy? = dbQuery {
        PaywallCopyTable
            .select { (PaywallCopyTable.key eq key) and (PaywallCopyTable.variant eq variant) }
            .firstOrNull()
            ?.let { row ->
                PaywallCopy(
                    key = row[PaywallCopyTable.key],
                    variant = row[PaywallCopyTable.variant],
                    headingEn = row[PaywallCopyTable.headingEn],
                    subEn = row[PaywallCopyTable.subEn],
                    ctaEn = row[PaywallCopyTable.ctaEn],
                )
            }
    }

    suspend fun upsertCopy(copy: PaywallCopy) = dbQuery {
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        val updated = PaywallCopyTable.update({
            (PaywallCopyTable.key eq copy.key) and (PaywallCopyTable.variant eq copy.variant)
        }) { statement ->
            statement[headingEn] = copy.headingEn
            statement[subEn] = copy.subEn
            statement[ctaEn] = copy.ctaEn
            statement[updatedAt] = now
        }
        if (updated == 0) {
            PaywallCopyTable.insert { statement ->
                statement[key] = copy.key
                statement[variant] = copy.variant
                statement[headingEn] = copy.headingEn
                statement[subEn] = copy.subEn
                statement[ctaEn] = copy.ctaEn
                statement[updatedAt] = now
            }
        }
    }
}
